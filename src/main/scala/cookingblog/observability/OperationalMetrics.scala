package cookingblog.observability

import cats.effect.{IO, Ref}

import scala.concurrent.duration.FiniteDuration

trait OperationalMetrics {
  def recordRequest(method: String, status: Int, duration: FiniteDuration): IO[Unit]
  def recordScrape(outcome: String, duration: FiniteDuration): IO[Unit]
  def recordPhotoProcessingFailure(reason: String): IO[Unit]
  def render(scrapeJobCounts: Map[String, Long]): IO[String]
}

object OperationalMetrics {
  private final case class RequestMetric(count: Long, durationSeconds: Double)
  private final case class ScrapeMetric(count: Long, durationSeconds: Double)
  private final case class State(
      requests: Map[(String, Int), RequestMetric],
      scrapes: Map[String, ScrapeMetric],
      photoFailures: Map[String, Long]
  )

  val noop: OperationalMetrics = new OperationalMetrics {
    override def recordRequest(
        method: String,
        status: Int,
        duration: FiniteDuration
    ): IO[Unit] = IO.unit

    override def recordScrape(outcome: String, duration: FiniteDuration): IO[Unit] =
      IO.unit

    override def recordPhotoProcessingFailure(reason: String): IO[Unit] = IO.unit

    override def render(scrapeJobCounts: Map[String, Long]): IO[String] =
      IO.pure("")
  }

  def create: IO[OperationalMetrics] =
    Ref
      .of[IO, State](State(Map.empty, Map.empty, Map.empty))
      .map(ref => LiveOperationalMetrics(ref))

  private final class LiveOperationalMetrics(ref: Ref[IO, State]) extends OperationalMetrics {
    override def recordRequest(
        method: String,
        status: Int,
        duration: FiniteDuration
    ): IO[Unit] =
      ref.update { state =>
        val key = method -> status
        val current = state.requests.getOrElse(key, RequestMetric(0L, 0d))
        state.copy(
          requests = state.requests.updated(
            key,
            current.copy(
              count = current.count + 1L,
              durationSeconds = current.durationSeconds + duration.toNanos.toDouble / 1_000_000_000d
            )
          )
        )
      }

    override def recordScrape(
        outcome: String,
        duration: FiniteDuration
    ): IO[Unit] =
      ref.update { state =>
        val current = state.scrapes.getOrElse(outcome, ScrapeMetric(0L, 0d))
        state.copy(
          scrapes = state.scrapes.updated(
            outcome,
            current.copy(
              count = current.count + 1L,
              durationSeconds = current.durationSeconds + duration.toNanos.toDouble / 1_000_000_000d
            )
          )
        )
      }

    override def recordPhotoProcessingFailure(reason: String): IO[Unit] =
      ref.update(state =>
        state.copy(
          photoFailures =
            state.photoFailures.updatedWith(reason)(count => Some(count.getOrElse(0L) + 1L))
        )
      )

    override def render(scrapeJobCounts: Map[String, Long]): IO[String] =
      ref.get.map { state =>
        val requestLines = state.requests.toList
          .sortBy { case ((method, status), _) => method -> status }
          .flatMap { case ((method, status), metric) =>
            List(
              s"""cooking_blog_http_requests_total{method="$method",status="$status"} ${metric.count}""",
              s"""cooking_blog_http_request_duration_seconds_sum{method="$method",status="$status"} ${metric.durationSeconds}""",
              s"""cooking_blog_http_request_duration_seconds_count{method="$method",status="$status"} ${metric.count}"""
            )
          }
        val scrapeLines = state.scrapes.toList.sortBy(_._1).flatMap { case (outcome, metric) =>
          List(
            s"""cooking_blog_scrape_attempts_total{outcome="$outcome"} ${metric.count}""",
            s"""cooking_blog_scrape_duration_seconds_sum{outcome="$outcome"} ${metric.durationSeconds}""",
            s"""cooking_blog_scrape_duration_seconds_count{outcome="$outcome"} ${metric.count}"""
          )
        }
        val photoLines = state.photoFailures.toList.sortBy(_._1).map { case (reason, count) =>
          s"""cooking_blog_photo_processing_failures_total{reason="$reason"} $count"""
        }
        val queueLines =
          List("pending", "running", "succeeded", "failed").map(status =>
            s"""cooking_blog_scrape_jobs{status="$status"} ${scrapeJobCounts
                .getOrElse(status, 0L)}"""
          )
        (
          List(
            "# HELP cooking_blog_http_requests_total Completed HTTP requests.",
            "# TYPE cooking_blog_http_requests_total counter",
            "# HELP cooking_blog_http_request_duration_seconds Request completion latency.",
            "# TYPE cooking_blog_http_request_duration_seconds summary",
            "# HELP cooking_blog_scrape_attempts_total Scrape attempts by outcome.",
            "# TYPE cooking_blog_scrape_attempts_total counter",
            "# HELP cooking_blog_scrape_duration_seconds Scrape attempt duration.",
            "# TYPE cooking_blog_scrape_duration_seconds summary",
            "# HELP cooking_blog_photo_processing_failures_total Photo processing failures.",
            "# TYPE cooking_blog_photo_processing_failures_total counter",
            "# HELP cooking_blog_scrape_jobs Durable scrape jobs by current status.",
            "# TYPE cooking_blog_scrape_jobs gauge"
          ) ++ requestLines ++ scrapeLines ++ photoLines ++ queueLines
        ).mkString("", "\n", "\n")
      }
  }

  private object LiveOperationalMetrics {
    def apply(ref: Ref[IO, State]): LiveOperationalMetrics =
      new LiveOperationalMetrics(ref)
  }
}
