package cookingblog.scraping

import munit.FunSuite
import org.http4s.Uri

import scala.io.Source

final class ContentExtractorSuite extends FunSuite {
  private val baseUri =
    Uri.unsafeFromString(
      "https://www.seriouseats.com/sous-vide-glazed-carrots-recipe"
    )

  test("extracts Serious Eats-style Recipe JSON-LD") {
    val content = ContentExtractor.extract(fixture("serious-eats-sous-vide-carrots.html"), baseUri)

    assertEquals(content.map(_.title), Right(Some("Sous Vide Glazed Carrots Recipe")))
    assert(content.exists(_.contentText.contains("1 pound carrots")))
    assert(content.exists(_.contentText.contains("Preheat a water bath to 183°F")))
    assert(content.exists(_.contentText.contains("Instructions")))
  }

  test("discovers an absolute print page and falls back to article text") {
    val html =
      """
        |<html>
        |  <head>
        |    <title>Vegetable Stew</title>
        |    <link rel="alternate" media="print" href="/recipes/stew/print">
        |  </head>
        |  <body>
        |    <article>
        |      <h1>Vegetable Stew</h1>
        |      <p>This long introduction explains how the vegetables develop flavor.</p>
        |      <h2>Ingredients</h2>
        |      <ul><li>Carrots</li><li>Stock</li><li>Herbs</li></ul>
        |      <h2>Directions</h2>
        |      <p>Simmer all ingredients until tender, then season and serve immediately.</p>
        |    </article>
        |  </body>
        |</html>
        |""".stripMargin

    val content = ContentExtractor.extract(html, Uri.unsafeFromString("https://example.com/stew"))

    assertEquals(
      content.toOption.flatMap(_.printUri),
      Some(Uri.unsafeFromString("https://example.com/recipes/stew/print"))
    )
    assert(content.exists(_.contentText.contains("Simmer all ingredients")))
  }

  test("rejects malformed or content-free pages") {
    val html = "<html><body><script>secret()</script><p>short</p></body></html>"

    assert(ContentExtractor.extract(html, baseUri).isLeft)
  }

  private def fixture(name: String): String = {
    val source =
      Source.fromResource(s"scraping/$name", getClass.getClassLoader)
    try {
      source.mkString
    } finally {
      source.close()
    }
  }
}
