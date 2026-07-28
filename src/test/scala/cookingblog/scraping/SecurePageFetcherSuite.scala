package cookingblog.scraping

import cats.effect.IO
import cookingblog.config.ScrapeConfig
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}

import java.net.InetAddress
import scala.concurrent.duration.*

final class SecurePageFetcherSuite extends CatsEffectSuite {
  private val publicAddress = InetAddress.getByName("93.184.216.34")
  private val privateAddress = InetAddress.getByName("127.0.0.1")

  test("fetches bounded HTML and records response metadata") {
    val html =
      "<html><body><article><p>" + ("carrots " * 30) + "</p></article></body></html>"
    val app = HttpApp[IO](_ =>
      Ok(html)
        .map(
          _.putHeaders(
            `Content-Type`(MediaType.text.html),
            Header.Raw(org.typelevel.ci.CIString("ETag"), "\"version-1\"")
          )
        )
    )

    fetcher(app, _ => IO.pure(List(publicAddress)))
      .fetch(
        Uri.unsafeFromString("https://public.example/recipe")
      )
      .map { page =>
        assertEquals(page.body, html)
        assertEquals(page.etag, Some("\"version-1\""))
        assertEquals(page.resolvedUri.renderString, "https://public.example/recipe")
      }
  }

  test("validates every redirect destination before requesting it") {
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "start" =>
        Found(Location(Uri.unsafeFromString("http://blocked.example/secret")))
      case _ =>
        IO.raiseError(AssertionError("The blocked redirect was requested"))
    }
    val resolver = new HostResolver {
      override def resolve(host: String): IO[List[InetAddress]] =
        IO.pure(
          List(if (host == "blocked.example") privateAddress else publicAddress)
        )
    }

    fetcher(routes.orNotFound, resolver)
      .fetch(Uri.unsafeFromString("https://public.example/start"))
      .attempt
      .map(result =>
        assert(
          result.left.exists(_.getMessage.contains("non-public network"))
        )
      )
  }

  test("rejects responses over the configured byte limit") {
    val app = HttpApp[IO](_ =>
      Ok("x" * 101)
        .map(_.putHeaders(`Content-Type`(MediaType.text.html)))
    )

    fetcher(
      app,
      _ => IO.pure(List(publicAddress)),
      config.copy(maximumResponseBytes = 100L)
    ).fetch(Uri.unsafeFromString("https://public.example/large"))
      .attempt
      .map(result => assert(result.left.exists(_.getMessage.contains("exceeded"))))
  }

  private def fetcher(
      app: HttpApp[IO],
      resolver: HostResolver,
      scrapeConfig: ScrapeConfig = config
  ): SecurePageFetcher =
    SecurePageFetcher(
      Client.fromHttpApp(app),
      scrapeConfig,
      NetworkSafety(resolver)
    )

  private val config =
    ScrapeConfig(
      enabled = true,
      workerCount = 2,
      perHostConcurrency = 1,
      pollInterval = 10.millis,
      staleJobTimeout = 1.minute,
      requestTimeout = 1.second,
      totalJobTimeout = 2.seconds,
      maximumResponseBytes = 1000L,
      maximumRedirects = 3,
      maximumAttempts = 3,
      baseRetryDelay = 1.second,
      maximumRetryDelay = 1.minute,
      userAgent = "CookingBlogTest/1"
    )
}
