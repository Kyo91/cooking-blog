package cookingblog.scraping

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import org.http4s.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}

import scala.jdk.CollectionConverters.*

object ContentExtractor {
  private val MinimumContentLength = 80

  def extract(html: String, baseUri: Uri): Either[ScrapeFailure, ExtractedContent] = {
    val document = Jsoup.parse(html, baseUri.renderString)
    val printUri = discoverPrintUri(document)
    extractRecipeJsonLd(document)
      .orElse(extractMainContent(document))
      .filter(_.contentText.length >= MinimumContentLength)
      .map(_.copy(printUri = printUri))
      .toRight(
        ScrapeFailure(
          "The page did not contain enough readable recipe content",
          retryable = false
        )
      )
  }

  private def extractRecipeJsonLd(document: Document): Option[ExtractedContent] =
    document
      .select("script[type=application/ld+json]")
      .asScala
      .iterator
      .flatMap(script => parse(script.data()).toOption.iterator)
      .flatMap(allJsonValues)
      .flatMap(_.asObject.iterator)
      .find(isRecipe)
      .flatMap(recipeContent)

  private def allJsonValues(json: Json): Iterator[Json] =
    Iterator.single(json) ++
      json.asArray.iterator.flatMap(_.iterator).flatMap(allJsonValues) ++
      json.asObject.iterator
        .flatMap(_.values.iterator)
        .flatMap(allJsonValues)

  private def isRecipe(value: JsonObject): Boolean =
    value("@type").exists { json =>
      json.asString.exists(_.equalsIgnoreCase("Recipe")) ||
      json.asArray.exists(
        _.exists(_.asString.exists(_.equalsIgnoreCase("Recipe")))
      )
    }

  private def recipeContent(recipe: JsonObject): Option[ExtractedContent] = {
    val title = stringValue(recipe("name"))
    val description = stringValue(recipe("description")).toList
    val ingredients = stringValues(recipe("recipeIngredient"))
    val instructions =
      recipe("recipeInstructions").toList.flatMap(instructionValues)
    val sections =
      title.toList ++
        description ++
        section("Ingredients", ingredients) ++
        section("Instructions", instructions)
    val content = sections.mkString("\n")
    Option.when(content.nonEmpty)(ExtractedContent(title, content, None))
  }

  private def stringValue(json: Option[Json]): Option[String] =
    json.flatMap(_.asString).map(normalize).filter(_.nonEmpty)

  private def stringValues(json: Option[Json]): List[String] =
    json.toList.flatMap { value =>
      value.asArray
        .fold(stringValue(Some(value)).toList)(
          _.toList.flatMap(item => stringValue(Some(item)))
        )
    }

  private def instructionValues(json: Json): List[String] =
    json.asString.map(normalize).filter(_.nonEmpty).toList ++
      json.asArray.toList.flatMap(_.toList.flatMap(instructionValues)) ++
      json.asObject.toList.flatMap { value =>
        stringValue(value("text")).toList ++
          value("itemListElement").toList.flatMap(instructionValues)
      }

  private def section(name: String, values: List[String]): List[String] =
    Option.when(values.nonEmpty)(name :: values).toList.flatten

  private def extractMainContent(document: Document): Option[ExtractedContent] = {
    val candidate =
      List(
        document.selectFirst("article"),
        document.selectFirst("main"),
        document.selectFirst("[role=main]"),
        document.body()
      ).find(_ != null)
    candidate.map { element =>
      val copy = element.clone()
      copy
        .select(
          "script, style, noscript, iframe, svg, nav, footer, form, button, " +
            "[aria-hidden=true], .advertisement, .ad, .newsletter, .social-share"
        )
        .remove()
      ExtractedContent(
        Option(document.title()).map(normalize).filter(_.nonEmpty),
        blockText(copy),
        None
      )
    }
  }

  private def blockText(element: Element): String = {
    element
      .select("p, li, h1, h2, h3, h4, pre")
      .asScala
      .iterator
      .map(node => normalize(node.text()))
      .filter(_.nonEmpty)
      .toList
      .distinct
      .mkString("\n")
  }

  private def discoverPrintUri(document: Document): Option[Uri] = {
    val selectors =
      List(
        "link[href][rel~=alternate][media~=print]",
        "a[href][rel~=alternate][media~=print]",
        "a[href].print",
        "a[href][class*=print]",
        "a[href][id*=print]",
        "a[href][data-print-url]"
      )
    selectors.iterator
      .flatMap(selector => document.select(selector).asScala.iterator)
      .map(element =>
        Option(element.attr("abs:href"))
          .filter(_.nonEmpty)
          .orElse(Option(element.attr("data-print-url")).filter(_.nonEmpty))
      )
      .collectFirst { case Some(raw) => raw }
      .flatMap(Uri.fromString(_).toOption)
  }

  private def normalize(value: String): String =
    value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim
}
