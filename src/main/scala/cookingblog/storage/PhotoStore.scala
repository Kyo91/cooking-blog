package cookingblog.storage

import cats.effect.IO
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

/** Storage boundary for immutable photo variants addressed by opaque, non-guessable keys. */
trait PhotoStore {
  def put(
      storageKey: String,
      variant: PhotoVariant,
      extension: String,
      source: Path
  ): IO[Unit]
  def read(
      storageKey: String,
      variant: PhotoVariant,
      extension: String
  ): Stream[IO, Byte]
  def delete(storageKey: String): IO[Unit]
  def listStorageKeys: IO[Set[String]]
  def listStorageKeysOlderThan(cutoff: Instant): IO[Set[String]]
  def checkWritable: IO[Boolean]
}

final class LocalPhotoStore private (root: Path) extends PhotoStore {
  override def put(
      storageKey: String,
      variant: PhotoVariant,
      extension: String,
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
      storageKey: String,
      variant: PhotoVariant,
      extension: String
  ): Stream[IO, Byte] =
    Fs2Files[IO].readAll(Fs2Path.fromNioPath(fileFor(storageKey, variant, extension)))

  override def delete(storageKey: String): IO[Unit] =
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

  override def listStorageKeys: IO[Set[String]] =
    listDirectories(_ => true)

  override def listStorageKeysOlderThan(cutoff: Instant): IO[Set[String]] =
    listDirectories(path => Files.getLastModifiedTime(path).toInstant.isBefore(cutoff))

  private def listDirectories(include: Path => Boolean): IO[Set[String]] =
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
            .map(_.getFileName.toString)
            .filter(LocalPhotoStore.isValidStorageKey)
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

  private def directoryFor(storageKey: String): Path = {
    require(
      LocalPhotoStore.isValidStorageKey(storageKey),
      "Invalid photo storage key"
    )
    root.resolve(storageKey)
  }

  private def fileFor(
      storageKey: String,
      variant: PhotoVariant,
      extension: String
  ): Path = {
    require(
      LocalPhotoStore.ValidExtension.matches(extension),
      "Invalid photo extension"
    )
    directoryFor(storageKey).resolve(s"${variant.filename}.$extension")
  }
}

object LocalPhotoStore {
  private val ValidStorageKey = raw"[a-f0-9]{32}".r
  private val ValidExtension = raw"(jpg|png|webp)".r

  def create(root: Path): IO[LocalPhotoStore] =
    IO.blocking {
      val normalized = root.toAbsolutePath.normalize()
      val _ = Files.createDirectories(normalized)
      new LocalPhotoStore(normalized)
    }

  private[storage] def isValidStorageKey(value: String): Boolean =
    ValidStorageKey.matches(value)
}
