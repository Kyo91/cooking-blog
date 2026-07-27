package cookingblog.scraping

import cats.effect.std.Random
import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import cookingblog.config.ScrapeConfig
import cookingblog.domain.*
import cookingblog.observability.OperationalMetrics
import cookingblog.repository.*
import doobie.*
import doobie.implicits.*
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*

/** Supervised durable-job worker for recipe imports.
  *
  * Multiple workers claim jobs safely through the repository; this class owns retries, stale-job
  * recovery, and the atomic document/search/job completion transition.
  */
final class ScrapeWorker(
    transactor: Transactor[IO],
    config: ScrapeConfig,
    scraper: PageScraper,
    random: Random[IO],
    references: RecipeReferenceRepository[ConnectionIO],
    scrapedDocuments: ScrapedDocumentRepository[ConnectionIO],
    jobs: ScrapeJobRepository[ConnectionIO],
    searchDocuments: RecipeSearchDocumentRepository[ConnectionIO],
    metrics: OperationalMetrics
)(using logger: Logger[IO]) {

  /** Starts the bounded worker pool and stale-job recovery loop, cancelling all fibers on release.
    */
  def run: Resource[IO, Unit] = {
    val workers = List.range(1, config.workerCount + 1).map(workerLoop)
    val processes = recoveryLoop :: workers
    Resource
      .make(processes.traverse(_.start))(_.traverse_(_.cancel))
      .void
  }

  private def workerLoop(workerNumber: Int): IO[Unit] =
    (
      claim
        .flatMap {
          case None      => IO.sleep(config.pollInterval)
          case Some(job) => process(workerNumber, job)
        }
        .handleErrorWith(error =>
          logger.error(error)(s"Scrape worker $workerNumber failed while polling") *>
            IO.sleep(config.pollInterval)
        )
      )
      .foreverM

  private def claim: IO[Option[ScrapeJob]] =
    Clock[IO].realTimeInstant.flatMap(timestamp => jobs.claimNext(timestamp).transact(transactor))

  /** Runs one claimed job under the total-job timeout and maps expected failures to queue state. */
  private def process(workerNumber: Int, job: ScrapeJob): IO[Unit] = {
    Clock[IO].monotonic.flatMap { startedAt =>
      val work =
        references.find(job.referenceId).transact(transactor).flatMap {
          case None =>
            handleFailure(
              workerNumber,
              job,
              ScrapeFailure("The URL reference no longer exists", retryable = false),
              startedAt
            )
          case Some(reference) =>
            reference.url match {
              case None =>
                handleFailure(
                  workerNumber,
                  job,
                  ScrapeFailure(
                    "The URL reference did not contain a URL",
                    retryable = false
                  ),
                  startedAt
                )
              case Some(url) =>
                logger.info(
                  s"Scrape worker $workerNumber started job ${ScrapeJobId.value(job.id)} " +
                    s"attempt ${job.attemptCount}"
                ) *>
                  scraper.scrape(url).attempt.flatMap {
                    case Right(page) =>
                      complete(workerNumber, job, reference, page, startedAt)
                    case Left(failure: ScrapeFailure) =>
                      handleFailure(workerNumber, job, failure, startedAt)
                    case Left(error) =>
                      handleFailure(
                        workerNumber,
                        job,
                        ScrapeFailure(
                          "An unexpected scraping failure occurred",
                          retryable = true,
                          Some(error)
                        ),
                        startedAt
                      )
                  }
            }
        }

      work.timeoutTo(
        config.totalJobTimeout,
        handleFailure(
          workerNumber,
          job,
          ScrapeFailure(
            s"The scrape job exceeded ${config.totalJobTimeout.toSeconds} seconds",
            retryable = true
          ),
          startedAt
        )
      )
    }
  }

  /** Commits document upsert, search rebuild, and successful job state as one database transaction.
    */
  private def complete(
      workerNumber: Int,
      job: ScrapeJob,
      reference: RecipeReference,
      page: ScrapedPage,
      startedAt: FiniteDuration
  ): IO[Unit] =
    Clock[IO].realTimeInstant.flatMap { timestamp =>
      val document =
        ScrapedDocument(
          ScrapedDocumentId.random,
          reference.id,
          page.sourceUrl,
          Some(page.resolvedUrl),
          page.title,
          page.contentText,
          page.contentHash,
          page.etag,
          page.lastModified,
          timestamp,
          timestamp
        )
      val succeeded =
        job.copy(
          status = ScrapeJobStatus.Succeeded,
          finishedAt = Some(timestamp),
          lastError = None,
          updatedAt = timestamp
        )
      val program =
        scrapedDocuments.findByReference(reference.id).flatMap {
          case None           => scrapedDocuments.create(document)
          case Some(existing) =>
            scrapedDocuments.update(document.copy(id = existing.id)).void
        } *>
          searchDocuments.rebuildSearchDocument(reference.recipeId, timestamp) *>
          jobs.update(succeeded).flatMap {
            case true  => ().pure[ConnectionIO]
            case false =>
              IllegalStateException(
                "Claimed scrape job disappeared before completion"
              ).raiseError[ConnectionIO, Unit]
          }
      program.transact(transactor) *>
        logger.info(
          s"Scrape worker $workerNumber completed job ${ScrapeJobId.value(job.id)}"
        ) *> recordScrape("succeeded", startedAt)
    }

  /** Records a bounded error, choosing terminal failure or jittered retry from the failure policy.
    */
  private def handleFailure(
      workerNumber: Int,
      job: ScrapeJob,
      failure: ScrapeFailure,
      startedAt: FiniteDuration
  ): IO[Unit] =
    Clock[IO].realTimeInstant.flatMap { timestamp =>
      val message = boundedMessage(failure.message)
      val terminal = !failure.retryable || job.attemptCount >= config.maximumAttempts
      val update: IO[Boolean] =
        if (terminal) {
          jobs
            .update(
              job.copy(
                status = ScrapeJobStatus.Failed,
                finishedAt = Some(timestamp),
                lastError = Some(message),
                updatedAt = timestamp
              )
            )
            .transact(transactor)
        } else {
          random.nextDouble.flatMap { jitter =>
            val delay =
              RetryBackoff.delay(
                job.attemptCount,
                config.baseRetryDelay,
                config.maximumRetryDelay,
                jitter
              )
            jobs
              .update(
                job.copy(
                  status = ScrapeJobStatus.Pending,
                  availableAt = timestamp.plusMillis(delay.toMillis),
                  claimedAt = None,
                  finishedAt = None,
                  lastError = Some(message),
                  updatedAt = timestamp
                )
              )
              .transact(transactor)
          }
        }
      update.void *>
        logger.warn(
          s"Scrape worker $workerNumber ${if (terminal) "failed" else "rescheduled"} " +
            s"job ${ScrapeJobId.value(job.id)} after attempt ${job.attemptCount}: $message"
        ) *> recordScrape(if (terminal) "failed" else "retry", startedAt)
    }

  private def recoveryLoop: IO[Unit] =
    (
      recoverStale.handleErrorWith(error =>
        logger.error(error)("Failed to recover stale scrape jobs")
      ) *> IO.sleep(config.staleJobTimeout.min(1.minute))
    ).foreverM

  private def recoverStale: IO[Unit] =
    Clock[IO].realTimeInstant.flatMap { timestamp =>
      val claimedBefore = timestamp.minusMillis(config.staleJobTimeout.toMillis)
      jobs
        .recoverStale(claimedBefore, timestamp, config.maximumAttempts)
        .transact(transactor)
        .flatMap { count =>
          logger.info(s"Recovered $count stale scrape jobs").whenA(count > 0)
        }
    }

  private def boundedMessage(message: String): String =
    Option(message)
      .map(_.replaceAll("\\s+", " ").trim)
      .filter(_.nonEmpty)
      .getOrElse("Scraping failed")
      .take(1000)

  private def recordScrape(
      outcome: String,
      startedAt: FiniteDuration
  ): IO[Unit] =
    Clock[IO].monotonic.flatMap(finishedAt => metrics.recordScrape(outcome, finishedAt - startedAt))
}

