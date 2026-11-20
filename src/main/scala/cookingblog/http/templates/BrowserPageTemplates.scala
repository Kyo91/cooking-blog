package cookingblog.http.templates

import cookingblog.database.Database
import cookingblog.service.{PhotoService, RecipeApiService}

/** Composes the browser page templates used by the browser routes. */
private[http] trait BrowserPageTemplates
    extends BrowserHomeTemplates
    with BrowserRecipeFormTemplates
    with BrowserRecipeDetailTemplates {
  protected def recipeService: RecipeApiService
  protected def photoService: PhotoService
  protected def pool: Database
  protected def scrapingEnabled: Boolean
}
