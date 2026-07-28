package cookingblog.storage

import cats.effect.{IO, Resource}
import ciris.Secret
import cookingblog.config.{S3CredentialsMode, S3PhotoConfig}
import munit.CatsEffectSuite

import java.net.URI
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.duration.*

final class PhotoStoreContractSuite extends CatsEffectSuite {
  test("local PhotoStore satisfies immutable variant contract") {
    temporaryDirectory.use(directory =>
      LocalPhotoStore.create(directory).flatMap(store => verifyContract(store, directory))
    )
  }

  test("S3-compatible PhotoStore satisfies immutable variant contract") {
    s3Configuration match {
      case None =>
        IO.println(
          "Skipping S3-compatible PhotoStore contract; set S3_TEST_ENDPOINT to enable it"
        )
      case Some(configuration) =>
        temporaryDirectory.use(directory =>
          S3PhotoStore.create(configuration).use(store => verifyContract(store, directory))
        )
    }
  }

  private def verifyContract(store: PhotoStore, directory: Path): IO[Unit] = {
    val storageKey = "0123456789abcdef0123456789abcdef"
    for {
      first <- writeFile(directory, "first.png", Array[Byte](1, 2, 3))
      second <- writeFile(directory, "second.png", Array[Byte](4, 5, 6))
      _ <- store.put(storageKey, PhotoVariant.Original, "png", first)
      _ <- store.put(storageKey, PhotoVariant.Display, "png", second)
      original <- store.read(storageKey, PhotoVariant.Original, "png").compile.to(Array)
      display <- store.read(storageKey, PhotoVariant.Display, "png").compile.to(Array)
      storageKeys <- store.listStorageKeys
      agedStorageKeys <- store.listStorageKeysOlderThan(Instant.now().plusSeconds(5))
      writable <- store.checkWritable
      _ <- store.put(storageKey, PhotoVariant.Original, "png", second)
      overwritten <- store.read(storageKey, PhotoVariant.Original, "png").compile.to(Array)
      _ <- store.delete(storageKey)
      afterDelete <- store.listStorageKeys
    } yield {
      assertEquals(original.toList, List[Byte](1, 2, 3))
      assertEquals(display.toList, List[Byte](4, 5, 6))
      assertEquals(storageKeys, Set(storageKey))
      assertEquals(agedStorageKeys, Set(storageKey))
      assert(writable)
      assertEquals(overwritten.toList, List[Byte](4, 5, 6))
      assertEquals(afterDelete, Set.empty[String])
    }
  }

  private def temporaryDirectory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("photo-store-contract-")))(directory =>
      IO.blocking {
        val walk = Files.walk(directory)
        try {
          walk
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(path => {
              val _ = Files.deleteIfExists(path)
            })
        } finally {
          walk.close()
        }
      }.handleError(_ => ())
    )

  private def writeFile(directory: Path, name: String, bytes: Array[Byte]): IO[Path] =
    IO.blocking {
      val target = directory.resolve(name)
      Files.write(target, bytes)
      target
    }

  private def s3Configuration: Option[S3PhotoConfig] =
    sys.env.get("S3_TEST_ENDPOINT").map { endpoint =>
      S3PhotoConfig(
        bucket = sys.env.getOrElse("S3_TEST_BUCKET", "cooking-blog-photos"),
        prefix = s"cooking-blog/photos/contract-${java.util.UUID.randomUUID()}",
        region = sys.env.getOrElse("S3_TEST_REGION", "us-east-1"),
        endpoint = Some(URI.create(endpoint)),
        pathStyleAccess = true,
        credentialsMode = S3CredentialsMode.Static,
        accessKeyId = sys.env.getOrElse("S3_TEST_ACCESS_KEY_ID", "cooking-blog"),
        secretAccessKey =
          Secret(sys.env.getOrElse("S3_TEST_SECRET_ACCESS_KEY", "cooking-blog-dev-secret")),
        maximumConcurrency = 2,
        connectionTimeout = 5.seconds,
        requestTimeout = 30.seconds
      )
    }
}
