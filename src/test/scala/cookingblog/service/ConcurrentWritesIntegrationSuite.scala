package cookingblog.service

import cookingblog.database.Database
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import cookingblog.config.ScrapeConfig
import cookingblog.domain.*
import cookingblog.repository.SkunkRepositories
import cookingblog.scraping.{PageScraper, ScrapedPage, ScrapeWorker}
import cookingblog.storage.{LocalPhotoStore, StorageKey}
import cookingblog.testing.PostgresIntegrationSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import java.nio.file.Files
import skunk.Transaction
import skunk.codec.all.text
import skunk.implicits.*
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** Hold a transaction open in an isolated PostgreSQL Testcontainer, observe blocked writers, then
  * release it. No sleeps are used to guess when a write has reached its critical section.
  */
final class ConcurrentWritesIntegrationSuite extends PostgresIntegrationSuite {
  private given Logger[IO] = NoOpLogger[IO]

  override protected def databasePoolSize: Int = 6

  private val cookedAt = Instant.parse("2026-10-26T12:00:00Z")
  private val cleanup = new PhotoCleanup[IO] {
    override def deleteBestEffort(keys: List[StorageKey]): IO[Unit] = IO.unit
  }

  test("concurrent recipe patches preserve unrelated fields and keywords") {
    context.use { ctx =>
      underLock(
        ctx,
        List(
          ctx.api.updateRecipe(
            ctx.id,
            UpdateRecipeInput(Some("Renamed " + UUID.randomUUID()), None, None)
          ),
          ctx.api
            .updateRecipe(ctx.id, UpdateRecipeInput(None, Some("new description"), Some("saffron")))
        )
      ) *> (for {
        recipe <- ctx.api.getRecipe(ctx.id).flatMap(success)
        keywords <- ctx.xa.use(_.keywords.listByRecipe(ctx.id))
        search <- ctx.xa.use(_.searchDocuments.find(ctx.id))
      } yield {
        assert(recipe.title.startsWith("Renamed "))
        assertEquals(recipe.description, "new description")
        assertEquals(keywords.map(_.keyword), List("saffron"))
        assert(
          search.exists(doc =>
            doc.plainText.contains(recipe.title) && doc.plainText.contains("saffron")
          )
        )
      })
    }
  }

  test("recipe edits cannot revert concurrent primary-photo selection or meal timestamps") {
    context.use { ctx =>
      for {
        meal <- ctx.api.createMeal(ctx.id, CreateMealInput(None, cookedAt)).flatMap(success)
        photo <- createPhoto(ctx, meal.id)
        later = cookedAt.plusSeconds(3600)
        _ <- underLock(
          ctx,
          List(
            ctx.api
              .updateRecipe(ctx.id, UpdateRecipeInput(None, Some("edited description"), None))
              .map(_.map(_ => ())),
            ctx.api.selectPrimaryPhoto(ctx.id, photo.id).map(_.map(_ => ())),
            ctx.api.createMeal(ctx.id, CreateMealInput(Some("new meal"), later)).map(_.map(_ => ()))
          )
        )
        recipe <- ctx.api.getRecipe(ctx.id).flatMap(success)
      } yield {
        assertEquals(recipe.description, "edited description")
        assertEquals(recipe.primaryPhotoId, Some(photo.id))
        assertEquals(recipe.lastMadeAt, Some(later))
      }
    }
  }

