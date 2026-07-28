package cookingblog.scraping

import cats.effect.std.Random
import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import ciris.Secret
import cookingblog.config.{DatabaseConfig, ScrapeConfig}
import cookingblog.database.Database
import cookingblog.domain.*
import cookingblog.repository.DoobieRepositories
import doobie.Transactor
import doobie.implicits.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

final class ScrapeWorkerIntegrationSuite extends CatsEffectSuite {
  given Logger[IO] = NoOpLogger[IO]

  test("a durable pending job completes after the worker starts later") {
    database.use { transactor =>
      val ids = TestIds.create
      val exercise = for {
        timestamp <- Clock[IO].realTimeInstant
        _ <- setup(transactor, ids, timestamp)
        persistedBeforeStart <-
          DoobieRepositories.scrapeJobs.find(ids.jobId).transact(transactor)
        random <- Random.scalaUtilRandom[IO]
        worker =
          ScrapeWorker(
            transactor,
            config,
            successfulScraper,
            random
          )
        result <- worker.run.use { _ =>
          waitForTerminal(transactor, ids.jobId, 200).flatMap { completed =>
            (
              DoobieRepositories.scrapedDocuments
                .findByReference(ids.referenceId),
              DoobieRepositories.searchDocuments.find(ids.recipeId)
            ).tupled.transact(transactor).map { case (document, search) =>
              (completed, document, search)
            }
          }
        }
      } yield {
        assertEquals(
          persistedBeforeStart.map(_.status),
          Some(ScrapeJobStatus.Pending)
        )
        assertEquals(result._1.status, ScrapeJobStatus.Succeeded)
        assertEquals(result._1.attemptCount, 1)
        assert(result._2.exists(_.contentText.contains("glazed carrot")))
        assert(result._3.exists(_.plainText.contains("restart durable keyword")))
      }
      exercise.guarantee(deleteRecipes(transactor, List(ids.recipeId)))
    }
  }

  test("atomic claims are distinct and stale jobs recover safely") {
    database.use { transactor =>
      val first = TestIds.create
      val second = TestIds.create
      val exercise = for {
        timestamp <- Clock[IO].realTimeInstant
        _ <- setup(transactor, first, timestamp)
        _ <- setup(transactor, second, timestamp.plusMillis(1))
        claims <- (
          DoobieRepositories.scrapeJobs
            .claimNext(timestamp.plusSeconds(1))
            .transact(transactor),
          DoobieRepositories.scrapeJobs
            .claimNext(timestamp.plusSeconds(1))
            .transact(transactor)
        ).parTupled
        claimed = List(claims._1, claims._2).flatten
        recoveredCount <-
          DoobieRepositories.scrapeJobs
            .recoverStale(
              timestamp.plusSeconds(2),
              timestamp.plusSeconds(3),
              maximumAttempts = 3
            )
            .transact(transactor)
        recovered <- claimed.traverse(job =>
          DoobieRepositories.scrapeJobs.find(job.id).transact(transactor)
        )
      } yield {
        assertEquals(claimed.size, 2)
        assertEquals(claimed.map(_.id).distinct.size, 2)
        assert(claimed.forall(_.status == ScrapeJobStatus.Running))
        assertEquals(recoveredCount, 2)
        assert(
          recovered.flatten.forall(job =>
            job.status == ScrapeJobStatus.Pending && job.claimedAt.isEmpty
          )
        )
      }
      exercise.guarantee(
        deleteRecipes(transactor, List(first.recipeId, second.recipeId))
      )
    }
  }

  test("retryable failures are durably rescheduled before succeeding") {
    database.use { transactor =>
      val ids = TestIds.create
      val exercise = for {
        timestamp <- Clock[IO].realTimeInstant
        _ <- setup(transactor, ids, timestamp)
        attempts <- Ref.of[IO, Int](0)
        scraper = new PageScraper {
          override def scrape(url: String): IO[ScrapedPage] =
            attempts.getAndUpdate(_ + 1).flatMap {
              case 0 =>
                IO.raiseError(
                  ScrapeFailure("temporary upstream failure", retryable = true)
                )
              case _ => successfulScraper.scrape(url)
            }
        }
        random <- Random.scalaUtilRandom[IO]
        worker = ScrapeWorker(transactor, config, scraper, random)
        completed <- worker.run.use(_ => waitForTerminal(transactor, ids.jobId, 300))
      } yield {
        assertEquals(completed.status, ScrapeJobStatus.Succeeded)
        assertEquals(completed.attemptCount, 2)
        assertEquals(completed.lastError, None)
      }
      exercise.guarantee(deleteRecipes(transactor, List(ids.recipeId)))
    }
  }

