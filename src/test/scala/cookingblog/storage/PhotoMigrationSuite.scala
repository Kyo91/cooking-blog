package cookingblog.storage

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.parser.parse
import munit.CatsEffectSuite

import java.nio.file.{AccessDeniedException, Files, Path}

final class PhotoMigrationSuite extends CatsEffectSuite {
  private val key = StorageKey.parse("0123456789abcdef0123456789abcdef").toOption.get
  private val item = PhotoMigrationItem(key, PhotoExtension.Png)
  private val localToS3Scope =
    PhotoMigrationScope(
      PhotoMigrationDirection.LocalToS3,
      "local:/migration-source",
      "s3:destination-a"
    )

  test("copies every variant, verifies it, and does not rewrite verified destination data") {
    directories.use { case (sourceDirectory, destinationDirectory, manifest) =>
      for {
        source <- LocalPhotoStore.create(sourceDirectory)
        destination <- LocalPhotoStore.create(destinationDirectory)
        _ <- populate(source, sourceDirectory)
        migration = PhotoMigration(source, destination)
        first <- migration.run(List(item), options(manifest))
        manifestJson <- IO.blocking(Files.readString(manifest))
        second <- PhotoMigration(source, FailingReadPhotoStore(destination))
          .run(List(item), options(manifest, resume = true))
        bytes <- destination.read(key, PhotoVariant.Display, PhotoExtension.Png).compile.to(Array)
      } yield {
        val manifestCursor = parse(manifestJson).toOption.get.hcursor
        assertEquals(first.copied, 3)
        assertEquals(first.destinationOrphan, 0)
        assertEquals(manifestCursor.get[Int]("schemaVersion"), Right(1))
        assertEquals(manifestCursor.get[String]("direction"), Right("local-to-s3"))
        assertEquals(second.copied, 0)
        assertEquals(second.alreadyVerified, 3)
        assertEquals(bytes.toList, List[Byte](1))
      }
    }
  }

  test("reports missing sources, mismatches, and dry-run missing destinations without deletion") {
    directories.use { case (sourceDirectory, destinationDirectory, manifest) =>
      for {
        source <- LocalPhotoStore.create(sourceDirectory)
        destination <- LocalPhotoStore.create(destinationDirectory)
        display <- file(sourceDirectory, "display.png", Array[Byte](2))
        _ <- source.put(key, PhotoVariant.Display, PhotoExtension.Png, display)
        conflicting <- file(destinationDirectory, "conflicting.png", Array[Byte](9))
        _ <- destination.put(key, PhotoVariant.Display, PhotoExtension.Png, conflicting)
        summary <- PhotoMigration(source, destination).run(
          List(item),
          options(manifest, dryRun = true)
        )
        preserved <- destination
          .read(key, PhotoVariant.Display, PhotoExtension.Png)
          .compile
          .to(Array)
      } yield {
        assertEquals(summary.missingSource, 2)
        assertEquals(summary.mismatched, 1)
        assertEquals(
          summary.diagnostics.map(_.operation).toSet,
          Set("read-source", "verify-destination")
        )
        assert(summary.diagnostics.forall(_.storageKey == StorageKey.value(key)))
        assert(
          summary.diagnostics.forall(diagnostic =>
            diagnostic.category == "not-found" || diagnostic.category == "checksum-mismatch"
          )
        )
        assertEquals(preserved.toList, List[Byte](9))
      }
    }
  }

  test("uses the same verified copy workflow when an S3 source is represented by PhotoStore") {
    directories.use { case (s3SourceDirectory, localTargetDirectory, manifest) =>
      for {
        s3Source <- LocalPhotoStore.create(s3SourceDirectory)
        localTarget <- LocalPhotoStore.create(localTargetDirectory)
        _ <- populate(s3Source, s3SourceDirectory)
        summary <- PhotoMigration(s3Source, localTarget).run(
          List(item),
          options(
            manifest,
            scope = PhotoMigrationScope(
              PhotoMigrationDirection.S3ToLocal,
              "s3:source-a",
              "local:/migration-target"
            )
          )
        )
        original <- localTarget
          .read(key, PhotoVariant.Original, PhotoExtension.Png)
          .compile
          .to(Array)
      } yield {
        assertEquals(summary.copied, 3)
        assertEquals(original.toList, List[Byte](0))
      }
    }
  }

