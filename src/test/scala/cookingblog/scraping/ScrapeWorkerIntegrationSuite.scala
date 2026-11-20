package cookingblog.scraping

import cookingblog.database.Database
import cats.effect.std.Random
import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*
import cookingblog.config.ScrapeConfig
import cookingblog.domain.*
import cookingblog.testing.PostgresIntegrationSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

final class ScrapeWorkerIntegrationSuite extends PostgresIntegrationSuite {
  given Logger[IO] = NoOpLogger[IO]

  test("a durable pending job completes after the worker starts later") {
    database.use { pool =>
      val ids = TestIds.create
      val exercise = for {
        timestamp <- Clock[IO].realTimeInstant
        _ <- setup(pool, ids, timestamp)
        persistedBeforeStart <-
          pool.use(_.scrapeJobs.find(ids.jobId))
        random <- Random.scalaUtilRandom[IO]
        worker =
          ScrapeWorker(
            pool,
            config,
            successfulScraper,
            random
          )
        result <- worker.run.use { _ =>
          waitForTerminal(pool, ids.jobId, 200).flatMap { completed =>
            pool
              .transaction { repos =>
                (
                  repos.scrapedDocuments
                    .findByReference(ids.referenceId),
                  repos.searchDocuments.find(ids.recipeId)
                ).tupled
              }
              .map { case (document, search) =>
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
      exercise.guarantee(deleteRecipes(pool, List(ids.recipeId)))
    }
  }

  test("atomic claims are distinct and stale jobs recover safely") {
    database.use { pool =>
      val first = TestIds.create
      val second = TestIds.create
      val exercise = for {
        timestamp <- Clock[IO].realTimeInstant
        _ <- setup(pool, first, timestamp)
        _ <- setup(pool, second, timestamp.plusMillis(1))
        claims <- (
          pool.use(_.scrapeJobs.claimNext(timestamp.plusSeconds(1))),
          pool.use(_.scrapeJobs.claimNext(timestamp.plusSeconds(1)))
        ).parTupled
        claimed = List(claims._1, claims._2).flatten
        recoveredCount <-
          pool.use(
            _.scrapeJobs.recoverStale(
              timestamp.plusSeconds(2),
              timestamp.plusSeconds(3),
              maximumAttempts = 3
            )
          )
        recovered <- claimed.traverse(job => pool.use(_.scrapeJobs.find(job.id)))
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
        deleteRecipes(pool, List(first.recipeId, second.recipeId))
      )
    }
  }

  test("retryable failures are durably rescheduled before succeeding") {
    database.use { pool =>
      val ids = TestIds.create
      val exercise = for {
        timestamp <- Clock[IO].realTimeInstant
        _ <- setup(pool, ids, timestamp)
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
        worker = ScrapeWorker(pool, config, scraper, random)
        completed <- worker.run.use(_ => waitForTerminal(pool, ids.jobId, 300))
      } yield {
        assertEquals(completed.status, ScrapeJobStatus.Succeeded)
        assertEquals(completed.attemptCount, 2)
        assertEquals(completed.lastError, None)
      }
      exercise.guarantee(deleteRecipes(pool, List(ids.recipeId)))
    }
  }

  private def setup(
      pool: Database,
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
    pool.transaction { repos =>
      repos.recipes.create(recipe) *>
        repos.searchDocuments.create(
          RecipeSearchDocument(ids.recipeId, recipe.title, "", timestamp)
        ) *>
        repos.references.create(reference) *>
        repos.scrapeJobs.create(job)
    }
  }

  private def waitForTerminal(
      pool: Database,
      jobId: ScrapeJobId,
      attemptsRemaining: Int
  ): IO[ScrapeJob] =
    pool.use(_.scrapeJobs.find(jobId)).flatMap {
      case Some(job)
          if job.status == ScrapeJobStatus.Succeeded ||
            job.status == ScrapeJobStatus.Failed =>
        IO.pure(job)
      case _ if attemptsRemaining > 0 =>
        IO.sleep(10.millis) *>
          waitForTerminal(pool, jobId, attemptsRemaining - 1)
      case _ =>
        IO.raiseError(AssertionError("Scrape job did not reach a terminal state"))
    }

  private def deleteRecipes(
      pool: Database,
      recipeIds: List[RecipeId]
  ): IO[Unit] =
    pool.transaction(repos => recipeIds.traverse_(repos.recipes.delete))

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
