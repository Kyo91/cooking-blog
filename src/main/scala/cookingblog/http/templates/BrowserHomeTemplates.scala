package cookingblog.http.templates

import cats.effect.IO
import cookingblog.auth.AuthenticatedSession
import cookingblog.domain.Recipe
import cookingblog.service.{RecipeApiService, RecipeSort}
import scalatags.Text.Frag
import scalatags.Text.all.*
import scalatags.Text.attrs.{id as htmlId}
import scalatags.Text.tags.{h2 as htmlH2}
import scalatags.Text.tags2.{article, main, section}

private[templates] trait BrowserHomeTemplates extends BrowserTemplateSupport {
  protected def recipeService: RecipeApiService

  protected def renderHome(
      query: String,
      sort: RecipeSort,
      session: AuthenticatedSession
  ): IO[String] =
    recipeService
      .listRecipes(Option(query).filter(_.trim.nonEmpty), sort, 50, None)
      .map {
        case Right(results) =>
          page(
            "Recipes",
            nav(session),
            main(
              div(
                cls := "page-heading",
                div(h1("Recipes"), p("Find something you have made, then capture the next time.")),
                a(
                  cls := "button primary",
                  htmlId := "new-recipe",
                  href := "/recipes/new",
                  aria.label := "Create a new recipe",
                  "+"
                )
              ),
              label(`for` := "recipe-search", "Search recipes"),
              input(
                htmlId := "recipe-search",
                name := "q",
                tpe := "search",
                value := query,
                placeholder := "grilled chicken, weeknight, sous vide",
                autocomplete := "off",
                attr("hx-get") := "/recipes/search",
                attr("hx-trigger") := "input changed delay:250ms",
                attr("hx-target") := "#recipe-results",
                attr("hx-swap") := "innerHTML",
                attr("hx-include") := "#recipe-sort",
                autofocus
              ),
              label(`for` := "recipe-sort", "Order recipes by"),
              select(
                htmlId := "recipe-sort",
                name := "sort",
                attr("hx-get") := "/recipes/search",
                attr("hx-trigger") := "change",
                attr("hx-target") := "#recipe-results",
                attr("hx-swap") := "innerHTML",
                attr("hx-include") := "#recipe-search",
                option(
                  value := RecipeSort.Recent.value,
                  selected := (sort == RecipeSort.Recent),
                  "Most recently cooked"
                ),
                option(
                  value := RecipeSort.Updated.value,
                  selected := (sort == RecipeSort.Updated),
                  "Last updated"
                ),
                option(
                  value := RecipeSort.Title.value,
                  selected := (sort == RecipeSort.Title),
                  "Title"
                )
              ),
              p(htmlId := "search-status", cls := "muted", aria.live := "polite"),
              section(
                htmlId := "recipe-results",
                cls := "recipe-grid",
                recipeCards(results.items, query)
              )
            )
          )
        case Left(_) =>
          page(
            "Recipes",
            nav(session),
            main(h1("Recipes"), p(cls := "error", "Recipes could not be loaded."))
          )
      }

  protected def recipeCards(recipes: List[Recipe], query: String): Frag =
    if (recipes.isEmpty) {
      div(
        cls := "empty-state",
        htmlH2("No recipes found"),
        p("Try another phrase, or start a recipe with this search."),
        a(
          cls := "button primary",
          href := s"/recipes/new?title=${url(query)}",
          s"Create “${query.trim}”"
        )
      )
    } else {
      frag(recipes.map { recipe =>
        article(
          cls := "recipe-card",
          img(
            src := s"/media/recipes/${id(recipe.id)}/primary?variant=thumbnail",
            alt := "",
            attr("loading") := "lazy"
          ),
          div(
            htmlH2(a(href := s"/recipes/${id(recipe.id)}", recipe.title)),
            p(summary(recipe.description)),
            p(
              cls := "muted",
              recipe.lastMadeAt.fold("Not cooked yet")(instant => s"Last made ${date(instant)}")
            )
          )
        )
      })
    }

  protected def browserSort(raw: Option[String]): RecipeSort = raw match {
    case Some(RecipeSort.Updated.value) => RecipeSort.Updated
    case Some(RecipeSort.Title.value)   => RecipeSort.Title
    case _                              => RecipeSort.Recent
  }
}
