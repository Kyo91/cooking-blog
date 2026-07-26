package cookingblog.scraping

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.config.ScrapeConfig
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.Location
import org.typelevel.ci.CIString

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.concurrent.duration.FiniteDuration

final class SecurePageFetcher(
    client: Client[IO],
    config: ScrapeConfig,
    networkSafety: NetworkSafety
) {
  def fetch(uri: Uri): IO[FetchedPage] =
    fetchRedirect(uri, uri, config.maximumRedirects)

  private def fetchRedirect(
      requestedUri: Uri,
      uri: Uri,
      redirectsRemaining: Int
  ): IO[FetchedPage] =
    networkSafety.validate(uri) *>
      client
        .run(
          Request[IO](Method.GET, uri).putHeaders(
            Header.Raw(CIString("User-Agent"), config.userAgent),
            Header.Raw(
              CIString("Accept"),
              "text/html, application/xhtml+xml;q=0.9"
            )
          )
        )
        .use { response =>
          if (response.status.code >= 300 && response.status.code < 400) {
            response.headers.get[Location] match {
              case None =>
                IO.raiseError(
                  ScrapeFailure(
                    "The server returned a redirect without a destination",
                    retryable = true
                  )
                )
              case Some(_) if redirectsRemaining <= 0 =>
                IO.raiseError(
                  ScrapeFailure("The page exceeded the redirect limit", retryable = false)
                )
              case Some(location) =>
                resolve(uri, location.uri)
                  .flatMap(next => fetchRedirect(requestedUri, next, redirectsRemaining - 1))
            }
          } else if (!response.status.isSuccess) {
            IO.raiseError(
              ScrapeFailure(
                s"The remote server returned HTTP ${response.status.code}",
                retryable = response.status.code >= 500 || response.status.code == 429
              )
            )
          } else if (!htmlContentType(response)) {
            IO.raiseError(
              ScrapeFailure(
                "The remote response was not HTML",
                retryable = false
              )
            )
          } else {
            readBounded(response).map { body =>
              FetchedPage(
                requestedUri,
                uri,
                body,
                header(response, CIString("ETag")),
                header(response, CIString("Last-Modified"))
              )
            }
          }
        }
        .timeoutTo(config.requestTimeout, requestTimeout(config.requestTimeout))

  private def readBounded(response: Response[IO]): IO[String] =
    response.body
      .take(config.maximumResponseBytes + 1L)
      .compile
      .to(Array)
      .flatMap { bytes =>
        if (bytes.length.toLong > config.maximumResponseBytes) {
          IO.raiseError(
            ScrapeFailure(
              s"The remote response exceeded ${config.maximumResponseBytes} bytes",
              retryable = false
            )
          )
        } else {
          IO.pure(String(bytes, StandardCharsets.UTF_8))
        }
      }

  private def htmlContentType(response: Response[IO]): Boolean =
    response.contentType.exists { contentType =>
      val mediaType = contentType.mediaType
      (mediaType.mainType.equalsIgnoreCase("text") &&
        mediaType.subType.equalsIgnoreCase("html")) ||
      (mediaType.mainType.equalsIgnoreCase("application") &&
        mediaType.subType.equalsIgnoreCase("xhtml+xml"))
    }

  private def resolve(base: Uri, destination: Uri): IO[Uri] =
    IO.fromEither(
      Either
        .catchNonFatal(URI.create(base.renderString).resolve(destination.renderString))
        .leftMap(error =>
          ScrapeFailure(
            "The server returned an invalid redirect destination",
            retryable = false,
            Some(error)
          )
        )
        .flatMap(value =>
          Uri
            .fromString(value.toString)
            .leftMap(error =>
              ScrapeFailure(
                "The server returned an invalid redirect destination",
                retryable = false,
                Some(error)
              )
            )
        )
    )

  private def header(response: Response[IO], name: CIString): Option[String] =
    response.headers.headers.find(_.name == name).map(_.value)

  private def requestTimeout(duration: FiniteDuration): IO[Nothing] =
    IO.raiseError(
      ScrapeFailure(
        s"The remote request exceeded ${duration.toSeconds} seconds",
        retryable = true
      )
    )
}

final class HttpPageScraper(fetcher: SecurePageFetcher) extends PageScraper {
  override def scrape(url: String): IO[ScrapedPage] =
    IO.fromEither(
      Uri
        .fromString(url)
        .leftMap(error =>
          ScrapeFailure("The reference URL is invalid", retryable = false, Some(error))
        )
    ).flatMap { sourceUri =>
      fetcher.fetch(sourceUri).flatMap { mainPage =>
        IO.fromEither(ContentExtractor.extract(mainPage.body, mainPage.resolvedUri))
          .flatMap { mainContent =>
            preferredContent(mainPage, mainContent).map { case (page, content) =>
              ScrapedPage(
                sourceUri.renderString,
                page.resolvedUri.renderString,
                content.title,
                content.contentText,
                sha256(content.contentText),
                page.etag,
                page.lastModified
              )
            }
          }
      }
    }

  private def preferredContent(
      mainPage: FetchedPage,
      mainContent: ExtractedContent
  ): IO[(FetchedPage, ExtractedContent)] =
    mainContent.printUri.filter(_ != mainPage.resolvedUri) match {
      case None           => IO.pure((mainPage, mainContent))
      case Some(printUri) =>
        fetcher
          .fetch(printUri)
          .flatMap(page =>
            IO.fromEither(ContentExtractor.extract(page.body, page.resolvedUri))
              .map(content => (page, content))
          )
          .handleError(_ => (mainPage, mainContent))
    }

  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
}
