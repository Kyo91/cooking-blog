package cookingblog.storage

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import cookingblog.config.S3PhotoConfig
import fs2.io.file.{Files as Fs2Files, Path as Fs2Path}
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.S3Exception

import java.io.IOException
import java.net.{ConnectException, SocketTimeoutException}
import java.nio.charset.StandardCharsets
import java.nio.file.{AccessDeniedException, Files, NoSuchFileException, Path, StandardCopyOption}
import java.security.MessageDigest
import java.util.concurrent.TimeoutException

final case class PhotoMigrationItem(storageKey: StorageKey, extension: PhotoExtension)

enum PhotoMigrationDirection(val value: String) {
  case LocalToS3 extends PhotoMigrationDirection("local-to-s3")
  case S3ToLocal extends PhotoMigrationDirection("s3-to-local")
}

final case class PhotoMigrationScope(
    direction: PhotoMigrationDirection,
    sourceIdentity: String,
    destinationIdentity: String
)

object PhotoMigrationScope {
  def localToS3(localDirectory: Path, s3: S3PhotoConfig): PhotoMigrationScope =
    PhotoMigrationScope(
      PhotoMigrationDirection.LocalToS3,
      localIdentity(localDirectory),
      s3Identity(s3)
    )

  def s3ToLocal(s3: S3PhotoConfig, localDirectory: Path): PhotoMigrationScope =
    PhotoMigrationScope(
      PhotoMigrationDirection.S3ToLocal,
      s3Identity(s3),
      localIdentity(localDirectory)
    )

  private def localIdentity(directory: Path): String =
    s"local:${directory.toAbsolutePath.normalize()}"

  private def s3Identity(config: S3PhotoConfig): String = {
    val endpoint = config.endpoint
      .map(_.normalize().toASCIIString.stripSuffix("/"))
      .getOrElse(s"aws://${config.region}")
    s"s3:endpoint=$endpoint;region=${config.region};bucket=${config.bucket};prefix=${config.prefix}"
  }
}

final case class PhotoMigrationOptions(
    dryRun: Boolean,
    resume: Boolean,
    repair: Boolean,
    manifest: Path,
    scope: PhotoMigrationScope
)

final case class PhotoMigrationDiagnostic(
    storageKey: String,
    variant: String,
    operation: String,
    category: String
)

object PhotoMigrationDiagnostic {
  given Encoder[PhotoMigrationDiagnostic] = deriveEncoder
}

final case class PhotoMigrationSummary(
    copied: Int = 0,
    repaired: Int = 0,
    alreadyVerified: Int = 0,
    missingSource: Int = 0,
    missingDestination: Int = 0,
    mismatched: Int = 0,
    failed: Int = 0,
    destinationOrphan: Int = 0,
    diagnostics: List[PhotoMigrationDiagnostic] = Nil
) {
  def hasFailures: Boolean =
    missingSource > 0 || missingDestination > 0 || mismatched > 0 || failed > 0
}

object PhotoMigrationSummary {
  given Encoder[PhotoMigrationSummary] = deriveEncoder
}

private final case class VerifiedVariant(byteSize: Long, sha256: String)
private final case class MigrationManifest(
    schemaVersion: Int,
    direction: String,
    sourceIdentity: String,
    destinationIdentity: String,
    verified: Map[String, VerifiedVariant]
)

private object MigrationManifest {
  val SchemaVersion = 1

  given Encoder[VerifiedVariant] = deriveEncoder
  given Decoder[VerifiedVariant] = deriveDecoder
  given Encoder[MigrationManifest] = deriveEncoder
  given Decoder[MigrationManifest] = deriveDecoder

  def empty(scope: PhotoMigrationScope): MigrationManifest =
    MigrationManifest(
      SchemaVersion,
      scope.direction.value,
      scope.sourceIdentity,
      scope.destinationIdentity,
      Map.empty
    )
}

/** Copies immutable variants between stores while retaining an external, resumable verification
  * log.
  */
final class PhotoMigration(source: PhotoStore, destination: PhotoStore) {
  import MigrationManifest.*

