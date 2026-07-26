package cookingblog.service

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import cookingblog.domain.*
import cookingblog.repository.*
import cookingblog.service.ApiError.*
import cookingblog.storage.*
import doobie.*
import doobie.implicits.*
import fs2.Stream

import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import scala.util.control.NonFatal

final case class PhotoMedia(
    photo: Photo,
    body: Stream[IO, Byte]
)

/** Coordinates safe image processing, filesystem storage, and photo metadata transactions.
  *
  * Object writes happen before metadata writes and are compensated when the database operation
  * cannot complete.
  */
final class PhotoService(
    transactor: Transactor[IO],
    photoStore: PhotoStore,
    imageProcessor: ImageProcessor,
    meals: MealRepository[ConnectionIO],
    photos: PhotoRepository[ConnectionIO],
    photoCleanup: PhotoCleanup[IO]
) {
  private val MaxCommentLength = 2000
  private val MaxFilenameLength = 255

  /** Validates the relationship and image stream before storing all immutable image variants. */
  def upload(
      recipeId: RecipeId,
      mealId: MealId,
      originalFilename: String,
      comment: Option[String],
      body: Stream[IO, Byte]
  ): IO[Either[ApiError, Photo]] =
    validateComment(comment) match {
      case Left(error)             => IO.pure(Left(error))
      case Right(validatedComment) =>
        validateMealRelationship(recipeId, mealId).flatMap {
          case Left(error) => IO.pure(Left(error))
          case Right(_)    =>
            imageProcessor
              .process(body)
              .use(processed =>
                saveProcessed(
                  recipeId,
                  mealId,
                  sanitizeFilename(originalFilename),
                  validatedComment,
                  processed
                )
              )
              .attempt
              .map {
                case Right(result)                                        => result
                case Left(ImageProcessingException.UploadTooLarge(limit)) =>
                  Left(PayloadTooLarge(s"Photo must be at most $limit bytes"))
                case Left(ImageProcessingException.EmptyUpload) =>
                  Left(Validation(Map("photo" -> List("must not be empty"))))
                case Left(ImageProcessingException.UnsupportedImage) =>
                  Left(
                    UnsupportedMedia("Photo must decode as JPEG, PNG, or WebP")
                  )
                case Left(ImageProcessingException.ImageTooLarge) =>
                  Left(UnsupportedMedia("Decoded photo dimensions are too large"))
                case Left(_) =>
                  Left(UnavailableDependency("Photo processing is unavailable"))
              }
        }
    }

  def updateComment(
      recipeId: RecipeId,
      mealId: MealId,
      photoId: PhotoId,
      input: UpdatePhotoInput
  ): IO[Either[ApiError, Photo]] =
    validateComment(input.comment) match {
      case Left(error)    => IO.pure(Left(error))
      case Right(comment) =>
        now.flatMap { timestamp =>
          val program =
            findRelated(recipeId, mealId, photoId).flatMap {
              case Left(error)     => error.asLeft[Photo].pure[ConnectionIO]
              case Right(existing) =>
                val updated =
                  existing.copy(comment = comment, updatedAt = timestamp)
                photos.update(updated).as(updated.asRight[ApiError])
            }
          program.transact(transactor)
        }
    }

  /** Removes metadata first, then cleans physical objects without turning a cleanup retry into data
    * loss.
    */
  def deletePhoto(
      recipeId: RecipeId,
      mealId: MealId,
      photoId: PhotoId
  ): IO[Either[ApiError, Unit]] = {
    val program =
      findRelated(recipeId, mealId, photoId).flatMap {
        case Left(error)  => error.asLeft[(String, Unit)].pure[ConnectionIO]
        case Right(photo) =>
          photos
            .delete(photo.id)
            .as((photo.storageKey, ()).asRight[ApiError])
      }
    program.transact(transactor).flatMap {
      case Left(error)            => IO.pure(Left(error))
      case Right((storageKey, _)) =>
        photoCleanup.deleteBestEffort(List(storageKey)).as(Right(()))
    }
  }

  def media(
      photoId: PhotoId,
      variant: PhotoVariant
  ): IO[Either[ApiError, PhotoMedia]] =
    photos.find(photoId).transact(transactor).map {
      _.toRight(NotFound("photo")).map(photo =>
        PhotoMedia(
          photo,
          photoStore.read(
            photo.storageKey,
            variant,
            extensionFor(photo.contentType)
          )
        )
      )
    }

  def recipePrimaryMedia(
      recipeId: RecipeId,
      variant: PhotoVariant
  ): IO[Either[ApiError, PhotoMedia]] =
    photos.findPrimaryForRecipe(recipeId).transact(transactor).map {
      _.toRight(NotFound("photo")).map(photo =>
        PhotoMedia(
          photo,
          photoStore.read(
            photo.storageKey,
            variant,
            extensionFor(photo.contentType)
          )
        )
      )
    }

  /** Deletes only aged store objects that have no database reference, avoiding active upload races.
    */
  def cleanupOrphans: IO[Int] =
    now.flatMap { timestamp =>
      (
        photoStore.listStorageKeysOlderThan(timestamp.minusSeconds(1.hour.toSeconds)),
        photos.listStorageKeys.transact(transactor).map(_.toSet)
      ).tupled.flatMap { case (storedKeys, referencedKeys) =>
        val orphaned = storedKeys.diff(referencedKeys)
        photoCleanup.deleteBestEffort(orphaned.toList).as(orphaned.size)
      }
    }

  def checkStoreWritable: IO[Boolean] = photoStore.checkWritable

  /** Compensates stored variants whenever photo metadata cannot be committed. */
  private def saveProcessed(
      recipeId: RecipeId,
      mealId: MealId,
      originalFilename: String,
      comment: Option[String],
      processed: ProcessedPhoto
  ): IO[Either[ApiError, Photo]] = {
    val storageKey = UUID.randomUUID().toString.replace("-", "")
    val writeObjects =
      PhotoVariant.values.toList.traverse_(variant =>
        photoStore.put(
          storageKey,
          variant,
          processed.extension,
          processed.files(variant)
        )
      )
    writeObjects.attempt.flatMap {
      case Left(_) =>
        photoCleanup
          .deleteBestEffort(List(storageKey))
          .as(
            Left(UnavailableDependency("Photo storage is unavailable"))
          )
      case Right(_) =>
        now.flatMap { timestamp =>
          val photo =
            Photo(
              PhotoId.random,
              mealId,
              storageKey,
              originalFilename,
              processed.contentType,
              processed.uploadedByteSize,
              Some(processed.width),
              Some(processed.height),
              comment,
              timestamp,
              timestamp
            )
          val program =
            meals.find(mealId).flatMap {
              case None => NotFound("meal").asLeft[Photo].pure[ConnectionIO]
              case Some(meal) if meal.recipeId != recipeId =>
                InvalidRelationship("meal does not belong to recipe")
                  .asLeft[Photo]
                  .pure[ConnectionIO]
              case Some(_) =>
                photos.create(photo).as(photo.asRight[ApiError])
            }
          program.transact(transactor).attempt.flatMap {
            case Right(Right(saved)) => IO.pure(Right(saved))
            case Right(Left(error))  =>
              photoCleanup.deleteBestEffort(List(storageKey)).as(Left(error))
            case Left(_) =>
              photoCleanup
                .deleteBestEffort(List(storageKey))
                .as(
                  Left(UnavailableDependency("Photo metadata could not be saved"))
                )
          }
        }
    }
  }

  private def validateMealRelationship(
      recipeId: RecipeId,
      mealId: MealId
  ): IO[Either[ApiError, Unit]] =
    meals.find(mealId).transact(transactor).map {
      case None       => Left(NotFound("meal"))
      case Some(meal) =>
        Either.cond(
          meal.recipeId == recipeId,
          (),
          InvalidRelationship("meal does not belong to recipe")
        )
    }

  private def findRelated(
      recipeId: RecipeId,
      mealId: MealId,
      photoId: PhotoId
  ): ConnectionIO[Either[ApiError, Photo]] =
    (meals.find(mealId), photos.find(photoId)).tupled.map {
      case (None, _)                                    => Left(NotFound("meal"))
      case (_, None)                                    => Left(NotFound("photo"))
      case (Some(meal), _) if meal.recipeId != recipeId =>
        Left(InvalidRelationship("meal does not belong to recipe"))
      case (_, Some(photo)) if photo.mealId != mealId =>
        Left(InvalidRelationship("photo does not belong to meal"))
      case (_, Some(photo)) => Right(photo)
    }

  private def validateComment(
      comment: Option[String]
  ): Either[ApiError, Option[String]] = {
    val normalized = comment.map(_.trim).filter(_.nonEmpty)
    Either.cond(
      normalized.forall(_.length <= MaxCommentLength),
      normalized,
      Validation(
        Map("comment" -> List(s"must be at most $MaxCommentLength characters"))
      )
    )
  }

  private def sanitizeFilename(raw: String): String = {
    val base =
      try {
        Option(Paths.get(raw).getFileName).fold("photo")(_.toString)
      } catch {
        case NonFatal(_) => "photo"
      }
    val sanitized =
      base.filter(character => !character.isControl).trim.take(MaxFilenameLength)
    if (sanitized.isEmpty) "photo" else sanitized
  }

  private def extensionFor(contentType: String): String =
    contentType match {
      case "image/jpeg" => "jpg"
      case "image/png"  => "png"
      case "image/webp" => "webp"
      case other        => throw IllegalStateException(s"Unsupported stored type: $other")
    }

  private def now: IO[Instant] = Clock[IO].realTimeInstant
}

object PhotoService {
  def apply(
      transactor: Transactor[IO],
      photoStore: PhotoStore,
      photoCleanup: PhotoCleanup[IO]
  ): PhotoService =
    new PhotoService(
      transactor,
      photoStore,
      ImageProcessor(),
      DoobieRepositories.meals,
      DoobieRepositories.photos,
      photoCleanup
    )
}
