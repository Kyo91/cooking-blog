package cookingblog.http.templates

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.domain.*
import cookingblog.http.pages.{BrowserRecipe, BrowserReference}
import cookingblog.repository.DoobieRepositories
import cookingblog.service.RecipeApiService
import doobie.Transactor
import doobie.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import scalatags.Text.Frag
import scalatags.Text.all.*
import scalatags.Text.attrs.{id as htmlId}
import scalatags.Text.tags.{h2 as htmlH2}
import scalatags.Text.tags2.{article, details, main, section, summary as detailsSummary}

private[templates] trait BrowserRecipeDetailTemplates extends BrowserRecipeFormTemplates {
  protected def recipeService: RecipeApiService
  protected def transactor: Transactor[IO]

  protected def recipeEditPage(id: RecipeId, csrfToken: String): IO[Response[IO]] =
    (recipeService.getRecipe(id), recipeKeywords(id), recipeReferences(id)).mapN {
      case (Right(recipe), keywords, references) =>
        Ok(
          recipeForm(
            Some(recipe),
            recipe.title,
            recipe.description,
            keywords.mkString(", "),
            references,
            csrfToken
          ),
          `Content-Type`(MediaType.text.html)
        )
      case _ => notFoundPage
    }.flatten

  protected def recipeDetailPage(id: RecipeId, csrfToken: String): IO[Response[IO]] =
    recipeDetail(id).flatMap {
      case None         => notFoundPage
      case Some(detail) => Ok(detailPage(detail, csrfToken), `Content-Type`(MediaType.text.html))
    }

  private def recipeDetail(id: RecipeId): IO[Option[BrowserRecipe]] = {
    import DoobieRepositories.*
    (for {
      recipe <- recipes.find(id)
      result <- recipe.traverse { value =>
        (
          meals.listByRecipe(id),
          references.listByRecipe(id),
          photos.listByRecipe(id),
          keywords.listByRecipe(id)
        ).tupled.flatMap { case (mealRows, referenceRows, photoRows, keywordRows) =>
          referenceRows
            .traverse(reference =>
              (
                scrapeJobs.findLatestByReference(reference.id),
                scrapedDocuments.findByReference(reference.id)
              ).tupled.map(BrowserReference(reference, _, _))
            )
            .map(rows =>
              BrowserRecipe(value, mealRows, photoRows, keywordRows.map(_.keyword), rows)
            )
        }
      }
    } yield result).transact(transactor)
  }

  private def recipeKeywords(id: RecipeId): IO[List[String]] =
    DoobieRepositories.keywords.listByRecipe(id).map(_.map(_.keyword)).transact(transactor)

  private def recipeReferences(id: RecipeId): IO[List[RecipeReference]] =
    DoobieRepositories.references.listByRecipe(id).transact(transactor)

  private def detailPage(detail: BrowserRecipe, csrfToken: String): String = {
    val recipe = detail.recipe
    val primary = img(
      cls := "hero-photo",
      src := s"/media/recipes/${id(recipe.id)}/primary?variant=display",
      alt := s"Photo of ${recipe.title}"
    )
    val keywords =
      if (detail.keywords.isEmpty) frag()
      else ul(cls := "chips", detail.keywords.map(value => li(value)))
    val references =
      if (detail.references.isEmpty) p(cls := "muted", "No sources yet.")
      else frag(detail.references.map(referenceView(_, recipe.id, csrfToken)))
    val meals =
      if (detail.meals.isEmpty) div(cls := "empty-state", p("No cooking entries yet."))
      else frag(detail.meals.map(mealView(_, detail.photos, csrfToken)))
    page(
      recipe.title,
      main(
        a(href := "/", "← All recipes"),
        div(
          cls := "detail-heading",
          div(h1(recipe.title), p(recipe.description), keywords),
          div(
            cls := "actions",
            a(cls := "button", href := s"/recipes/${id(recipe.id)}/edit", "Edit recipe"),
            a(
              cls := "button primary",
              href := s"/recipes/${id(recipe.id)}/meals/new",
              "Record meal"
            ),
            confirmationForm(
              s"/recipes/${id(recipe.id)}/delete",
              csrfToken,
              "Permanently delete this recipe and all of its cooking history?",
              "Delete recipe"
            )
          )
        ),
        primary,
        section(
          htmlH2("Sources and imports"),
          div(htmlId := "references", references),
          form(
            method := "post",
            action := s"/recipes/${id(recipe.id)}/references",
            cls := "reference-form",
            attr("data-html-form") := "true",
            input(tpe := "hidden", name := "csrf_token", value := csrfToken),
            label(`for` := "reference-kind", "Add a source"),
            select(
              htmlId := "reference-kind",
              name := "kind",
              option(value := "url", "Recipe URL"),
              option(value := "book", "Book citation")
            ),
            div(
              cls := "inline-form",
              input(
                htmlId := "reference-url",
                name := "url",
                tpe := "url",
                aria.describedby := "reference-form-errors",
                placeholder := "https://example.com/recipe"
              ),
              input(
                htmlId := "reference-citation",
                name := "citation",
                aria.describedby := "reference-form-errors",
                placeholder := "Book title, author, page",
                hidden
              ),
              button(tpe := "submit", "Add source")
            ),
            p(
              cls := "hint",
              "URL imports begin in the background. Book citations are saved as written."
            ),
            p(
              htmlId := "reference-form-errors",
              cls := "form-error",
              aria.live := "polite",
              role := "alert"
            )
          )
        ),
        section(htmlH2("Cooking history"), meals)
      )
    )
  }

  private def referenceView(
      value: BrowserReference,
      recipeId: RecipeId,
      csrfToken: String
  ): Frag = {
    val reference = value.reference
    val displayLabel =
      reference.displayName.orElse(reference.url).orElse(reference.citation).getOrElse("Reference")
    val importInfo = reference.kind match {
      case ReferenceKind.Book =>
        reference.citation.fold(frag())(citation => p(citation))
      case ReferenceKind.Url =>
        val status = value.job.map(_.status).fold("pending") {
          case ScrapeJobStatus.Succeeded => "complete"
          case other                     => other.databaseValue
        }
        val content = value.document.fold(frag())(document =>
          details(detailsSummary("Imported text"), p(document.contentText))
        )
        val statusLabel =
          if (status == "pending" || status == "running") {
            span(
              cls := "status",
              aria.live := "polite",
              attr("data-import-active") := "true",
              status
            )
          } else {
            span(cls := "status", aria.live := "polite", status)
          }
        frag(
          p(statusLabel, " ", value.job.flatMap(_.lastError).getOrElse("")),
          content
        )
    }
    val endpoint = s"/recipes/${id(recipeId)}/references/${id(reference.id)}"
    val retry = Option.when(reference.kind == ReferenceKind.Url)(
      confirmationForm(s"$endpoint/scrape", csrfToken, "Retry this import now?", "Retry import")
    )
    val field = reference.kind match {
      case ReferenceKind.Url =>
        input(
          htmlId := s"reference-${id(reference.id)}",
          name := "url",
          tpe := "url",
          aria.describedby := s"reference-errors-${id(reference.id)}",
          scalatags.Text.attrs.value := reference.url.getOrElse(""),
          required
        )
      case ReferenceKind.Book =>
        input(
          htmlId := s"reference-${id(reference.id)}",
          name := "citation",
          aria.describedby := s"reference-errors-${id(reference.id)}",
          scalatags.Text.attrs.value := reference.citation.getOrElse(""),
          required
        )
    }
    article(
      cls := "reference",
      h3(displayLabel),
      importInfo,
      form(
        method := "post",
        action := endpoint,
        attr("data-html-form") := "true",
        input(tpe := "hidden", name := "csrf_token", scalatags.Text.attrs.value := csrfToken),
        label(
          `for` := s"reference-${id(reference.id)}",
          if (reference.kind == ReferenceKind.Url) "Recipe URL" else "Book citation"
        ),
        field,
        p(
          htmlId := s"reference-errors-${id(reference.id)}",
          cls := "form-error",
          aria.live := "polite",
          role := "alert"
        ),
        button(tpe := "submit", "Save source")
      ),
      retry,
      confirmationForm(
        s"$endpoint/delete",
        csrfToken,
        "Permanently delete this source?",
        "Delete source"
      )
    )
  }

  private def mealView(meal: Meal, photos: List[Photo], csrfToken: String): Frag = {
    val mealPhotos = photos
      .filter(_.mealId == meal.id)
      .map { photo =>
        figure(
          img(
            src := s"/media/${id(photo.id)}?variant=thumbnail",
            alt := photo.comment.getOrElse(s"Photo from ${date(meal.cookedAt)}")
          ),
          figcaption(
            form(
              method := "post",
              action := s"/recipes/${id(meal.recipeId)}/meals/${id(meal.id)}/photos/${id(photo.id)}",
              attr("data-html-form") := "true",
              input(tpe := "hidden", name := "csrf_token", value := csrfToken),
              label(`for` := s"caption-${id(photo.id)}", "Caption"),
              input(
                htmlId := s"caption-${id(photo.id)}",
                name := "comment",
                aria.describedby := s"caption-errors-${id(photo.id)}",
                value := photo.comment.getOrElse(""),
                maxlength := 1000
              ),
              p(
                htmlId := s"caption-errors-${id(photo.id)}",
                cls := "form-error",
                aria.live := "polite",
                role := "alert"
              ),
              button(tpe := "submit", "Save caption")
            )
          ),
          div(
            cls := "photo-actions",
            confirmationForm(
              s"/recipes/${id(meal.recipeId)}/primary-photo/${id(photo.id)}",
              csrfToken,
              "Use this photo as the recipe's primary photo?",
              "Use as primary"
            ),
            confirmationForm(
              s"/recipes/${id(meal.recipeId)}/meals/${id(meal.id)}/photos/${id(photo.id)}/delete",
              csrfToken,
              "Permanently delete this photo?",
              "Delete photo"
            )
          )
        )
      }
    article(
      cls := "meal",
      div(
        h3(date(meal.cookedAt)),
        div(
          a(href := s"/recipes/${id(meal.recipeId)}/meals/${id(meal.id)}/edit", "Edit"),
          confirmationForm(
            s"/recipes/${id(meal.recipeId)}/meals/${id(meal.id)}/delete",
            csrfToken,
            "Permanently delete this cooking entry and its photos?",
            "Delete meal"
          )
        )
      ),
      p(meal.notes),
      div(cls := "meal-photos", mealPhotos)
    )
  }
}