  test("rejects a resume manifest from another direction before reading source data") {
    directories.use { case (sourceDirectory, destinationDirectory, manifest) =>
      for {
        source <- LocalPhotoStore.create(sourceDirectory)
        destination <- LocalPhotoStore.create(destinationDirectory)
        _ <- populate(source, sourceDirectory)
        _ <- PhotoMigration(source, destination).run(List(item), options(manifest))
        error <- interceptIO[IllegalArgumentException] {
          PhotoMigration(FailingReadPhotoStore(destination), source).run(
            List(item),
            options(
              manifest,
              resume = true,
              scope = PhotoMigrationScope(
                PhotoMigrationDirection.S3ToLocal,
                "s3:destination-a",
                "local:/migration-source"
              )
            )
          )
        }
      } yield {
        assert(error.getMessage.contains("migration direction"))
        assert(error.getMessage.contains("without --resume"))
      }
    }
  }

  test("rejects a resume manifest when the destination identity changes") {
    directories.use { case (sourceDirectory, destinationDirectory, manifest) =>
      for {
        source <- LocalPhotoStore.create(sourceDirectory)
        destination <- LocalPhotoStore.create(destinationDirectory)
        _ <- populate(source, sourceDirectory)
        _ <- PhotoMigration(source, destination).run(List(item), options(manifest))
        error <- interceptIO[IllegalArgumentException] {
          PhotoMigration(FailingReadPhotoStore(source), destination).run(
            List(item),
            options(
              manifest,
              resume = true,
              scope = localToS3Scope.copy(destinationIdentity = "s3:destination-b")
            )
          )
        }
      } yield assert(error.getMessage.contains("destination identity"))
    }
  }

  test("rejects a resume manifest when the source identity changes") {
    directories.use { case (sourceDirectory, destinationDirectory, manifest) =>
      for {
        source <- LocalPhotoStore.create(sourceDirectory)
        destination <- LocalPhotoStore.create(destinationDirectory)
        _ <- populate(source, sourceDirectory)
        _ <- PhotoMigration(source, destination).run(List(item), options(manifest))
        error <- interceptIO[IllegalArgumentException] {
          PhotoMigration(FailingReadPhotoStore(source), destination).run(
            List(item),
            options(
              manifest,
              resume = true,
              scope = localToS3Scope.copy(sourceIdentity = "local:/another-source")
            )
          )
        }
      } yield assert(error.getMessage.contains("source identity"))
    }
  }

  test("repairs a mismatched destination only when explicitly authorized") {
    directories.use { case (sourceDirectory, destinationDirectory, manifest) =>
      for {
        source <- LocalPhotoStore.create(sourceDirectory)
        destination <- LocalPhotoStore.create(destinationDirectory)
        _ <- populate(source, sourceDirectory)
        conflicting <- file(destinationDirectory, "conflicting.png", Array[Byte](9))
        _ <- destination.put(key, PhotoVariant.Display, PhotoExtension.Png, conflicting)
        withoutRepair <- PhotoMigration(source, destination).run(List(item), options(manifest))
        preserved <- destination
          .read(key, PhotoVariant.Display, PhotoExtension.Png)
          .compile
          .to(Array)
        withRepair <- PhotoMigration(source, destination).run(
          List(item),
          options(manifest, resume = true, repair = true)
        )
        repaired <- destination
          .read(key, PhotoVariant.Display, PhotoExtension.Png)
          .compile
          .to(Array)
      } yield {
        assertEquals(withoutRepair.copied, 2)
        assertEquals(withoutRepair.mismatched, 1)
        assertEquals(preserved.toList, List[Byte](9))
        assertEquals(withRepair.alreadyVerified, 2)
        assertEquals(withRepair.repaired, 1)
        assertEquals(withRepair.mismatched, 0)
        assertEquals(withRepair.failed, 0)
        assertEquals(repaired.toList, List[Byte](1))
      }
    }
  }

  test("categorizes storage failures without retaining exception messages") {
    directories.use { case (sourceDirectory, destinationDirectory, manifest) =>
      for {
        source <- LocalPhotoStore.create(sourceDirectory)
        destination <- LocalPhotoStore.create(destinationDirectory)
        _ <- populate(source, sourceDirectory)
        summary <- PhotoMigration(
          source,
          FailingReadPhotoStore(
            destination,
            AccessDeniedException("credential=super-secret-token")
          )
        ).run(
          List(item),
          options(manifest, dryRun = true)
        )
      } yield {
        assertEquals(summary.failed, 3)
        assertEquals(
          summary.diagnostics.map(_.variant).toSet,
          Set("original", "display", "thumbnail")
        )
        assert(summary.diagnostics.forall(_.operation == "read-destination"))
        assert(summary.diagnostics.forall(_.category == "permission-denied"))
        assert(!summary.toString.contains("super-secret-token"))
      }
    }
  }

