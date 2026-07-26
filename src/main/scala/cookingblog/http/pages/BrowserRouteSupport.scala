package cookingblog.http.pages

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.{AuthenticatedSession, SessionManager}
import cookingblog.service.ApiError
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}
import scalatags.Text.all.*

private[pages] trait BrowserRouteSupport {
  protected def sessionManager: SessionManager[IO]

  protected def browserMutation(
      session: AuthenticatedSession,
      request: Request[IO]
  )(use: UrlForm => IO[Response[IO]]): IO[Response[IO]] =
    request.as[UrlForm].flatMap { form =>
      form.values
        .get("csrf_token")
        .flatMap(_.headOption)
        .traverse(sessionManager.validateCsrf(session, _))
        .flatMap {
          case Some(true) => use(form)
          case _          =>
            Forbidden(
              p(cls := "form-error", role := "alert", "Invalid CSRF token.").render,
              `Content-Type`(MediaType.text.html)
            )
        }
    }

  protected def redirectToRecipe(recipeId: String): IO[Response[IO]] =
    SeeOther(Location(Uri.unsafeFromString(s"/recipes/$recipeId")))

  protected def formValue(form: UrlForm, name: String): String =
    form.values.get(name).flatMap(_.headOption).getOrElse("")

  protected def formValues(form: UrlForm, name: String): List[String] =
    form.values.get(name).fold(List.empty[String])(_.toList)

  protected def formFailure(error: ApiError): IO[Response[IO]] =
    BadRequest(
      p(cls := "form-error", role := "alert", formError(error)).render,
      `Content-Type`(MediaType.text.html)
    )

  private def formError(error: ApiError): String = error match {
    case ApiError.Validation(fields)             => fields.values.flatten.mkString("; ")
    case ApiError.Conflict(message)              => message
    case ApiError.NotFound(resource)             => s"$resource was not found"
    case ApiError.InvalidRelationship(message)   => message
    case ApiError.UnsupportedMedia(message)      => message
    case ApiError.PayloadTooLarge(message)       => message
    case ApiError.UnavailableDependency(message) => message
  }
}
