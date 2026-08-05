package cookingblog

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import cookingblog.config.{AppConfig, S3PhotoConfig}
import cookingblog.database.Database
import cookingblog.repository.DoobieRepositories
import cookingblog.storage.*
import doobie.implicits.*
import io.circe.syntax.*

import java.nio.file.Path

/** Operator-only photo copy command. It never changes database rows or deletes bytes. */
object PhotoMigrationCommand extends IOApp {
  override def run(arguments: List[String]): IO[ExitCode] =
    parseArguments(arguments).fold(error => IO.println(error).as(ExitCode.Error), execute)

  private def execute(options: CommandOptions): IO[ExitCode] =
    loadConfig
      .flatMap { config =>
        config.photos match {
          case s3: S3PhotoConfig =>
            localDirectory(options.direction).flatMap { localDirectory =>
              validatePaths(localDirectory, manifestPath).flatMap {
                case (normalizedDirectory, normalizedManifest) =>
                  resources(config, normalizedDirectory, s3, options.direction).use {
                    case (transactor, source, target) =>
                      DoobieRepositories.photos.listAll.transact(transactor).flatMap { photos =>
                        photos
                          .traverse { photo =>
                            IO.fromOption(PhotoExtension.fromContentType(photo.contentType))(
                              IllegalArgumentException(
                                s"Unsupported content type for photo ${photo.id}"
                              )
                            ).map(extension => PhotoMigrationItem(photo.storageKey, extension))
                          }
                          .flatMap { items =>
                            val scope = migrationScope(options.direction, normalizedDirectory, s3)
                            PhotoMigration(source, target)
                              .run(
                                items,
                                PhotoMigrationOptions(
                                  options.dryRun,
                                  options.resume,
                                  options.repair,
                                  normalizedManifest,
                                  scope
                                )
                              )
                              .flatTap(summary => render(summary, options.json))
                              .map(summary =>
                                if (summary.hasFailures) ExitCode.Error else ExitCode.Success
                              )
                          }
                      }
                  }
              }
            }
          case _ =>
            IO.println("PHOTO_BACKEND must be s3 when running photo migration").as(ExitCode.Error)
        }
      }
      .handleErrorWith(error => IO.println(error.getMessage).as(ExitCode.Error))

  private def resources(
      config: AppConfig,
      localDirectory: Path,
      s3: S3PhotoConfig,
      direction: PhotoMigrationDirection
  ): Resource[IO, (doobie.Transactor[IO], PhotoStore, PhotoStore)] =
    for {
      transactor <- Database.transactor(config.database)
      local <- Resource.eval(LocalPhotoStore.create(localDirectory))
      s3Store <- S3PhotoStore.create(s3)
      (source, target) =
        direction match {
          case PhotoMigrationDirection.LocalToS3 => (local: PhotoStore, s3Store: PhotoStore)
          case PhotoMigrationDirection.S3ToLocal => (s3Store: PhotoStore, local: PhotoStore)
        }
    } yield (transactor, source, target)

  private def migrationScope(
      direction: PhotoMigrationDirection,
      localDirectory: Path,
      s3: S3PhotoConfig
  ): PhotoMigrationScope =
    direction match {
      case PhotoMigrationDirection.LocalToS3 =>
        PhotoMigrationScope.localToS3(localDirectory, s3)
      case PhotoMigrationDirection.S3ToLocal =>
        PhotoMigrationScope.s3ToLocal(s3, localDirectory)
    }

  private def loadConfig: IO[AppConfig] =
    AppConfig.load.flatMap(
      _.fold(
        errors =>
          IO.raiseError(IllegalArgumentException(errors.toNonEmptyList.toList.mkString("; "))),
        IO.pure
      )
    )

  private def localDirectory(direction: PhotoMigrationDirection): IO[Path] = {
    val variable =
      direction match {
        case PhotoMigrationDirection.LocalToS3 => "PHOTO_MIGRATION_SOURCE_DIRECTORY"
        case PhotoMigrationDirection.S3ToLocal => "PHOTO_MIGRATION_TARGET_DIRECTORY"
      }
    IO.fromOption(sys.env.get(variable).map(Path.of(_)))(
      IllegalArgumentException(s"$variable must identify the local photo directory")
    )
  }

