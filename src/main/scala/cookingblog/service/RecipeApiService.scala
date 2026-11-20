package cookingblog.service

import cookingblog.database.Database
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import cookingblog.domain.*
import cookingblog.storage.StorageKey
import cookingblog.repository.*
import cookingblog.service.ApiError.*
import skunk.SqlState

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.{Base64, Locale}

/** Application service for the authenticated recipe API.
  *
  * It validates boundary input, enforces nested-resource relationships, and keeps related
  * persistence changes in one transaction.
  */
final class RecipeApiService(
    pool: Database,
    photoCleanup: PhotoCleanup[IO]
) {
  private val MaxTitleLength = 200
  private val MaxDescriptionLength = 20000
  private val MaxNotesLength = 20000
  private val MaxReferenceValueLength = 4000
  private val MaxDisplayNameLength = 500
  private val MaxKeywordLength = 100
  private val MaxKeywords = 50

  private final case class CreateRecipeCommand(
      title: String,
      description: String,
      keywords: List[String]
  )
  private final case class CreateMealCommand(notes: String, cookedAt: Instant)

  /** Validates a new recipe and atomically creates its keyword and search projections. */
  def createRecipe(input: CreateRecipeInput): IO[Either[ApiError, Recipe]] =
    continue(parseCreateRecipe(input)) { command =>
      now.flatMap { timestamp =>
        val recipe =
          Recipe(
            RecipeId.random,
            command.title,
            command.description,
            None,
            timestamp,
            timestamp,
            None
          )
        pool
          .transaction { repos =>
            import repos.{recipes, keywords, searchDocuments}
            recipes.create(recipe) *>
              keywords.replace(recipe.id, command.keywords) *>
              searchDocuments.rebuildSearchDocument(recipe.id, timestamp)
          }
          .as(recipe)
          .attempt
          .map(_.leftMap(databaseError))
      }
    }

  def getRecipe(id: RecipeId): IO[Either[ApiError, Recipe]] =
    pool
      .use(_.recipes.find(id))
      .map(_.toRight(NotFound("recipe")))

  /** Lists recipes either by configured browse order or database relevance. Query cursors embed the
    * normalized query and sort to prevent accidental reuse with a different result set.
    */
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
        val normalizedQuery =
          query.map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty)
        val stored = normalizedQuery.fold(pool.use(_.recipes.list))(value =>
          pool.use(_.searchDocuments.search(value).map(_.map(_.recipe)))
        )
        stored.map { storedRecipes =>
          val filtered = storedRecipes
          val sorted =
            if (normalizedQuery.nonEmpty && sort == RecipeSort.Recent) filtered
            else sortRecipes(filtered, sort)
          val remaining =
            decodedCursor match {
              case None        => Right(sorted)
              case Some(value) =>
                val index =
                  sorted.indexWhere(recipe => cursorBinding(recipe, sort, normalizedQuery) == value)
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
                encodeCursor(cursorBinding(pageItems.last, sort, normalizedQuery))
              )
            RecipePage(pageItems, next)
          }
        }
    }
  }

  /** Replaces requested recipe fields, optionally replacing its complete normalized keyword set. */
  def updateRecipe(
      id: RecipeId,
      input: UpdateRecipeInput
  ): IO[Either[ApiError, Recipe]] =
    if (input.title.isEmpty && input.description.isEmpty && input.keywords.isEmpty) {
      IO.pure(
        Left(Validation(Map("body" -> List("must contain a field to update"))))
      )
    } else {
      now.flatMap { timestamp =>
        pool
          .transaction { repos =>
            import repos.{recipes, searchDocuments, keywords}
            recipes.findForUpdate(id).flatMap {
              case None           => NotFound("recipe").asLeft[Recipe].pure[IO]
              case Some(existing) =>
                val validation = (
                  validateRecipe(
                    input.title.getOrElse(existing.title),
                    input.description.getOrElse(existing.description)
                  ),
                  input.keywords.traverse(validateKeywords)
                ).tupled
                validation match {
                  case Left(error) => error.asLeft[Recipe].pure[IO]
                  case Right(((title, description), replacementKeywords)) =>
                    recipes.updateDetails(id, title, description, timestamp).flatMap {
                      case None          => NotFound("recipe").asLeft[Recipe].pure[IO]
                      case Some(updated) =>
                        (replacementKeywords.traverse_(keywords.replace(id, _)) *>
                          searchDocuments.rebuildSearchDocument(id, timestamp))
                          .as(updated.asRight[ApiError])
                    }
                }
            }
          }
          .attempt
          .map(_.fold(error => Left(databaseError(error)), identity))
      }
    }

  /** Deletes metadata transactionally, then performs idempotent cleanup of its photo objects. */
  def deleteRecipe(id: RecipeId): IO[Either[ApiError, Unit]] = {
    pool
      .transaction { repos =>
        import repos.{recipes, photos}
        recipes.findForUpdate(id).flatMap {
          case None =>
            NotFound("recipe").asLeft[List[StorageKey]].pure[IO]
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
      }
      .flatMap(completeDeletion)
  }

  /** Creates a meal and refreshes both the cached last-made timestamp and search projection. */
  def createMeal(
      recipeId: RecipeId,
      input: CreateMealInput
  ): IO[Either[ApiError, Meal]] =
    continue(parseCreateMeal(input)) { command =>
      now.flatMap { timestamp =>
        val meal =
          Meal(
            MealId.random,
            recipeId,
            command.notes,
            command.cookedAt,
            timestamp,
            timestamp
          )
        pool.transaction { repos =>
          import repos.{recipes, meals}
          recipes.findForUpdate(recipeId).flatMap {
            case None    => NotFound("recipe").asLeft[Meal].pure[IO]
            case Some(_) =>
              (meals.create(meal) *> refreshMealProjections(repos, recipeId, timestamp))
                .as(meal.asRight[ApiError])
          }
        }
      }
    }

  def getMeal(
      recipeId: RecipeId,
      mealId: MealId
  ): IO[Either[ApiError, Meal]] =
    pool.use(_.meals.find(mealId)).map {
      case None       => Left(NotFound("meal"))
      case Some(meal) =>
        Either.cond(
          meal.recipeId == recipeId,
          meal,
          InvalidRelationship("meal does not belong to recipe")
        )
    }

  /** Updates a related meal while keeping recipe ordering and search content in sync. */
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
          transactWrite(recipeId) { repos =>
            import repos.meals
            meals.find(mealId).flatMap {
              case None => NotFound("meal").asLeft[Meal].pure[IO]
              case Some(existing) if existing.recipeId != recipeId =>
                InvalidRelationship("meal does not belong to recipe")
                  .asLeft[Meal]
                  .pure[IO]
              case Some(existing) =>
                val updated =
                  existing.copy(
                    notes = notes.getOrElse(existing.notes),
                    cookedAt = input.cookedAt.getOrElse(existing.cookedAt),
                    updatedAt = timestamp
                  )
                meals.update(updated).flatMap {
                  case false => NotFound("meal").asLeft[Meal].pure[IO]
                  case true  =>
                    refreshMealProjections(repos, recipeId, timestamp)
                      .as(updated.asRight[ApiError])
                }
            }
          }
        }
      }
    }

  /** Deletes a meal, recalculates recipe state, and cleans associated photo objects afterward. */
  def deleteMeal(
      recipeId: RecipeId,
      mealId: MealId
  ): IO[Either[ApiError, Unit]] =
    now.flatMap { timestamp =>
      transactWrite(recipeId) { repos =>
        import repos.{meals, photos}
        meals.find(mealId).flatMap {
          case None =>
            NotFound("meal").asLeft[List[StorageKey]].pure[IO]
          case Some(meal) if meal.recipeId != recipeId =>
            InvalidRelationship("meal does not belong to recipe")
              .asLeft[List[StorageKey]]
              .pure[IO]
          case Some(_) =>
            photos.listByMeal(mealId).flatMap { storedPhotos =>
              meals.delete(mealId).flatMap {
                case false => NotFound("meal").asLeft[List[StorageKey]].pure[IO]
                case true  =>
                  refreshMealProjections(repos, recipeId, timestamp)
                    .as(storedPhotos.map(_.storageKey).asRight[ApiError])
              }
            }
        }
      }.flatMap(completeDeletion)
    }

  /** Selects a primary photo only after proving that it belongs to the requested recipe. */
  def selectPrimaryPhoto(
      recipeId: RecipeId,
      photoId: PhotoId
  ): IO[Either[ApiError, Recipe]] =
    now.flatMap { timestamp =>
      pool.transaction { repos =>
        import repos.{recipes, meals, photos}
        (recipes.findForUpdate(recipeId), photos.find(photoId)).tupled.flatMap {
          case (None, _) =>
            NotFound("recipe").asLeft[Recipe].pure[IO]
          case (_, None) =>
            NotFound("photo").asLeft[Recipe].pure[IO]
          case (Some(_), Some(photo)) =>
            meals.find(photo.mealId).flatMap {
              case Some(meal) if meal.recipeId == recipeId =>
                recipes
                  .setPrimaryPhoto(recipeId, photoId, timestamp)
                  .map(_.toRight(NotFound("recipe")))
              case _ =>
                InvalidRelationship("photo does not belong to recipe")
                  .asLeft[Recipe]
                  .pure[IO]
            }
        }
      }
    }

  /** Creates a reference and, for URLs, enqueues durable scrape work in the same transaction. */
  def createReference(
      recipeId: RecipeId,
      input: CreateReferenceInput
  ): IO[Either[ApiError, RecipeReference]] =
    continue(IO.pure(validateReference(input))) { validated =>
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
        pool
          .transaction { repos =>
            import repos.{recipes, references, searchDocuments}
            recipes.findForUpdate(recipeId).flatMap {
              case None    => NotFound("recipe").asLeft[RecipeReference].pure[IO]
              case Some(_) =>
                (references.create(reference) *>
                  enqueueIfUrl(repos, reference, timestamp) *>
                  searchDocuments.rebuildSearchDocument(recipeId, timestamp))
                  .as(reference.asRight[ApiError])
            }
          }
          .attempt
          .map(
            _.fold(
              throwable => Left(databaseError(throwable)),
              identity
            )
          )
      }
    }

  /** Updates a reference, re-enqueuing import work when its URL changes. */
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
      now.flatMap { timestamp =>
        transactWrite(recipeId) { repos =>
          import repos.{references, searchDocuments}
          references.find(referenceId).flatMap {
            case None => NotFound("reference").asLeft[RecipeReference].pure[IO]
            case Some(existing) if existing.recipeId != recipeId =>
              InvalidRelationship("reference does not belong to recipe")
                .asLeft[RecipeReference]
                .pure[IO]
            case Some(existing) =>
              val merged = CreateReferenceInput(
                existing.kind.databaseValue,
                input.url.orElse(existing.url),
                input.citation.orElse(existing.citation),
                input.displayName.orElse(existing.displayName)
              )
              validateReference(merged) match {
                case Left(error)      => error.asLeft[RecipeReference].pure[IO]
                case Right(validated) =>
                  val updated = existing.copy(
                    url = validated.url,
                    citation = validated.citation,
                    displayName = validated.displayName,
                    updatedAt = timestamp
                  )
                  val urlChanged =
                    existing.kind == ReferenceKind.Url && existing.url != updated.url
                  references.update(updated).flatMap {
                    case false => NotFound("reference").asLeft[RecipeReference].pure[IO]
                    case true  =>
                      ((if (urlChanged) enqueueIfUrl(repos, updated, timestamp)
                        else ().pure[IO]) *>
                        searchDocuments.rebuildSearchDocument(recipeId, timestamp))
                        .as(updated.asRight[ApiError])
                  }
              }
          }
        }.attempt
          .map(_.fold(error => Left(databaseError(error)), identity))
      }
    }

  def deleteReference(
      recipeId: RecipeId,
      referenceId: ReferenceId
  ): IO[Either[ApiError, Unit]] =
    now.flatMap { timestamp =>
      transactWrite(recipeId) { repos =>
        import repos.{references, searchDocuments}
        references.find(referenceId).flatMap {
          case None => NotFound("reference").asLeft[Unit].pure[IO]
          case Some(reference) if reference.recipeId != recipeId =>
            InvalidRelationship("reference does not belong to recipe")
              .asLeft[Unit]
              .pure[IO]
          case Some(_) =>
            references.delete(referenceId).flatMap {
              case false => NotFound("reference").asLeft[Unit].pure[IO]
              case true  =>
                searchDocuments.rebuildSearchDocument(recipeId, timestamp).as(().asRight[ApiError])
            }
        }
      }
    }

  def retryReference(
      recipeId: RecipeId,
      referenceId: ReferenceId
  ): IO[Either[ApiError, ScrapeJob]] =
    now.flatMap { timestamp =>
      transactWrite(recipeId) { repos =>
        import repos.{references, scrapeJobs}
        references.find(referenceId).flatMap {
          case None => NotFound("reference").asLeft[ScrapeJob].pure[IO]
          case Some(reference) if reference.recipeId != recipeId =>
            InvalidRelationship("reference does not belong to recipe")
              .asLeft[ScrapeJob]
              .pure[IO]
          case Some(reference) if reference.kind != ReferenceKind.Url =>
            Validation(Map("reference" -> List("book references cannot be scraped")))
              .asLeft[ScrapeJob]
              .pure[IO]
          case Some(reference) =>
            val job = pendingJob(reference.id, timestamp)
            scrapeJobs.create(job).as(job.asRight[ApiError])
        }
      }
    }

  def getReferenceScrapeStatus(
      recipeId: RecipeId,
      referenceId: ReferenceId
  ): IO[Either[ApiError, ReferenceScrapeStatus]] = {
    pool.transaction { repos =>
      import repos.{references, scrapedDocuments, scrapeJobs}
      references.find(referenceId).flatMap {
        case None =>
          NotFound("reference").asLeft[ReferenceScrapeStatus].pure[IO]
        case Some(reference) if reference.recipeId != recipeId =>
          InvalidRelationship("reference does not belong to recipe")
            .asLeft[ReferenceScrapeStatus]
            .pure[IO]
        case Some(reference) if reference.kind != ReferenceKind.Url =>
          Validation(Map("reference" -> List("book references cannot be scraped")))
            .asLeft[ReferenceScrapeStatus]
            .pure[IO]
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
            ReferenceScrapeStatus(
              reference.id,
              status,
              processingEnabled = true,
              job,
              document
            )
              .asRight[ApiError]
          }
      }
    }
  }

  private final case class ValidatedReference(
      kind: ReferenceKind,
      url: Option[String],
      citation: Option[String],
      displayName: Option[String]
  )

  private def parseCreateRecipe(
      input: CreateRecipeInput
  ): IO[Either[ApiError, CreateRecipeCommand]] =
    IO.pure(
      (
        validateRecipe(input.title, input.description.getOrElse("")),
        validateKeywords(input.keywords.getOrElse(""))
      ).mapN { case ((title, description), keywords) =>
        CreateRecipeCommand(title, description, keywords)
      }
    )

  private def parseCreateMeal(
      input: CreateMealInput
  ): IO[Either[ApiError, CreateMealCommand]] =
    validateText("notes", input.notes.getOrElse(""), MaxNotesLength)
      .map(_.map(notes => CreateMealCommand(notes, input.cookedAt)))

  private def validateRecipe(
      rawTitle: String,
      rawDescription: String
  ): Either[ApiError, (String, String)] = {
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
    validationResult(errors, (title, description))
  }

  private def validateReference(
      input: CreateReferenceInput
  ): Either[ApiError, ValidatedReference] = {
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
    kind match {
      case Some(value) =>
        validationResult(
          errors,
          ValidatedReference(value, url, citation, displayName)
        )
      case None => Left(Validation(groupErrors(errors)))
    }
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
      repos: SkunkRepositories,
      reference: RecipeReference,
      timestamp: Instant
  ): IO[Unit] =
    if (reference.kind == ReferenceKind.Url) {
      repos.scrapeJobs.create(pendingJob(reference.id, timestamp))
    } else {
      ().pure[IO]
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

  /** Normalizes comma-separated tags while preserving first spelling and rejecting unsafe volume.
    */
  private def validateKeywords(raw: String): Either[ApiError, List[String]] = {
    val normalized =
      raw
        .split(",", -1)
        .toList
        .map(_.trim)
        .filter(_.nonEmpty)
        .foldLeft(List.empty[String]) { (values, keyword) =>
          if (values.exists(_.equalsIgnoreCase(keyword))) values else values :+ keyword
        }
    val errors =
      List(
        Option.when(normalized.size > MaxKeywords)(
          "must contain at most 50 comma-separated keywords"
        ),
        Option.when(normalized.exists(_.length > MaxKeywordLength))(
          "each keyword must be at most 100 characters"
        )
      ).flatten
    Either.cond(errors.isEmpty, normalized, Validation(Map("keywords" -> errors)))
  }

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

  private def cursorBinding(
      recipe: Recipe,
      sort: RecipeSort,
      query: Option[String]
  ): String =
    s"${sort.value}\u0000${query.getOrElse("")}\u0000${cursorValue(recipe, sort)}"

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
      case SqlState.UniqueViolation(_) =>
        Conflict("A record with the same unique value already exists")
      case _ => throw throwable
    }

  private def completeDeletion(
      result: Either[ApiError, List[StorageKey]]
  ): IO[Either[ApiError, Unit]] =
    result match {
      case Left(error) =>
        IO.pure(Left(error))
      case Right(storageKeys) =>
        photoCleanup.deleteBestEffort(storageKeys).as(Right(()))
    }

  /** Parent-first locking serializes source writes and projection rebuilds under READ COMMITTED.
    * Never run network or photo-store I/O while holding this lock.
    */
  private def transactWrite[A](recipeId: RecipeId)(use: SkunkRepositories => IO[A]): IO[A] =
    pool.transaction(repos => repos.recipes.findForUpdate(recipeId) *> IO.defer(use(repos)))

  private def refreshMealProjections(
      repos: SkunkRepositories,
      recipeId: RecipeId,
      timestamp: Instant
  ): IO[Unit] =
    repos.recipes.refreshLastMadeAt(recipeId, timestamp).flatMap {
      case true  => repos.searchDocuments.rebuildSearchDocument(recipeId, timestamp)
      case false => IO.raiseError(IllegalStateException("Locked recipe disappeared during a write"))
    }

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
      pool: Database,
      photoCleanup: PhotoCleanup[IO]
  ): RecipeApiService =
    new RecipeApiService(
      pool,
      photoCleanup
    )
}
