package cookingblog.http

import cookingblog.database.Database
import cats.effect.IO
import cookingblog.observability.OperationalMetrics
import skunk.codec.all.{text, int8}
import skunk.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

/** Authenticated Prometheus-compatible operational metrics. */
final class MetricsRoutes(
    pool: Database,
    metrics: OperationalMetrics
) {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "metrics" =>
    pool.session
      .use(
        _.execute(
          sql"""
        select status, count(*)
        from scrape_jobs
        group by status
      """
            .query(text *: int8)
        )
      )
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