  private def manifestPath: Path =
    Path.of(sys.env.getOrElse("PHOTO_MIGRATION_MANIFEST", "./data/photo-migration-manifest.json"))

  private def validatePaths(localDirectory: Path, manifest: Path): IO[(Path, Path)] =
    IO {
      val normalizedDirectory = localDirectory.toAbsolutePath.normalize()
      val normalizedManifest = manifest.toAbsolutePath.normalize()
      if (normalizedManifest.startsWith(normalizedDirectory)) {
        throw IllegalArgumentException(
          "PHOTO_MIGRATION_MANIFEST must be outside the local photo directory"
        )
      }
      (normalizedDirectory, normalizedManifest)
    }

  private def render(summary: PhotoMigrationSummary, json: Boolean): IO[Unit] =
    IO.println(renderOutput(summary, json))

  private[cookingblog] def renderOutput(
      summary: PhotoMigrationSummary,
      json: Boolean
  ): String =
    if (json) summary.asJson.noSpaces
    else {
      val aggregate =
        s"copied=${summary.copied} repaired=${summary.repaired} " +
          s"already-verified=${summary.alreadyVerified} " +
          s"missing-source=${summary.missingSource} " +
          s"missing-destination=${summary.missingDestination} " +
          s"mismatched=${summary.mismatched} failed=${summary.failed} " +
          s"destination-orphan=${summary.destinationOrphan}"
      val diagnostics = summary.diagnostics.map { diagnostic =>
        s"diagnostic storage-key=${diagnostic.storageKey} variant=${diagnostic.variant} " +
          s"operation=${diagnostic.operation} category=${diagnostic.category}"
      }
      (aggregate :: diagnostics).mkString("\n")
    }

  private[cookingblog] final case class CommandOptions(
      dryRun: Boolean,
      resume: Boolean,
      repair: Boolean,
      json: Boolean,
      direction: PhotoMigrationDirection
  )

  private[cookingblog] def parseArguments(
      arguments: List[String]
  ): Either[String, CommandOptions] = {
    def parse(remaining: List[String], options: CommandOptions): Either[String, CommandOptions] =
      remaining match {
        case Nil                            => Right(options)
        case "--dry-run" :: tail            => parse(tail, options.copy(dryRun = true))
        case "--resume" :: tail             => parse(tail, options.copy(resume = true))
        case "--repair" :: tail             => parse(tail, options.copy(repair = true))
        case "--json" :: tail               => parse(tail, options.copy(json = true))
        case "--direction" :: value :: tail =>
          parseDirection(value).flatMap(direction =>
            parse(tail, options.copy(direction = direction))
          )
        case option :: tail if option.startsWith("--direction=") =>
          parseDirection(option.stripPrefix("--direction=")).flatMap(direction =>
            parse(tail, options.copy(direction = direction))
          )
        case "--direction" :: Nil => Left("--direction requires local-to-s3 or s3-to-local")
        case "--help" :: _        =>
          Left(
            "Usage: PhotoMigrationCommand [--direction local-to-s3|s3-to-local] " +
              "[--dry-run] [--resume] [--repair] [--json]"
          )
        case value :: _ => Left(s"Unknown photo migration option: $value")
      }

    parse(
      arguments,
      CommandOptions(false, false, false, false, PhotoMigrationDirection.LocalToS3)
    )
  }

  private def parseDirection(value: String): Either[String, PhotoMigrationDirection] =
    value match {
      case "local-to-s3" => Right(PhotoMigrationDirection.LocalToS3)
      case "s3-to-local" => Right(PhotoMigrationDirection.S3ToLocal)
      case other         =>
        Left(s"Unknown photo migration direction: $other (expected local-to-s3 or s3-to-local)")
    }
}
