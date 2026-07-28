package cookingblog.storage

import cats.effect.{IO, Resource}
import cookingblog.config.{LocalPhotoConfig, PhotoConfig, S3PhotoConfig}
import fs2.Stream
import fs2.io.file.{Files as Fs2Files, Path as Fs2Path}

import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.time.Instant
import scala.jdk.CollectionConverters.*

enum PhotoVariant(val filename: String) {
  case Original extends PhotoVariant("original")
  case Display extends PhotoVariant("display")
  case Thumbnail extends PhotoVariant("thumbnail")
}

opaque type StorageKey = String
object StorageKey {
  private val Pattern = raw"[a-f0-9]{32}".r

  def random: StorageKey = java.util.UUID.randomUUID().toString.replace("-", "")
  def parse(value: String): Either[String, StorageKey] =
    Either.cond(
      Pattern.matches(value),
      value,
      s"Invalid photo storage key '$value': expected 32 lower-case hexadecimal characters"
    )
  def value(key: StorageKey): String = key
}

enum PhotoExtension(val value: String, val contentType: String) {
  case Jpeg extends PhotoExtension("jpg", "image/jpeg")
  case Png extends PhotoExtension("png", "image/png")
  case Webp extends PhotoExtension("webp", "image/webp")
}

object PhotoExtension {
  def fromContentType(contentType: String): Option[PhotoExtension] =
    PhotoExtension.values.find(_.contentType == contentType)
}

/** Storage boundary for immutable photo variants addressed by opaque, non-guessable keys. */
trait PhotoStore {
  def put(
      storageKey: StorageKey,
      variant: PhotoVariant,
      extension: PhotoExtension,
      source: Path
  ): IO[Unit]
  def read(
      storageKey: StorageKey,
      variant: PhotoVariant,
      extension: PhotoExtension
  ): Stream[IO, Byte]
  def delete(storageKey: StorageKey): IO[Unit]
  def listStorageKeys: IO[Set[StorageKey]]
  def listStorageKeysOlderThan(cutoff: Instant): IO[Set[StorageKey]]
  def checkWritable: IO[Boolean]
}

object PhotoStore {
  def create(config: PhotoConfig): Resource[IO, PhotoStore] =
    config match {
      case LocalPhotoConfig(directory) =>
        Resource.eval(LocalPhotoStore.create(directory))
      case s3: S3PhotoConfig => S3PhotoStore.create(s3)
    }
}

final class LocalPhotoStore private (root: Path) extends PhotoStore {
  override def put(
      storageKey: StorageKey,
      variant: PhotoVariant,
      extension: PhotoExtension,
      source: Path
  ): IO[Unit] =
    IO.blocking {
      val directory = directoryFor(storageKey)
      val _ = Files.createDirectories(directory)
      val target = fileFor(storageKey, variant, extension)
      val temporary =
        Files.createTempFile(directory, s".${variant.filename}-", ".tmp")
      try {
        val _ =
          Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
        try {
          val _ =
            Files.move(
              temporary,
              target,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING
            )
        } catch {
          case _: AtomicMoveNotSupportedException =>
            val _ =
              Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING
              )
        }
      } finally {
        val _ = Files.deleteIfExists(temporary)
      }
    }

  override def read(
      storageKey: StorageKey,
      variant: PhotoVariant,
      extension: PhotoExtension
  ): Stream[IO, Byte] =
    Fs2Files[IO].readAll(Fs2Path.fromNioPath(fileFor(storageKey, variant, extension)))

  override def delete(storageKey: StorageKey): IO[Unit] =
    IO.blocking {
      val directory = directoryFor(storageKey)
      if (Files.exists(directory)) {
        val stream = Files.walk(directory)
        try {
          val paths =
            stream.iterator().asScala.toList.sortBy(_.getNameCount).reverse
          paths.foreach(path => {
            val _ = Files.deleteIfExists(path)
          })
        } finally {
          stream.close()
        }
      }
    }

  override def listStorageKeys: IO[Set[StorageKey]] =
    listDirectories(_ => true)

  override def listStorageKeysOlderThan(cutoff: Instant): IO[Set[StorageKey]] =
    listDirectories(path => Files.getLastModifiedTime(path).toInstant.isBefore(cutoff))

  private def listDirectories(include: Path => Boolean): IO[Set[StorageKey]] =
    IO.blocking {
      if (!Files.exists(root)) {
        Set.empty
      } else {
        val stream = Files.list(root)
        try {
          stream
            .iterator()
            .asScala
            .filter(Files.isDirectory(_))
            .filter(include)
            .flatMap(path => StorageKey.parse(path.getFileName.toString).toOption)
            .toSet
        } finally {
          stream.close()
        }
      }
    }

  override def checkWritable: IO[Boolean] =
    IO.blocking {
      val _ = Files.createDirectories(root)
      val probe = Files.createTempFile(root, ".write-probe-", ".tmp")
      Files.deleteIfExists(probe)
    }.attempt
      .map(_.isRight)

  private def directoryFor(storageKey: StorageKey): Path =
    root.resolve(StorageKey.value(storageKey))

  private def fileFor(
      storageKey: StorageKey,
      variant: PhotoVariant,
      extension: PhotoExtension
  ): Path = directoryFor(storageKey).resolve(s"${variant.filename}.${extension.value}")
}

object LocalPhotoStore {
  def create(root: Path): IO[LocalPhotoStore] =
    IO.blocking {
      val normalized = root.toAbsolutePath.normalize()
      val _ = Files.createDirectories(normalized)
      new LocalPhotoStore(normalized)
    }
}
