package cookingblog.scraping

import cats.effect.IO
import cookingblog.config.ScrapeConfig
import munit.CatsEffectSuite
import org.http4s.ember.client.EmberClientBuilder

import scala.concurrent.duration.*

final class LiveScrapeSuite extends CatsEffectSuite {
  test("extracts the configured live recipe URL") {
    sys.env.get("LIVE_SCRAPE_URL") match {
      case None      => IO.unit
      case Some(url) =>
        EmberClientBuilder
          .default[IO]
          .withMaxTotal(1)
          .withMaxPerKey(_ => 1)
          .withTimeout(config.requestTimeout)
          .build
          .use { client =>
            val scraper =
              HttpPageScraper(
                SecurePageFetcher(
                  client,
                  config,
                  NetworkSafety(SystemHostResolver)
                )
              )
            scraper.scrape(url).map { page =>
              assert(page.title.exists(_.contains("Sous Vide Glazed Carrots")))
              assert(page.contentText.contains("carrot"))
              assert(page.contentText.contains("butter"))
              assert(page.contentText.length > 500)
            }
          }
    }
  }

  private val config =
    ScrapeConfig(
      enabled = true,
      workerCount = 1,
      perHostConcurrency = 1,
      pollInterval = 100.millis,
      staleJobTimeout = 1.minute,
      requestTimeout = 20.seconds,
      totalJobTimeout = 1.minute,
      maximumResponseBytes = 2_000_000L,
      maximumRedirects = 5,
      maximumAttempts = 1,
      baseRetryDelay = 1.second,
      maximumRetryDelay = 1.minute,
      userAgent = "CookingBlogLiveTest/0.1 (+personal recipe archive)"
    )
}
