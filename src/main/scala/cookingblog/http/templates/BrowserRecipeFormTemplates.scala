package cookingblog.http.templates

import cookingblog.domain.{Meal, Recipe, RecipeReference, ReferenceKind}
import scalatags.Text.Frag
import scalatags.Text.all.*
import scalatags.Text.attrs.{id as htmlId}
import scalatags.Text.tags.{h2 as htmlH2}
import scalatags.Text.tags2.main

import java.time.Instant

private[templates] trait BrowserRecipeFormTemplates extends BrowserTemplateSupport {
  protected def sourceRow(reference: RecipeReference): Frag =
    div(
      cls := "source-row",
      select(
        name := "source_kind",
        option(value := "url", selected := (reference.kind == ReferenceKind.Url), "Recipe URL"),
        option(value := "book", selected := (reference.kind == ReferenceKind.Book), "Book citation")
      ),
      input(
        name := "source_url",
        tpe := "url",
        value := reference.url.getOrElse(""),
        placeholder := "https://example.com/recipe"
      ),
      input(
        name := "source_citation",
        value := reference.citation.getOrElse(""),
        placeholder := "Book title, author, page"
      ),
      button(cls := "remove-source", tpe := "button", "Remove source")
    )

  protected def recipeForm(
      recipe: Option[Recipe],
      titleValue: String,
      descriptionValue: String,
      keywords: String,
      sources: List[RecipeReference],
      csrfToken: String
  ): String = {
    val editing = recipe.nonEmpty
    val heading = if (editing) "Edit recipe" else "New recipe"
    val actionUrl = recipe.fold("/recipes")(value => s"/recipes/${id(value.id)}")
    val back = recipe.fold("/")(value => s"/recipes/${id(value.id)}")
    page(
      heading,
      main(
        cls := "form-page",
        a(href := back, "← Back"),
        h1(heading),
        form(
          method := "post",
          action := actionUrl,
          attr("data-recipe-form") := "true",
          attr("data-html-form") := "true",
          input(tpe := "hidden", name := "csrf_token", value := csrfToken),
          label(`for` := "title", "Title"),
          input(
            htmlId := "title",
            name := "title",
            required,
            maxlength := 200,
            aria.describedby := "form-errors",
            value := titleValue
          ),
          label(`for` := "description", "Description"),
          textarea(
            htmlId := "description",
            name := "description",
            maxlength := 10000,
            aria.describedby := "form-errors",
            descriptionValue
          ),
          label(`for` := "keywords", "Keywords"),
          input(
            htmlId := "keywords",
            name := "keywords",
            aria.describedby := "form-errors",
            value := keywords,
            placeholder := "sous vide, chicken, bbq"
          ),
          p(cls := "hint", "Separate keywords with commas. Multi-word keywords are kept together."),
          div(
            htmlId := "recipe-sources",
            aria.live := "polite",
            htmlH2("Sources"),
            p(cls := "hint", "Add one or more recipe URLs or book citations."),
            sources.map(sourceRow),
            button(htmlId := "add-recipe-source", tpe := "button", "Add source")
          ),
          p(htmlId := "form-errors", cls := "form-error", aria.live := "polite", role := "alert"),
          button(
            cls := "primary",
            tpe := "submit",
            if (editing) "Save changes" else "Create recipe"
          )
        )
      )
    )
  }

  protected def mealForm(recipe: Recipe, meal: Option[Meal], csrfToken: String): String = {
    val existing = meal.map(value => s"/recipes/${id(recipe.id)}/meals/${id(value.id)}")
    val targetUrl = existing.getOrElse(s"/recipes/${id(recipe.id)}/meals")
    val cookedAt = meal.fold(Instant.now())(_.cookedAt)
    page(
      if (meal.nonEmpty) "Edit meal" else "Cooked it",
      main(
        cls := "form-page",
        a(href := s"/recipes/${id(recipe.id)}", s"← ${recipe.title}"),
        h1(if (meal.nonEmpty) "Edit cooking entry" else "Record a cooking entry"),
        form(
          htmlId := "meal-form",
          method := "post",
          action := targetUrl,
          attr("data-meal-form") := "true",
          attr("data-recipe-id") := id(recipe.id),
          attr("data-meal-id") := meal.map(value => id(value.id)).getOrElse(""),
          input(tpe := "hidden", name := "csrf_token", value := csrfToken),
          label(`for` := "cooked-at", "When did you cook it?"),
          input(
            htmlId := "cooked-at",
            name := "cookedAt",
            tpe := "datetime-local",
            required,
            aria.describedby := "form-errors",
            value := localDateTime(cookedAt)
          ),
          label(`for` := "notes", "Notes"),
          textarea(
            htmlId := "notes",
            name := "notes",
            maxlength := 10000,
            aria.describedby := "form-errors",
            placeholder := "What worked? What would you change?",
            meal.map(_.notes).getOrElse("")
          ),
          label(`for` := "photos", "Photos"),
          input(
            htmlId := "photos",
            name := "photo",
            tpe := "file",
            accept := "image/jpeg,image/png,image/webp",
            aria.describedby := "form-errors upload-progress",
            multiple
          ),
          div(htmlId := "photo-previews", cls := "photo-previews", aria.live := "polite"),
          p(cls := "hint", "JPEG, PNG, or WebP, up to 10 MB each."),
          p(htmlId := "form-errors", cls := "form-error", aria.live := "polite"),
          p(htmlId := "upload-progress", cls := "muted", aria.live := "polite"),
          button(cls := "primary", tpe := "submit", "Save cooking entry")
        )
      )
    )
  }
}
