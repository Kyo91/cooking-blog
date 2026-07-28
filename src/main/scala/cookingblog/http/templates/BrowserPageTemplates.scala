package cookingblog.http.templates

import cats.effect.IO
import cookingblog.service.{PhotoService, RecipeApiService}
import doobie.Transactor

/** Composes the browser page templates used by the browser routes. */
private[http] trait BrowserPageTemplates
    extends BrowserHomeTemplates
    with BrowserRecipeFormTemplates
    with BrowserRecipeDetailTemplates {
  protected def recipeService: RecipeApiService
  protected def photoService: PhotoService
  protected def transactor: Transactor[IO]
  protected def scrapingEnabled: Boolean
}