  private def setup(
      transactor: Transactor[IO],
      ids: TestIds,
      timestamp: Instant
  ): IO[Unit] = {
    val recipe =
      Recipe(
        ids.recipeId,
        s"Scrape worker ${UUID.randomUUID()}",
        "",
        None,
        timestamp,
        timestamp,
        None
      )
    val reference =
      RecipeReference(
        ids.referenceId,
        ids.recipeId,
        ReferenceKind.Url,
        Some("https://www.seriouseats.com/sous-vide-glazed-carrots-recipe"),
        None,
        Some("Serious Eats"),
        timestamp,
        timestamp
      )
    val job =
      ScrapeJob(
        ids.jobId,
        ids.referenceId,
        ScrapeJobStatus.Pending,
        0,
        Instant.EPOCH,
        None,
        None,
        None,
        timestamp,
        timestamp
      )
    (
      DoobieRepositories.recipes.create(recipe) *>
        DoobieRepositories.searchDocuments.create(
          RecipeSearchDocument(ids.recipeId, recipe.title, "", timestamp)
        ) *>
        DoobieRepositories.references.create(reference) *>
        DoobieRepositories.scrapeJobs.create(job)
    ).transact(transactor)
  }

  private def waitForTerminal(
      transactor: Transactor[IO],
      jobId: ScrapeJobId,
      attemptsRemaining: Int
  ): IO[ScrapeJob] =
    DoobieRepositories.scrapeJobs.find(jobId).transact(transactor).flatMap {
      case Some(job)
          if job.status == ScrapeJobStatus.Succeeded ||
            job.status == ScrapeJobStatus.Failed =>
        IO.pure(job)
      case _ if attemptsRemaining > 0 =>
        IO.sleep(10.millis) *>
          waitForTerminal(transactor, jobId, attemptsRemaining - 1)
      case _ =>
        IO.raiseError(AssertionError("Scrape job did not reach a terminal state"))
    }

  private def deleteRecipes(
      transactor: Transactor[IO],
      recipeIds: List[RecipeId]
  ): IO[Unit] =
    recipeIds
      .traverse_(DoobieRepositories.recipes.delete)
      .transact(transactor)

  private val successfulScraper = new PageScraper {
    override def scrape(url: String): IO[ScrapedPage] =
      IO.pure(
        ScrapedPage(
          url,
          url,
          Some("Sous Vide Glazed Carrots Recipe"),
          "glazed carrot instructions with restart durable keyword",
          "0123456789abcdef",
          None,
          None
        )
      )
  }

  private val config =
    ScrapeConfig(
      enabled = true,
      workerCount = 1,
      perHostConcurrency = 1,
      pollInterval = 10.millis,
      staleJobTimeout = 1.minute,
      requestTimeout = 1.second,
      totalJobTimeout = 2.seconds,
      maximumResponseBytes = 1_000_000L,
      maximumRedirects = 3,
      maximumAttempts = 3,
      baseRetryDelay = 10.millis,
      maximumRetryDelay = 1.second,
      userAgent = "CookingBlogTest/1"
    )

  private val databaseConfig =
    DatabaseConfig(
      sys.env.getOrElse(
        "DATABASE_URL",
        "jdbc:postgresql://localhost:5432/cooking_blog"
      ),
      sys.env.getOrElse("DATABASE_USER", "cooking_blog"),
      Secret(sys.env.getOrElse("DATABASE_PASSWORD", "cooking_blog_dev")),
      poolSize = 2
    )

  private val database: Resource[IO, Transactor[IO]] =
    Resource.eval(Database.migrate(databaseConfig)) *>
      Database.transactor(databaseConfig)

  private final case class TestIds(
      recipeId: RecipeId,
      referenceId: ReferenceId,
      jobId: ScrapeJobId
  )

  private object TestIds {
    def create: TestIds =
      TestIds(RecipeId.random, ReferenceId.random, ScrapeJobId.random)
  }
}