  def run(
      items: List[PhotoMigrationItem],
      options: PhotoMigrationOptions
  ): IO[PhotoMigrationSummary] =
    loadManifest(options).flatMap { manifest =>
      items
        .flatMap(item => PhotoVariant.values.toList.map(variant => (item, variant)))
        .foldLeft(IO.pure((manifest, PhotoMigrationSummary()))) { case (result, (item, variant)) =>
          result.flatMap { case (currentManifest, summary) =>
            migrateVariant(item, variant, currentManifest, options).flatMap {
              case (updatedManifest, updatedSummary) =>
                persistManifest(updatedManifest, options) *>
                  IO.pure((updatedManifest, add(summary, updatedSummary)))
            }
          }
        }
        .map(_._2)
        .flatMap(summary =>
          destinationOrphans(items)
            .map(orphanCount => summary.copy(destinationOrphan = orphanCount))
        )
    }

  private def migrateVariant(
      item: PhotoMigrationItem,
      variant: PhotoVariant,
      manifest: MigrationManifest,
      options: PhotoMigrationOptions
  ): IO[(MigrationManifest, PhotoMigrationSummary)] = {
    val key = manifestKey(item, variant)
    digest(source.read(item.storageKey, variant, item.extension)).attempt.flatMap {
      case Left(error) if isMissing(error) =>
        IO.pure(
          (
            manifest,
            issue(item, variant, "read-source", errorCategory(error), missingSource = 1)
          )
        )
      case Left(error) =>
        IO.pure(
          (manifest, issue(item, variant, "read-source", errorCategory(error), failed = 1))
        )
      case Right(expected) =>
        if (options.resume && manifest.verified.get(key).contains(expected)) {
          IO.pure((manifest, PhotoMigrationSummary(alreadyVerified = 1)))
        } else {
          inspectDestination(item, variant, expected, manifest, key, options)
        }
    }
  }

  private def inspectDestination(
      item: PhotoMigrationItem,
      variant: PhotoVariant,
      expected: VerifiedVariant,
      manifest: MigrationManifest,
      key: String,
      options: PhotoMigrationOptions
  ): IO[(MigrationManifest, PhotoMigrationSummary)] =
    digest(destination.read(item.storageKey, variant, item.extension)).attempt.flatMap {
      case Right(actual) if actual == expected =>
        IO.pure(
          (
            verify(manifest, key, expected),
            PhotoMigrationSummary(alreadyVerified = 1)
          )
        )
      case Right(_) if options.repair && !options.dryRun =>
        copyAndVerify(item, variant, expected, unverify(manifest, key), key, repairing = true)
      case Right(_) =>
        IO.pure(
          (
            unverify(manifest, key),
            issue(item, variant, "verify-destination", "checksum-mismatch", mismatched = 1)
          )
        )
      case Left(error) if options.dryRun && isMissing(error) =>
        IO.pure(
          (
            unverify(manifest, key),
            issue(
              item,
              variant,
              "read-destination",
              errorCategory(error),
              missingDestination = 1
            )
          )
        )
      case Left(error) if isMissing(error) =>
        copyAndVerify(item, variant, expected, unverify(manifest, key), key, repairing = false)
      case Left(error) =>
        IO.pure(
          (
            unverify(manifest, key),
            issue(item, variant, "read-destination", errorCategory(error), failed = 1)
          )
        )
    }

  private def copyAndVerify(
      item: PhotoMigrationItem,
      variant: PhotoVariant,
      expected: VerifiedVariant,
      manifest: MigrationManifest,
      key: String,
      repairing: Boolean
  ): IO[(MigrationManifest, PhotoMigrationSummary)] =
    temporaryFile
      .use { localCopy =>
        source
          .read(item.storageKey, variant, item.extension)
          .through(Fs2Files[IO].writeAll(Fs2Path.fromNioPath(localCopy)))
          .compile
          .drain
          .attempt
          .flatMap {
            case Left(error) if isMissing(error) =>
              IO.pure(
                (
                  manifest,
                  issue(item, variant, "read-source", errorCategory(error), missingSource = 1)
                )
              )
            case Left(error) =>
              IO.pure(
                (manifest, issue(item, variant, "read-source", errorCategory(error), failed = 1))
              )
            case Right(_) =>
              destination
                .put(item.storageKey, variant, item.extension, localCopy)
                .attempt
                .flatMap {
                  case Left(error) =>
                    IO.pure(
                      (
                        manifest,
                        issue(
                          item,
                          variant,
                          "write-destination",
                          errorCategory(error),
                          failed = 1
                        )
                      )
                    )
                  case Right(_) =>
                    verifyDestination(item, variant, expected, manifest, key, repairing)
                }
          }
      }
      .handleError(error =>
        (
          manifest,
          issue(item, variant, "stage-source", errorCategory(error), failed = 1)
        )
      )

