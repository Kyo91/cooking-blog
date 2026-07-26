package cookingblog.scraping

import org.http4s.Uri

final case class ScrapeFailure(
    message: String,
    retryable: Boolean,
    cause: Option[Throwable] = None
) extends RuntimeException(message, cause.orNull)

final case class FetchedPage(
    requestedUri: Uri,
    resolvedUri: Uri,
    body: String,
    etag: Option[String],
    lastModified: Option[String]
)

final case class ExtractedContent(
    title: Option[String],
    contentText: String,
    printUri: Option[Uri]
)

final case class ScrapedPage(
    sourceUrl: String,
    resolvedUrl: String,
    title: Option[String],
    contentText: String,
    contentHash: String,
    etag: Option[String],
    lastModified: Option[String]
)

trait PageScraper {
  def scrape(url: String): cats.effect.IO[ScrapedPage]
}
