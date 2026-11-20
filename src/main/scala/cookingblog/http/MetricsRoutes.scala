package cookingblog.http

import cats.effect.IO
import cookingblog.observability.OperationalMetrics
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

/** Authenticated Prometheus-compatible operational metrics. */
final class MetricsRoutes(
    scrapeJobCounts: IO[Map[String, Long]],
    metrics: OperationalMetrics
) {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "metrics" =>
    scrapeJobCounts
      .flatMap(metrics.render)
      .flatMap(body =>
        Ok(body).map(
          _.putHeaders(
            Header.Raw(
              CIString("Content-Type"),
              "text/plain; version=0.0.4; charset=utf-8"
            ),
            Header.Raw(CIString("Cache-Control"), "no-store")
          )
        )
      )
  }
}
