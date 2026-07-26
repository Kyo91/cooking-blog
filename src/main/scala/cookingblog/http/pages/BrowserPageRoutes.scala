package cookingblog.http.pages

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.{AuthenticatedSession, SessionManager}
import cookingblog.http.templates.BrowserPageTemplates
import cookingblog.service.{PhotoService, RecipeApiService}
import doobie.Transactor
import org.http4s.HttpRoutes

/** Browser-only routes composed from resource-specific route groups. */
final class BrowserPageRoutes(
    protected val sessionManager: SessionManager[IO],
    protected val recipeService: RecipeApiService,
    protected val photoService: PhotoService,
    protected val transactor: Transactor[IO]
) extends BrowserPageTemplates
    with BrowserRouteSupport
    with BrowserReadRoutes
    with BrowserRecipeRoutes
    with BrowserMealRoutes
    with BrowserReferenceRoutes
    with BrowserPhotoRoutes {
  def routes(session: AuthenticatedSession): HttpRoutes[IO] =
    readRoutes(session) <+>
      recipeRoutes(session) <+>
      mealRoutes(session) <+>
      referenceRoutes(session) <+>
      photoRoutes(session)
}