  test("concurrent partial meal and reference edits merge against locked current values") {
    context.use { ctx =>
      for {
        meal <- ctx.api
          .createMeal(ctx.id, CreateMealInput(Some("old notes"), cookedAt))
          .flatMap(success)
        reference <- ctx.api
          .createReference(
            ctx.id,
            CreateReferenceInput("url", Some("https://example.com/old"), None, Some("old label"))
          )
          .flatMap(success)
        later = cookedAt.plusSeconds(7200)
        _ <- underLock(
          ctx,
          List(
            ctx.api
              .updateMeal(ctx.id, meal.id, UpdateMealInput(Some("saffron notes"), None))
              .map(_.map(_ => ())),
            ctx.api
              .updateMeal(ctx.id, meal.id, UpdateMealInput(None, Some(later)))
              .map(_.map(_ => ())),
            ctx.api
              .updateReference(
                ctx.id,
                reference.id,
                UpdateReferenceInput(Some("https://example.com/new"), None, None)
              )
              .map(_.map(_ => ())),
            ctx.api
              .updateReference(
                ctx.id,
                reference.id,
                UpdateReferenceInput(None, None, Some("new label"))
              )
              .map(_.map(_ => ()))
          )
        )
        savedMeal <- ctx.api.getMeal(ctx.id, meal.id).flatMap(success)
        savedReference <- ctx.xa.use(_.references.find(reference.id))
        recipe <- ctx.api.getRecipe(ctx.id).flatMap(success)
        search <- ctx.xa.use(_.searchDocuments.find(ctx.id))
        jobs <- ctx.xa.use(_.scrapeJobs.listByReference(reference.id))
      } yield {
        assertEquals(savedMeal.notes, "saffron notes")
        assertEquals(savedMeal.cookedAt, later)
        assertEquals(recipe.lastMadeAt, Some(later))
        assertEquals(savedReference.flatMap(_.url), Some("https://example.com/new"))
        assertEquals(savedReference.flatMap(_.displayName), Some("new label"))
        assertEquals(jobs.size, 2)
        assert(
          search.exists(doc =>
            doc.plainText.contains("saffron notes") && doc.plainText.contains("new label")
          )
        )
      }
    }
  }

  test(
    "concurrent meal and reference creates/deletes keep search and last-made projections consistent"
  ) {
    context.use { ctx =>
      for {
        oldMeal <- ctx.api
          .createMeal(ctx.id, CreateMealInput(Some("obsolete meal"), cookedAt))
          .flatMap(success)
        oldReference <- ctx.api
          .createReference(
            ctx.id,
            CreateReferenceInput("book", None, Some("obsolete citation"), None)
          )
          .flatMap(success)
        later = cookedAt.plusSeconds(3600)
        _ <- underLock(
          ctx,
          List(
            ctx.api.deleteMeal(ctx.id, oldMeal.id),
            ctx.api.deleteReference(ctx.id, oldReference.id),
            ctx.api
              .createMeal(ctx.id, CreateMealInput(Some("fresh meal"), later))
              .map(_.map(_ => ())),
            ctx.api
              .createReference(
                ctx.id,
                CreateReferenceInput("book", None, Some("fresh citation"), None)
              )
              .map(_.map(_ => ()))
          )
        )
        recipe <- ctx.api.getRecipe(ctx.id).flatMap(success)
        search <- ctx.xa.use(_.searchDocuments.find(ctx.id))
      } yield {
        assertEquals(recipe.lastMadeAt, Some(later))
        assert(
          search.exists(doc =>
            doc.plainText.contains("fresh meal") && doc.plainText.contains(
              "fresh citation"
            ) && !doc.plainText.contains("obsolete")
          )
        )
      }
    }
  }

  test("intent-specific recipe writes preserve derived fields and report missing rows") {
    context.use { ctx =>
      for {
        meal <- ctx.api.createMeal(ctx.id, CreateMealInput(None, cookedAt)).flatMap(success)
        photo <- createPhoto(ctx, meal.id)
        recipe <- ctx.api.getRecipe(ctx.id).flatMap(success)
        updatedAt = recipe.updatedAt.plusSeconds(1)
        selected <- ctx.xa.use(_.recipes.setPrimaryPhoto(ctx.id, photo.id, updatedAt))
        updated <- ctx.xa.use(
          _.recipes.updateDetails(ctx.id, recipe.title, "details only", updatedAt)
        )
        missingId = RecipeId.random
        missingDetails <- ctx.xa.use(_.recipes.updateDetails(missingId, "missing", "", cookedAt))
        missingPrimary <- ctx.xa.use(_.recipes.setPrimaryPhoto(missingId, photo.id, cookedAt))
      } yield {
        assertEquals(selected.flatMap(_.primaryPhotoId), Some(photo.id))
        assertEquals(updated.flatMap(_.primaryPhotoId), Some(photo.id))
        assertEquals(updated.flatMap(_.lastMadeAt), Some(cookedAt))
        assertEquals(updated.map(_.description), Some("details only"))
        assertEquals(missingDetails, None)
        assertEquals(missingPrimary, None)
      }
    }
  }

