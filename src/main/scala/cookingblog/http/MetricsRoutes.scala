package cookingblog.http

import cats.effect.IO
import cookingblog.observability.OperationalMetrics
import doobie.Transactor
import doobie.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

/** Authenticated Prometheus-compatible operational metrics. */
final class MetricsRoutes(
    transactor: Transactor[IO],
    metrics: OperationalMetrics
) {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "metrics" =>
    sql"""
        select status, count(*)
        from scrape_jobs
        group by status
      """
      .query[(String, Long)]
      .to[List]
      .transact(transactor)
      .flatMap(counts => metrics.render(counts.toMap))
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