  private def verifyDestination(
      item: PhotoMigrationItem,
      variant: PhotoVariant,
      expected: VerifiedVariant,
      manifest: MigrationManifest,
      key: String,
      repairing: Boolean
  ): IO[(MigrationManifest, PhotoMigrationSummary)] =
    digest(destination.read(item.storageKey, variant, item.extension)).attempt.map {
      case Right(actual) if actual == expected =>
        val summary =
          if (repairing) PhotoMigrationSummary(repaired = 1)
          else PhotoMigrationSummary(copied = 1)
        (verify(manifest, key, expected), summary)
      case Right(_) =>
        (
          manifest,
          issue(item, variant, "verify-destination", "checksum-mismatch", mismatched = 1)
        )
      case Left(error) if isMissing(error) =>
        (
          manifest,
          issue(
            item,
            variant,
            "verify-destination",
            errorCategory(error),
            missingDestination = 1
          )
        )
      case Left(error) =>
        (
          manifest,
          issue(item, variant, "verify-destination", errorCategory(error), failed = 1)
        )
    }

  private def temporaryFile: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempFile("photo-migration-", ".variant")))(path =>
      IO.blocking(Files.deleteIfExists(path)).void.handleError(_ => ())
    )

  private def digest(bytes: fs2.Stream[IO, Byte]): IO[VerifiedVariant] =
    IO.blocking(MessageDigest.getInstance("SHA-256")).flatMap { digest =>
      bytes.chunks
        .evalTap(chunk => IO.blocking(digest.update(chunk.toArray)))
        .compile
        .fold(0L)(_ + _.size.toLong)
        .map { size =>
          VerifiedVariant(size, digest.digest().map("%02x".format(_)).mkString)
        }
    }

  private def loadManifest(options: PhotoMigrationOptions): IO[MigrationManifest] =
    if (!options.resume) IO.pure(empty(options.scope))
    else {
      IO.blocking(
        Option.when(Files.exists(options.manifest))(
          Files.readString(options.manifest, StandardCharsets.UTF_8)
        )
      ).flatMap {
        case None        => IO.pure(empty(options.scope))
        case Some(value) =>
          IO.fromEither(
            decode[MigrationManifest](value).leftMap(_ =>
              IllegalArgumentException(
                "Photo migration manifest is incompatible or corrupt; expected schema version 1"
              )
            )
          ).flatMap(manifest => validateManifest(manifest, options.scope))
      }
    }

  private def validateManifest(
      manifest: MigrationManifest,
      scope: PhotoMigrationScope
  ): IO[MigrationManifest] = {
    val incompatibilities = List(
      Option.when(manifest.schemaVersion != SchemaVersion)(
        s"schema version ${manifest.schemaVersion} (expected $SchemaVersion)"
      ),
      Option.when(manifest.direction != scope.direction.value)("migration direction"),
      Option.when(manifest.sourceIdentity != scope.sourceIdentity)("source identity"),
      Option.when(manifest.destinationIdentity != scope.destinationIdentity)(
        "destination identity"
      )
    ).flatten
    if (incompatibilities.isEmpty) IO.pure(manifest)
    else {
      IO.raiseError(
        IllegalArgumentException(
          s"Photo migration manifest is incompatible: ${incompatibilities.mkString(", ")}. " +
            "Use a different PHOTO_MIGRATION_MANIFEST or start a new run without --resume."
        )
      )
    }
  }

  private def persistManifest(
      manifest: MigrationManifest,
      options: PhotoMigrationOptions
  ): IO[Unit] =
    if (options.dryRun) IO.unit
    else {
      IO.blocking {
        Option(options.manifest.getParent).foreach(parent => Files.createDirectories(parent))
        val parent = Option(options.manifest.toAbsolutePath.getParent).getOrElse(Path.of("."))
        val _ = Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".photo-migration-", ".tmp")
        try {
          Files.writeString(temporary, manifest.asJson.spaces2, StandardCharsets.UTF_8)
          try {
            val _ = Files.move(
              temporary,
              options.manifest,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE
            )
          } catch {
            case _: java.nio.file.AtomicMoveNotSupportedException =>
              val _ = Files.move(temporary, options.manifest, StandardCopyOption.REPLACE_EXISTING)
          }
        } finally {
          val _ = Files.deleteIfExists(temporary)
        }
      }
    }

  private def destinationOrphans(items: List[PhotoMigrationItem]): IO[Int] =
    destination.listStorageKeys.map(_.diff(items.map(_.storageKey).toSet).size)

  private def manifestKey(item: PhotoMigrationItem, variant: PhotoVariant): String =
    s"${StorageKey.value(item.storageKey)}/${variant.filename}.${item.extension.value}"

  private def verify(
      manifest: MigrationManifest,
      key: String,
      expected: VerifiedVariant
  ): MigrationManifest =
    manifest.copy(verified = manifest.verified.updated(key, expected))

  private def unverify(manifest: MigrationManifest, key: String): MigrationManifest =
    manifest.copy(verified = manifest.verified - key)

  private def issue(
      item: PhotoMigrationItem,
      variant: PhotoVariant,
      operation: String,
      category: String,
      missingSource: Int = 0,
      missingDestination: Int = 0,
      mismatched: Int = 0,
      failed: Int = 0
  ): PhotoMigrationSummary =
    PhotoMigrationSummary(
      missingSource = missingSource,
      missingDestination = missingDestination,
      mismatched = mismatched,
      failed = failed,
      diagnostics = List(
        PhotoMigrationDiagnostic(
          StorageKey.value(item.storageKey),
          variant.filename,
          operation,
          category
        )
      )
    )

  private def isMissing(error: Throwable): Boolean = errorChain(error).exists {
    case _: NoSuchFileException => true
    case exception: S3Exception => exception.statusCode() == 404
    case _                      => false
  }

  private def errorCategory(error: Throwable): String = {
    val errors = errorChain(error)
    errors.collectFirst { case exception: S3Exception => exception.statusCode() } match {
      case Some(401 | 403)                                              => "permission-denied"
      case Some(404)                                                    => "not-found"
      case Some(408 | 504)                                              => "timeout"
      case Some(429)                                                    => "throttled"
      case Some(status) if status >= 500                                => "storage-unavailable"
      case Some(_)                                                      => "storage-error"
      case None if errors.exists(_.isInstanceOf[NoSuchFileException])   => "not-found"
      case None if errors.exists(_.isInstanceOf[AccessDeniedException]) =>
        "permission-denied"
      case None
          if errors.exists(error =>
            error.isInstanceOf[TimeoutException] ||
              error.isInstanceOf[SocketTimeoutException]
          ) =>
        "timeout"
      case None if errors.exists(_.isInstanceOf[ConnectException]) =>
        "storage-unavailable"
      case None if errors.exists(_.isInstanceOf[SdkClientException]) =>
        "storage-unavailable"
      case None if errors.exists(_.isInstanceOf[IOException]) => "io-error"
      case None                                               => "unexpected"
    }
  }

  private def errorChain(error: Throwable): List[Throwable] = {
    def loop(current: Throwable, remaining: Int, collected: List[Throwable]): List[Throwable] =
      if (remaining == 0 || collected.exists(_ eq current)) collected.reverse
      else {
        Option(current.getCause) match {
          case Some(cause) => loop(cause, remaining - 1, current :: collected)
          case None        => (current :: collected).reverse
        }
      }
    loop(error, 16, Nil)
  }

  private def add(
      left: PhotoMigrationSummary,
      right: PhotoMigrationSummary
  ): PhotoMigrationSummary =
    PhotoMigrationSummary(
      left.copied + right.copied,
      left.repaired + right.repaired,
      left.alreadyVerified + right.alreadyVerified,
      left.missingSource + right.missingSource,
      left.missingDestination + right.missingDestination,
      left.mismatched + right.mismatched,
      left.failed + right.failed,
      left.destinationOrphan + right.destinationOrphan,
      left.diagnostics ++ right.diagnostics
    )
}
