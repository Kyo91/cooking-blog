package cookingblog.service

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import cookingblog.domain.*
import cookingblog.repository.*
import cookingblog.service.ApiError.*
import doobie.*
import doobie.implicits.*
import org.postgresql.util.PSQLException

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.{Base64, Locale}

final class RecipeApiService(
    transactor: Transactor[IO],
    recipes: RecipeRepository[ConnectionIO],
    meals: MealRepository[ConnectionIO],
    photos: PhotoRepository[ConnectionIO],
    references: RecipeReferenceRepository[ConnectionIO],
    scrapedDocuments: ScrapedDocumentRepository[ConnectionIO],
    scrapeJobs: ScrapeJobRepository[ConnectionIO],
    searchDocuments: RecipeSearchDocumentRepository[ConnectionIO],
    photoCleanup: PhotoCleanup[IO]
) {
  private val MaxTitleLength = 200
  private val MaxDescriptionLength = 20000
  private val MaxNotesLength = 20000
  private val MaxReferenceValueLength = 4000
  private val MaxDisplayNameLength = 500

  def createRecipe(input: CreateRecipeInput): IO[Either[ApiError, Recipe]] =
    continue(validateRecipe(input.title, input.description.getOrElse(""))) {
      case (title, description) =>
        now.flatMap { timestamp =>
          val recipe =
            Recipe(
              RecipeId.random,
              title,
              description,
              None,
              timestamp,
              timestamp,
              None
            )
          val search =
            RecipeSearchDocument(recipe.id, recipeText(recipe), "", timestamp)
          transact(recipes.create(recipe) *> searchDocuments.create(search))
            .as(recipe)
            .attempt
            .map(_.leftMap(databaseError))
        }
    }

  def getRecipe(id: RecipeId): IO[Either[ApiError, Recipe]] =
    transact(recipes.find(id))
      .map(_.toRight(NotFound("recipe")))

  def listRecipes(
      query: Option[String],
      sort: RecipeSort,
      limit: Int,
      cursor: Option[String]
  ): IO[Either[ApiError, RecipePage]] = {
    val validation: Either[ApiError, Option[String]] =
      if (limit < 1 || limit > 100) {
        Left(Validation(Map("limit" -> List("must be between 1 and 100"))))
      } else if (query.exists(_.length > 500)) {
        Left(Validation(Map("q" -> List("must be at most 500 characters"))))
      } else {
        cursor.traverse(decodeCursor).map(_.flatten)
      }

    validation match {
      case Left(error)          => IO.pure(Left(error))
      case Right(decodedCursor) =>
        transact(recipes.list).map { storedRecipes =>
          val normalizedQuery =
            query.map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty)
          val filtered = normalizedQuery.fold(storedRecipes)(needle =>
            storedRecipes.filter(recipe =>
              recipe.title.toLowerCase(Locale.ROOT).contains(needle) ||
                recipe.description.toLowerCase(Locale.ROOT).contains(needle)
            )
          )
          val sorted = sortRecipes(filtered, sort)
          val remaining =
            decodedCursor match {
              case None        => Right(sorted)
              case Some(value) =>
                val index =
                  sorted.indexWhere(recipe => cursorValue(recipe, sort) == value)
                Either.cond(
                  index >= 0,
                  sorted.drop(index + 1),
                  Validation(
                    Map(
                      "cursor" -> List(
                        "does not belong to this query and sort order"
                      )
                    )
                  )
                )
            }
          remaining.map { values =>
            val pageItems = values.take(limit)
            val next =
              Option.when(values.size > limit)(
                encodeCursor(cursorValue(pageItems.last, sort))
              )
            RecipePage(pageItems, next)
          }
        }
    }
  }

  def updateRecipe(
      id: RecipeId,
      input: UpdateRecipeInput
  ): IO[Either[ApiError, Recipe]] =
    if (input.title.isEmpty && input.description.isEmpty) {
      IO.pure(
        Left(Validation(Map("body" -> List("must contain a field to update"))))
      )
    } else {
      transact(recipes.find(id)).flatMap {
        case None           => IO.pure(Left(NotFound("recipe")))
        case Some(existing) =>
          continue(
            validateRecipe(
              input.title.getOrElse(existing.title),
              input.description.getOrElse(existing.description)
            )
          ) { case (title, description) =>
            now.flatMap { timestamp =>
              val updated =
                existing.copy(
                  title = title,
                  description = description,
                  updatedAt = timestamp
                )
              transact(
                recipes.update(updated) *>
                  searchDocuments.rebuildSearchDocument(id, timestamp)
              ).as(updated).attempt.map(_.leftMap(databaseError))
            }
          }
      }
    }

  def deleteRecipe(id: RecipeId): IO[Either[ApiError, Unit]] = {
    val program =
      recipes.find(id).flatMap {
        case None =>
          NotFound("recipe").asLeft[List[String]].pure[ConnectionIO]
        case Some(_) =>
          photos.listByRecipe(id).flatMap { storedPhotos =>
            recipes.delete(id).map { deleted =>
              Either.cond(
                deleted,
                storedPhotos.map(_.storageKey),
                NotFound("recipe")
              )
            }
          }
      }

    transact(program).flatMap(completeDeletion)
  }

  def createMeal(
      recipeId: RecipeId,
      input: CreateMealInput
  ): IO[Either[ApiError, Meal]] =
    continue(
      validateText("notes", input.notes.getOrElse(""), MaxNotesLength)
    ) { notes =>
      now.flatMap { timestamp =>
        val meal =
          Meal(
            MealId.random,
            recipeId,
            notes,
            input.cookedAt,
            timestamp,
            timestamp
          )
        val program =
          recipes.find(recipeId).flatMap {
            case None    => NotFound("recipe").asLeft[Meal].pure[ConnectionIO]
            case Some(_) =>
              (meals.create(meal) *>
                recipes.refreshLastMadeAt(recipeId, timestamp) *>
                searchDocuments.rebuildSearchDocument(recipeId, timestamp))
                .as(meal.asRight[ApiError])
          }
        transact(program)
      }
    }

  def getMeal(
      recipeId: RecipeId,
      mealId: MealId
  ): IO[Either[ApiError, Meal]] =
    transact(meals.find(mealId)).map {
      case None       => Left(NotFound("meal"))
      case Some(meal) =>
        Either.cond(
          meal.recipeId == recipeId,
          meal,
          InvalidRelationship("meal does not belong to recipe")
        )
    }

  def updateMeal(
      recipeId: RecipeId,
      mealId: MealId,
      input: UpdateMealInput
  ): IO[Either[ApiError, Meal]] =
    if (input.notes.isEmpty && input.cookedAt.isEmpty) {
      IO.pure(
        Left(Validation(Map("body" -> List("must contain a field to update"))))
      )
    } else {
      val validatedNotes =
        input.notes match {
          case None        => IO.pure(Right(None))
          case Some(notes) =>
            validateText("notes", notes, MaxNotesLength).map(_.map(Some(_)))
        }
      continue(validatedNotes) { notes =>
        now.flatMap { timestamp =>
          val program =
            meals.find(mealId).flatMap {
              case None => NotFound("meal").asLeft[Meal].pure[ConnectionIO]
              case Some(existing) if existing.recipeId != recipeId =>
                InvalidRelationship("meal does not belong to recipe")
                  .asLeft[Meal]
                  .pure[ConnectionIO]
              case Some(existing) =>
                val updated =
                  existing.copy(
                    notes = notes.getOrElse(existing.notes),
                    cookedAt = input.cookedAt.getOrElse(existing.cookedAt),
                    updatedAt = timestamp
                  )
                (meals.update(updated) *>
                  recipes.refreshLastMadeAt(recipeId, timestamp) *>
                  searchDocuments.rebuildSearchDocument(recipeId, timestamp))
                  .as(updated.asRight[ApiError])
            }
          transact(program)
        }
      }
    }

  def deleteMeal(
      recipeId: RecipeId,
      mealId: MealId
  ): IO[Either[ApiError, Unit]] =
    now.flatMap { timestamp =>
      val program =
        meals.find(mealId).flatMap {
          case None =>
            NotFound("meal").asLeft[List[String]].pure[ConnectionIO]
          case Some(meal) if meal.recipeId != recipeId =>
            InvalidRelationship("meal does not belong to recipe")
              .asLeft[List[String]]
              .pure[ConnectionIO]
          case Some(_) =>
            photos.listByMeal(mealId).flatMap { storedPhotos =>
              (meals.delete(mealId) *>
                recipes.refreshLastMadeAt(recipeId, timestamp) *>
                searchDocuments.rebuildSearchDocument(recipeId, timestamp))
                .as(storedPhotos.map(_.storageKey).asRight[ApiError])
            }
        }
      transact(program).flatMap(completeDeletion)
    }

  def selectPrimaryPhoto(
      recipeId: RecipeId,
      photoId: PhotoId
  ): IO[Either[ApiError, Recipe]] =
    now.flatMap { timestamp =>
      val program =
        (recipes.find(recipeId), photos.find(photoId)).tupled.flatMap {
          case (None, _) =>
            NotFound("recipe").asLeft[Recipe].pure[ConnectionIO]
          case (_, None) =>
            NotFound("photo").asLeft[Recipe].pure[ConnectionIO]
          case (Some(recipe), Some(photo)) =>
            meals.find(photo.mealId).flatMap {
              case Some(meal) if meal.recipeId == recipeId =>
                val updated =
                  recipe.copy(
                    primaryPhotoId = Some(photoId),
                    updatedAt = timestamp
                  )
                recipes.update(updated).as(updated.asRight[ApiError])
              case _ =>
                InvalidRelationship("photo does not belong to recipe")
                  .asLeft[Recipe]
                  .pure[ConnectionIO]
            }
        }
      transact(program)
    }

  def createReference(
      recipeId: RecipeId,
      input: CreateReferenceInput
  ): IO[Either[ApiError, RecipeReference]] =
    continue(validateReference(input)) { validated =>
      now.flatMap { timestamp =>
        val reference =
          RecipeReference(
            ReferenceId.random,
            recipeId,
            validated.kind,
            validated.url,
            validated.citation,
            validated.displayName,
            timestamp,
            timestamp
          )
        val program =
          recipes.find(recipeId).flatMap {
            case None    => NotFound("recipe").asLeft[RecipeReference].pure[ConnectionIO]
            case Some(_) =>
              (references.create(reference) *>
                enqueueIfUrl(reference, timestamp) *>
                searchDocuments.rebuildSearchDocument(recipeId, timestamp))
                .as(reference.asRight[ApiError])
          }
        transact(program).attempt.map(
          _.fold(
            throwable => Left(databaseError(throwable)),
            identity
          )
        )
      }
    }

  def updateReference(
      recipeId: RecipeId,
      referenceId: ReferenceId,
      input: UpdateReferenceInput
  ): IO[Either[ApiError, RecipeReference]] =
    if (input.url.isEmpty && input.citation.isEmpty && input.displayName.isEmpty) {
      IO.pure(
        Left(Validation(Map("body" -> List("must contain a field to update"))))
      )
    } else {
      transact(references.find(referenceId)).flatMap {
        case None                                            => IO.pure(Left(NotFound("reference")))
        case Some(existing) if existing.recipeId != recipeId =>
          IO.pure(
            Left(InvalidRelationship("reference does not belong to recipe"))
          )
        case Some(existing) =>
          val merged =
            CreateReferenceInput(
              existing.kind.databaseValue,
              input.url.orElse(existing.url),
              input.citation.orElse(existing.citation),
              input.displayName.orElse(existing.displayName)
            )
          continue(validateReference(merged)) { validated =>
            now.flatMap { timestamp =>
              val updated =
                existing.copy(
                  url = validated.url,
                  citation = validated.citation,
                  displayName = validated.displayName,
                  updatedAt = timestamp
                )
              val urlChanged =
                existing.kind == ReferenceKind.Url && existing.url != updated.url
              val program =
                references.update(updated) *>
                  (if (urlChanged) enqueueIfUrl(updated, timestamp)
                   else ().pure[ConnectionIO]) *>
                  searchDocuments.rebuildSearchDocument(recipeId, timestamp)
              transact(program).as(updated).attempt.map(_.leftMap(databaseError))
            }
          }
      }
    }

  def deleteReference(
      recipeId: RecipeId,
      referenceId: ReferenceId
  ): IO[Either[ApiError, Unit]] =
    transact(references.find(referenceId)).flatMap {
      case None                                              => IO.pure(Left(NotFound("reference")))
      case Some(reference) if reference.recipeId != recipeId =>
        IO.pure(Left(InvalidRelationship("reference does not belong to recipe")))
      case Some(_) =>
        now
          .flatMap(timestamp =>
            transact(
              references.delete(referenceId).flatMap {
                case true =>
                  searchDocuments
                    .rebuildSearchDocument(recipeId, timestamp)
                    .as(true)
                case false => false.pure[ConnectionIO]
              }
            )
          )
          .map(deleted => Either.cond(deleted, (), NotFound("reference")))
    }

  def retryReference(
      recipeId: RecipeId,
      referenceId: ReferenceId
  ): IO[Either[ApiError, ScrapeJob]] =
    now.flatMap { timestamp =>
      val program =
        references.find(referenceId).flatMap {
          case None => NotFound("reference").asLeft[ScrapeJob].pure[ConnectionIO]
          case Some(reference) if reference.recipeId != recipeId =>
            InvalidRelationship("reference does not belong to recipe")
              .asLeft[ScrapeJob]
              .pure[ConnectionIO]
          case Some(reference) if reference.kind != ReferenceKind.Url =>
            Validation(Map("reference" -> List("book references cannot be scraped")))
              .asLeft[ScrapeJob]
              .pure[ConnectionIO]
          case Some(reference) =>
            val job = pendingJob(reference.id, timestamp)
            scrapeJobs.create(job).as(job.asRight[ApiError])
        }
      transact(program)
    }

  def getReferenceScrapeStatus(
      recipeId: RecipeId,
      referenceId: ReferenceId
  ): IO[Either[ApiError, ReferenceScrapeStatus]] = {
    val program =
      references.find(referenceId).flatMap {
        case None =>
          NotFound("reference").asLeft[ReferenceScrapeStatus].pure[ConnectionIO]
        case Some(reference) if reference.recipeId != recipeId =>
          InvalidRelationship("reference does not belong to recipe")
            .asLeft[ReferenceScrapeStatus]
            .pure[ConnectionIO]
        case Some(reference) if reference.kind != ReferenceKind.Url =>
          Validation(Map("reference" -> List("book references cannot be scraped")))
            .asLeft[ReferenceScrapeStatus]
            .pure[ConnectionIO]
        case Some(reference) =>
          (
            scrapeJobs.findLatestByReference(referenceId),
            scrapedDocuments.findByReference(referenceId)
          ).tupled.map { case (job, document) =>
            val status =
              job.map(_.status) match {
                case Some(ScrapeJobStatus.Running)   => "running"
                case Some(ScrapeJobStatus.Succeeded) => "complete"
                case Some(ScrapeJobStatus.Failed)    => "failed"
                case _                               => "pending"
              }
            ReferenceScrapeStatus(reference.id, status, job, document)
              .asRight[ApiError]
          }
      }
    transact(program)
  }

  private final case class ValidatedReference(
      kind: ReferenceKind,
      url: Option[String],
      citation: Option[String],
      displayName: Option[String]
  )

  private def validateRecipe(
      rawTitle: String,
      rawDescription: String
  ): IO[Either[ApiError, (String, String)]] = {
    val title = rawTitle.trim
    val description = rawDescription.trim
    val errors =
      List(
        Option.when(title.isEmpty)("title" -> "must not be blank"),
        Option.when(title.length > MaxTitleLength)(
          "title" -> s"must be at most $MaxTitleLength characters"
        ),
        Option.when(description.length > MaxDescriptionLength)(
          "description" -> s"must be at most $MaxDescriptionLength characters"
        )
      ).flatten
    IO.pure(validationResult(errors, (title, description)))
  }

  private def validateReference(
      input: CreateReferenceInput
  ): IO[Either[ApiError, ValidatedReference]] = {
    val kind = input.kind.trim.toLowerCase(Locale.ROOT) match {
      case "url"  => Some(ReferenceKind.Url)
      case "book" => Some(ReferenceKind.Book)
      case _      => None
    }
    val url = input.url.map(_.trim).filter(_.nonEmpty)
    val citation = input.citation.map(_.trim).filter(_.nonEmpty)
    val displayName = input.displayName.map(_.trim).filter(_.nonEmpty)
    val errors =
      List(
        Option.when(kind.isEmpty)("kind" -> "must be url or book"),
        Option.when(kind.contains(ReferenceKind.Url) && !url.exists(validHttpUrl))(
          "url" -> "must be an absolute http or https URL"
        ),
        Option.when(kind.contains(ReferenceKind.Url) && citation.nonEmpty)(
          "citation" -> "must be omitted for URL references"
        ),
        Option.when(kind.contains(ReferenceKind.Book) && citation.isEmpty)(
          "citation" -> "is required for book references"
        ),
        Option.when(kind.contains(ReferenceKind.Book) && url.nonEmpty)(
          "url" -> "must be omitted for book references"
        ),
        Option.when(url.exists(_.length > MaxReferenceValueLength))(
          "url" -> s"must be at most $MaxReferenceValueLength characters"
        ),
        Option.when(citation.exists(_.length > MaxReferenceValueLength))(
          "citation" -> s"must be at most $MaxReferenceValueLength characters"
        ),
        Option.when(displayName.exists(_.length > MaxDisplayNameLength))(
          "displayName" -> s"must be at most $MaxDisplayNameLength characters"
        )
      ).flatten
    IO.pure(
      kind match {
        case Some(value) =>
          validationResult(
            errors,
            ValidatedReference(value, url, citation, displayName)
          )
        case None => Left(Validation(groupErrors(errors)))
      }
    )
  }

  private def validateText(
      field: String,
      raw: String,
      maximum: Int
  ): IO[Either[ApiError, String]] = {
    val value = raw.trim
    val errors =
      Option
        .when(value.length > maximum)(
          field -> s"must be at most $maximum characters"
        )
        .toList
    IO.pure(validationResult(errors, value))
  }

  private def validHttpUrl(raw: String): Boolean =
    Either
      .catchNonFatal(URI.create(raw))
      .exists(uri =>
        Set("http", "https").contains(Option(uri.getScheme).fold("")(_.toLowerCase(Locale.ROOT))) &&
          Option(uri.getHost).exists(_.nonEmpty)
      )

  private def enqueueIfUrl(
      reference: RecipeReference,
      timestamp: Instant
  ): ConnectionIO[Unit] =
    if (reference.kind == ReferenceKind.Url) {
      scrapeJobs.create(pendingJob(reference.id, timestamp))
    } else {
      ().pure[ConnectionIO]
    }

  private def pendingJob(referenceId: ReferenceId, timestamp: Instant): ScrapeJob =
    ScrapeJob(
      ScrapeJobId.random,
      referenceId,
      ScrapeJobStatus.Pending,
      0,
      timestamp,
      None,
      None,
      None,
      timestamp,
      timestamp
    )

  private def recipeText(recipe: Recipe): String =
    List(recipe.title, recipe.description).filter(_.nonEmpty).mkString("\n")

  private def sortRecipes(
      recipes: List[Recipe],
      sort: RecipeSort
  ): List[Recipe] =
    sort match {
      case RecipeSort.Recent =>
        recipes.sortWith { (left, right) =>
          val leftTime =
            left.lastMadeAt.getOrElse(left.updatedAt).toEpochMilli
          val rightTime =
            right.lastMadeAt.getOrElse(right.updatedAt).toEpochMilli
          leftTime > rightTime ||
          (leftTime == rightTime &&
            RecipeId.value(left.id).toString < RecipeId.value(right.id).toString)
        }
      case RecipeSort.Updated =>
        recipes.sortWith { (left, right) =>
          val leftTime = left.updatedAt.toEpochMilli
          val rightTime = right.updatedAt.toEpochMilli
          leftTime > rightTime ||
          (leftTime == rightTime &&
            RecipeId.value(left.id).toString < RecipeId.value(right.id).toString)
        }
      case RecipeSort.Title =>
        recipes.sortBy(recipe =>
          (
            recipe.title.toLowerCase(Locale.ROOT),
            RecipeId.value(recipe.id).toString
          )
        )
    }

  private def cursorValue(recipe: Recipe, sort: RecipeSort): String =
    sort match {
      case RecipeSort.Recent =>
        s"${recipe.lastMadeAt.getOrElse(recipe.updatedAt).toEpochMilli}:${RecipeId.value(recipe.id)}"
      case RecipeSort.Updated =>
        s"${recipe.updatedAt.toEpochMilli}:${RecipeId.value(recipe.id)}"
      case RecipeSort.Title =>
        s"${recipe.title.toLowerCase(Locale.ROOT)}:${RecipeId.value(recipe.id)}"
    }

  private def encodeCursor(value: String): String =
    Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decodeCursor(raw: String): Either[ApiError, Option[String]] =
    Either
      .catchNonFatal(
        String(
          Base64.getUrlDecoder.decode(raw),
          StandardCharsets.UTF_8
        )
      )
      .leftMap(_ => Validation(Map("cursor" -> List("must be a valid pagination cursor"))))
      .map(Some(_))

  private def validationResult[A](
      errors: List[(String, String)],
      value: A
  ): Either[ApiError, A] =
    Either.cond(errors.isEmpty, value, Validation(groupErrors(errors)))

  private def groupErrors(
      errors: List[(String, String)]
  ): Map[String, List[String]] =
    errors.groupMap(_._1)(_._2)

  private def databaseError(throwable: Throwable): ApiError =
    throwable match {
      case error: PSQLException if error.getSQLState == "23505" =>
        Conflict("A record with the same unique value already exists")
      case _ => throw throwable
    }

  private def completeDeletion(
      result: Either[ApiError, List[String]]
  ): IO[Either[ApiError, Unit]] =
    result match {
      case Left(error) =>
        IO.pure(Left(error))
      case Right(storageKeys) =>
        photoCleanup.deleteBestEffort(storageKeys).as(Right(()))
    }

  private def transact[A](program: ConnectionIO[A]): IO[A] =
    program.transact(transactor)

  private def now: IO[Instant] = Clock[IO].realTimeInstant

  private def continue[A, B](
      validated: IO[Either[ApiError, A]]
  )(use: A => IO[Either[ApiError, B]]): IO[Either[ApiError, B]] =
    validated.flatMap {
      case Left(error)  => IO.pure(Left(error))
      case Right(value) => use(value)
    }
}

object RecipeApiService {
  def apply(
      transactor: Transactor[IO],
      photoCleanup: PhotoCleanup[IO]
  ): RecipeApiService =
    new RecipeApiService(
      transactor,
      DoobieRepositories.recipes,
      DoobieRepositories.meals,
      DoobieRepositories.photos,
      DoobieRepositories.references,
      DoobieRepositories.scrapedDocuments,
      DoobieRepositories.scrapeJobs,
      DoobieRepositories.searchDocuments,
      photoCleanup
    )
}