  test("scraper completion and meal edits serialize search projection rebuilds") {
    context.use { ctx =>
      for {
        meal <- ctx.api.createMeal(ctx.id, CreateMealInput(None, cookedAt)).flatMap(success)
        reference <- ctx.api
          .createReference(
            ctx.id,
            CreateReferenceInput("url", Some("https://example.com/concurrent-import"), None, None)
          )
          .flatMap(success)
        job <- ctx.xa
          .use(_.scrapeJobs.findLatestByReference(reference.id))
          .flatMap(value => IO.fromOption(value)(AssertionError("Missing scrape job")))
        _ <- ctx.xa.use(_.scrapeJobs.update(job.copy(availableAt = Instant.EPOCH)))
        random <- Random.scalaUtilRandom[IO]
        scraper = new PageScraper {
          override def scrape(url: String): IO[ScrapedPage] = IO.pure(
            ScrapedPage(url, url, None, "imported saffron instructions", "abc123", None, None)
          )
        }
        worker = ScrapeWorker(ctx.xa, scrapeConfig, scraper, random)
        _ <- locked(ctx.id).use { case (connection, _) =>
          worker.run.use { _ =>
            (waitForBlocked(1, ctx.xa) *>
              Resource
                .make(
                  ctx.api
                    .updateMeal(ctx.id, meal.id, UpdateMealInput(Some("fresh meal notes"), None))
                    .start
                )(_.cancel)
                .use { edit =>
                  (waitForBlocked(2, ctx.xa) *>
                    connection.commit *>
                    edit.joinWithNever.flatMap(success) *>
                    waitForCompleted(job.id, ctx.xa)).guaranteeCase {
                    case cats.effect.Outcome.Succeeded(_) => IO.unit
                    case _                                => connection.rollback.void.attempt.void
                  }
                }).guaranteeCase {
              case cats.effect.Outcome.Succeeded(_) => IO.unit
              case _                                => connection.rollback.void.attempt.void
            }
          }
        }
        search <- ctx.xa.use(_.searchDocuments.find(ctx.id))
        document <- ctx.xa.use(_.scrapedDocuments.findByReference(reference.id))
      } yield {
        assert(document.exists(_.contentText == "imported saffron instructions"))
        assert(
          search.exists(doc =>
            doc.plainText.contains("imported saffron instructions") &&
              doc.plainText.contains("fresh meal notes")
          )
        )
      }
    }
  }

  private val scrapeConfig = ScrapeConfig(
    enabled = true,
    workerCount = 1,
    perHostConcurrency = 1,
    pollInterval = 10.millis,
    staleJobTimeout = 1.minute,
    requestTimeout = 1.second,
    totalJobTimeout = 20.seconds,
    maximumResponseBytes = 1000000L,
    maximumRedirects = 3,
    maximumAttempts = 3,
    baseRetryDelay = 10.millis,
    maximumRetryDelay = 1.second,
    userAgent = "ConcurrencyTest/1"
  )

  private def waitForCompleted(id: ScrapeJobId, xa: Database): IO[Unit] = {
    def poll: IO[Unit] = xa.use(_.scrapeJobs.find(id)).flatMap {
      case Some(job) if job.status == ScrapeJobStatus.Succeeded => IO.unit
      case Some(job) if job.status == ScrapeJobStatus.Failed    =>
        IO.raiseError(AssertionError(s"Scrape failed: ${job.lastError}"))
      case _ => IO.sleep(10.millis) *> poll
    }
    poll.timeout(8.seconds)
  }