object ScrapeWorker {
  def apply(
      transactor: Transactor[IO],
      config: ScrapeConfig,
      scraper: PageScraper,
      random: Random[IO]
  )(using Logger[IO]): ScrapeWorker =
    new ScrapeWorker(
      transactor,
      config,
      scraper,
      random,
      DoobieRepositories.references,
      DoobieRepositories.scrapedDocuments,
      DoobieRepositories.scrapeJobs,
      DoobieRepositories.searchDocuments,
      OperationalMetrics.noop
    )

  def apply(
      transactor: Transactor[IO],
      config: ScrapeConfig,
      scraper: PageScraper,
      random: Random[IO],
      metrics: OperationalMetrics
  )(using Logger[IO]): ScrapeWorker =
    new ScrapeWorker(
      transactor,
      config,
      scraper,
      random,
      DoobieRepositories.references,
      DoobieRepositories.scrapedDocuments,
      DoobieRepositories.scrapeJobs,
      DoobieRepositories.searchDocuments,
      metrics
    )
}

object RetryBackoff {

  /** Returns bounded exponential retry delay with deterministic caller-supplied jitter. */
  def delay(
      attempt: Int,
      base: FiniteDuration,
      maximum: FiniteDuration,
      jitter: Double
  ): FiniteDuration = {
    val exponent = math.max(0, attempt - 1)
    val multiplier = BigInt(2).pow(math.min(exponent, 30))
    val uncappedMillis = BigInt(base.toMillis) * multiplier
    val jitterFactor = 0.5d + math.max(0d, math.min(1d, jitter))
    val jitteredMillis = (uncappedMillis.toDouble * jitterFactor).toLong
    math.max(1L, math.min(maximum.toMillis, jitteredMillis)).millis
  }
}
