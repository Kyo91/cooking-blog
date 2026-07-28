package cookingblog.scraping

import cats.effect.{IO, Resource}
import cats.effect.std.Random
import cookingblog.config.ScrapeConfig
import cookingblog.observability.OperationalMetrics
import doobie.Transactor
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.Logger

object Scraping {
  def resources(
      config: ScrapeConfig,
      transactor: Transactor[IO],
      metrics: OperationalMetrics
  )(using logger: Logger[IO]): Resource[IO, Unit] =
    if (config.enabled) {
      for {
        client <- EmberClientBuilder
          .default[IO]
          .withMaxTotal(config.workerCount)
          .withMaxPerKey(_ => config.perHostConcurrency)
          .withTimeout(config.requestTimeout)
          .withIdleConnectionTime(config.totalJobTimeout)
          .build
        random <- Resource.eval(Random.scalaUtilRandom[IO])
        pageFetcher =
          SecurePageFetcher(client, config, NetworkSafety(SystemHostResolver))
        scrapeWorker =
          ScrapeWorker(
            transactor,
            config,
            HttpPageScraper(pageFetcher),
            random,
            metrics
          )
        _ <- scrapeWorker.run
      } yield ()
    } else {
      Resource.eval(
        logger.info(
          "Recipe scraping is disabled; durable pending jobs will remain queued"
        )
      )
    }
}
