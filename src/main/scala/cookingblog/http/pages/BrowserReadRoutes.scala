package cookingblog.http.pages

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.AuthenticatedSession
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import scalatags.Text.all.*

private[pages] trait BrowserReadRoutes {
  self: BrowserPageRoutes =>

  protected def readRoutes(session: AuthenticatedSession): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root =>
      val query = request.uri.query.params.get("q").getOrElse("")
      renderHome(query, browserSort(request.uri.query.params.get("sort")), session)
        .flatMap(Ok(_, `Content-Type`(MediaType.text.html)))

    case request @ GET -> Root / "recipes" / "search" =>
      val query = request.uri.query.params.get("q").getOrElse("")
      recipeService
        .listRecipes(Some(query), browserSort(request.uri.query.params.get("sort")), 50, None)
        .map {
          case Right(page) =>
            Ok(recipeCards(page.items, query).render, `Content-Type`(MediaType.text.html))
          case Left(_) =>
            BadRequest(
              p(cls := "error", "Search could not be completed.").render,
              `Content-Type`(MediaType.text.html)
            )
        }
        .flatten

    case request @ GET -> Root / "recipes" / "new" =>
      Ok(
        recipeForm(
          None,
          request.uri.query.params.get("title").getOrElse(""),
          "",
          "",
          Nil,
          session.csrfSecret.getOrElse("")
        ),
        `Content-Type`(MediaType.text.html)
      )

    case GET -> Root / "recipes" / rawRecipeId / "edit" =>
      recipeId(rawRecipeId).fold(notFoundPage)(recipeIdValue =>
        recipeEditPage(recipeIdValue, session.csrfSecret.getOrElse(""))
      )

    case GET -> Root / "recipes" / rawRecipeId / "meals" / "new" =>
      recipeId(rawRecipeId).fold(notFoundPage)(recipeIdValue =>
        recipeService.getRecipe(recipeIdValue).flatMap {
          case Right(recipe) =>
            Ok(
              mealForm(recipe, None, session.csrfSecret.getOrElse("")),
              `Content-Type`(MediaType.text.html)
            )
          case Left(_) => notFoundPage
        }
      )

    case GET -> Root / "recipes" / rawRecipeId / "meals" / rawMealId / "edit" =>
      (recipeId(rawRecipeId), mealId(rawMealId)).mapN((_, _)).fold(notFoundPage) {
        case (recipeIdValue, mealIdValue) =>
          (
            recipeService.getRecipe(recipeIdValue),
            recipeService.getMeal(recipeIdValue, mealIdValue)
          ).mapN {
            case (Right(recipe), Right(meal)) =>
              Ok(
                mealForm(recipe, Some(meal), session.csrfSecret.getOrElse("")),
                `Content-Type`(MediaType.text.html)
              )
            case _ => notFoundPage
          }.flatten
      }

    case GET -> Root / "recipes" / rawRecipeId =>
      recipeId(rawRecipeId).fold(notFoundPage)(
        recipeDetailPage(_, session.csrfSecret.getOrElse(""))
      )
  }
}