  test("does not record a repair until the overwritten destination verifies") {
    directories.use { case (sourceDirectory, destinationDirectory, manifest) =>
      for {
        source <- LocalPhotoStore.create(sourceDirectory)
        destination <- LocalPhotoStore.create(destinationDirectory)
        _ <- populate(source, sourceDirectory)
        _ <- populate(destination, destinationDirectory)
        conflicting <- file(destinationDirectory, "conflicting.png", Array[Byte](9))
        _ <- destination.put(key, PhotoVariant.Display, PhotoExtension.Png, conflicting)
        summary <- PhotoMigration(source, IgnoringPutPhotoStore(destination)).run(
          List(item),
          options(manifest, repair = true)
        )
      } yield {
        assertEquals(summary.alreadyVerified, 2)
        assertEquals(summary.repaired, 0)
        assertEquals(summary.mismatched, 1)
        assertEquals(summary.diagnostics.map(_.operation), List("verify-destination"))
      }
    }
  }

  private def populate(store: PhotoStore, directory: Path): IO[Unit] =
    PhotoVariant.values.toList.zipWithIndex.traverse_ { case (variant, index) =>
      file(directory, s"${variant.filename}.png", Array(index.toByte)).flatMap(path =>
        store.put(key, variant, PhotoExtension.Png, path)
      )
    }

  private def options(
      manifest: Path,
      dryRun: Boolean = false,
      resume: Boolean = false,
      repair: Boolean = false,
      scope: PhotoMigrationScope = localToS3Scope
  ): PhotoMigrationOptions =
    PhotoMigrationOptions(dryRun, resume, repair, manifest, scope)

  private def directories: Resource[IO, (Path, Path, Path)] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("photo-migration-suite-")))(deleteTree)
      .map { root =>
        (root.resolve("source"), root.resolve("destination"), root.resolve("manifest.json"))
      }

  private def file(directory: Path, name: String, bytes: Array[Byte]): IO[Path] =
    IO.blocking {
      val _ = Files.createDirectories(directory)
      val path = directory.resolve(name)
      Files.write(path, bytes)
      path
    }

  private def deleteTree(directory: Path): IO[Unit] =
    IO.blocking {
      val paths = Files.walk(directory)
      try {
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = Files.deleteIfExists(path)
          })
      } finally {
        paths.close()
      }
    }

  private final class FailingReadPhotoStore(
      delegate: PhotoStore,
      error: Throwable = IllegalStateException("destination read")
  ) extends PhotoStore {
    override def put(
        storageKey: StorageKey,
        variant: PhotoVariant,
        extension: PhotoExtension,
        source: Path
    ): IO[Unit] = delegate.put(storageKey, variant, extension, source)

    override def read(
        storageKey: StorageKey,
        variant: PhotoVariant,
        extension: PhotoExtension
    ): fs2.Stream[IO, Byte] = fs2.Stream.raiseError[IO](error)

    override def delete(storageKey: StorageKey): IO[Unit] = delegate.delete(storageKey)
    override def listStorageKeys: IO[Set[StorageKey]] = delegate.listStorageKeys
    override def listStorageKeysOlderThan(cutoff: java.time.Instant): IO[Set[StorageKey]] =
      delegate.listStorageKeysOlderThan(cutoff)
    override def checkWritable: IO[Boolean] = delegate.checkWritable
  }

  private final class IgnoringPutPhotoStore(delegate: PhotoStore) extends PhotoStore {
    override def put(
        storageKey: StorageKey,
        variant: PhotoVariant,
        extension: PhotoExtension,
        source: Path
    ): IO[Unit] = IO.unit

    override def read(
        storageKey: StorageKey,
        variant: PhotoVariant,
        extension: PhotoExtension
    ): fs2.Stream[IO, Byte] = delegate.read(storageKey, variant, extension)

    override def delete(storageKey: StorageKey): IO[Unit] = delegate.delete(storageKey)
    override def listStorageKeys: IO[Set[StorageKey]] = delegate.listStorageKeys
    override def listStorageKeysOlderThan(cutoff: java.time.Instant): IO[Set[StorageKey]] =
      delegate.listStorageKeysOlderThan(cutoff)
    override def checkWritable: IO[Boolean] = delegate.checkWritable
  }
}
