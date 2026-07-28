package cookingblog.service

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.storage.PhotoStore
import cookingblog.storage.StorageKey
import org.typelevel.log4cats.Logger

/** Performs idempotent best-effort removal of physical photo objects after metadata deletion. */
trait PhotoCleanup[F[_]] {
  def deleteBestEffort(storageKeys: List[StorageKey]): F[Unit]
}

final class PhotoStoreCleanup(
    photoStore: PhotoStore
)(using logger: Logger[IO])
    extends PhotoCleanup[IO] {
  override def deleteBestEffort(storageKeys: List[StorageKey]): IO[Unit] =
    storageKeys.traverse_(storageKey =>
      photoStore
        .delete(storageKey)
        .handleErrorWith(exception =>
          logger.warn(exception)(
            s"Failed to delete photo storage key ${StorageKey.value(storageKey)}"
          )
        )
    )
}

object PhotoCleanup {
  def apply(photoStore: PhotoStore)(using Logger[IO]): PhotoCleanup[IO] =
    PhotoStoreCleanup(photoStore)
}