  test("a committed concurrent recipe deletion never produces successful update responses") {
    context.use { ctx =>
      for {
        meal <- ctx.api.createMeal(ctx.id, CreateMealInput(None, cookedAt)).flatMap(success)
        photo <- createPhoto(ctx, meal.id)
        reference <- ctx.api
          .createReference(ctx.id, CreateReferenceInput("book", None, Some("citation"), None))
          .flatMap(success)
        results <- locked(ctx.id).use { case (connection, repos) =>
          val writes = List(
            ctx.api
              .updateRecipe(ctx.id, UpdateRecipeInput(None, Some("deleted edit"), None))
              .map(_.void),
            ctx.api
              .updateMeal(ctx.id, meal.id, UpdateMealInput(Some("deleted meal edit"), None))
              .map(_.void),
            ctx.api
              .updateReference(
                ctx.id,
                reference.id,
                UpdateReferenceInput(None, None, Some("deleted label"))
              )
              .map(_.void),
            ctx.photoService
              .updateComment(ctx.id, meal.id, photo.id, UpdatePhotoInput(Some("deleted comment")))
              .map(_.void),
            ctx.api.selectPrimaryPhoto(ctx.id, photo.id).map(_.void)
          )
          repos.recipes.delete(ctx.id) *>
            writes.traverse(write => Resource.make(write.start)(_.cancel)).use { fibers =>
              (waitForBlocked(writes.size, ctx.xa) *>
                connection.commit *>
                fibers.traverse(_.joinWithNever)).guaranteeCase {
                case cats.effect.Outcome.Succeeded(_) => IO.unit
                case _                                => connection.rollback.void.attempt.void
              }
            }
        }
      } yield {
        assertEquals(
          results,
          List(
            Left(ApiError.NotFound("recipe")),
            Left(ApiError.NotFound("meal")),
            Left(ApiError.NotFound("reference")),
            Left(ApiError.NotFound("meal")),
            Left(ApiError.NotFound("recipe"))
          )
        )
      }
    }
  }

  private final case class Context(
      xa: Database,
      api: RecipeApiService,
      photoService: PhotoService,
      id: RecipeId
  )

  private val context: Resource[IO, Context] = for {
    xa <- database
    directory <- Resource.make(
      IO.blocking(Files.createTempDirectory("concurrent-photo-metadata-"))
    )(directory => IO.blocking(Files.delete(directory)))
    store <- Resource.eval(LocalPhotoStore.create(directory))
    api = RecipeApiService(xa, cleanup)
    photoService = PhotoService(xa, store, cleanup)
    recipe <- Resource.make(
      api
        .createRecipe(CreateRecipeInput(s"Concurrent ${UUID.randomUUID()}", None, None))
        .flatMap(success)
    )(recipe => xa.use(_.recipes.delete(recipe.id)).void)
  } yield Context(xa, api, photoService, recipe.id)

  private def createPhoto(ctx: Context, mealId: MealId): IO[Photo] = {
    val photo = Photo(
      PhotoId.random,
      mealId,
      StorageKey.random,
      "photo.png",
      "image/png",
      1,
      Some(1),
      Some(1),
      None,
      cookedAt,
      cookedAt
    )
    ctx.xa.use(_.photos.create(photo)).as(photo)
  }

  private def success[A](result: Either[ApiError, A]): IO[A] =
    IO.fromEither(result.leftMap(error => AssertionError(s"Unexpected API error: $error")))

  private def underLock[A](ctx: Context, writes: List[IO[Either[ApiError, A]]]): IO[Unit] =
    locked(ctx.id).use { case (connection, _) =>
      writes.traverse(write => Resource.make(write.start)(_.cancel)).use { fibers =>
        (waitForBlocked(writes.size, ctx.xa) *>
          connection.commit *>
          fibers.traverse_(_.joinWithNever.flatMap(success)))
          .guaranteeCase {
            case cats.effect.Outcome.Succeeded(_) => IO.unit
            case _                                => connection.rollback.void.attempt.void
          }
      }
    }

  private def locked(id: RecipeId): Resource[IO, (Transaction[IO], SkunkRepositories)] =
    database.flatMap(_.session).flatMap { session =>
      val repos = new SkunkRepositories(session)
      session.transaction.evalTap(_ => repos.recipes.findForUpdate(id)).map(_ -> repos)
    }

  private def waitForBlocked(count: Int, xa: Database): IO[Unit] = {
    val blocked = xa.session.use(_.execute(sql"""
      select query from pg_stat_activity
      where datname = current_database() and application_name = $text
        and wait_event_type = 'Lock'
    """.query(text))(databaseApplicationName))
    def poll: IO[Unit] = blocked.flatMap { queries =>
      if (queries.size == count) {
        IO {
          assert(
            queries.forall(query => query.contains("from recipes") && query.contains("for update")),
            s"Writers must acquire the recipe lock before touching dependent rows: $queries"
          )
        }
      } else { IO.sleep(10.millis) *> poll }
    }
    poll.timeout(8.seconds)
  }
}
