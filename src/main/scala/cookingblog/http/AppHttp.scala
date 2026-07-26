package cookingblog.http

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.*
import cookingblog.config.AuthConfig
import cookingblog.http.api.ApiRoutes
import cookingblog.http.pages.BrowserPageRoutes
import cookingblog.service.{PhotoCleanup, PhotoService, RecipeApiService}
import cookingblog.storage.PhotoStore
import doobie.Transactor
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}
import org.http4s.server.middleware.{ErrorAction, ErrorHandling, RequestId}
import org.typelevel.log4cats.Logger

/** Application HTTP composition and the default-deny authentication boundary. */
final class AppHttp(
    credentialsAuthenticator: CredentialsAuthenticator[IO],
    sessionManager: SessionManager[IO],
    transactor: Transactor[IO],
    authConfig: AuthConfig,
    photoStore: PhotoStore
)(using logger: Logger[IO]) {
  private val sessionCookieName = "cooking_blog_session"
  private val csrfCookieName = "cooking_blog_csrf"
  private val photoCleanup = PhotoCleanup(photoStore)
  private val photoService = PhotoService(transactor, photoStore, photoCleanup)
  private val recipeService = RecipeApiService(transactor, photoCleanup)
  private val apiRoutes = ApiRoutes(recipeService, photoService, sessionManager)
  private val browserPages =
    BrowserPageRoutes(sessionManager, recipeService, photoService, transactor)
  private val healthRoutes = HealthRoutes(transactor, photoService)
  private val staticRoutes = StaticRoutes(getClass.getClassLoader)

  def cleanupOrphanPhotos: IO[Int] = photoService.cleanupOrphans

  lazy val app: HttpApp[IO] = RequestId.httpApp(
    ErrorHandling.Recover.total(
      ErrorAction.log(
        routes,
        messageFailureLogAction = (throwable, message) => logger.warn(throwable)(message),
        serviceErrorLogAction = (throwable, message) => logger.error(throwable)(message)
      )
    )
  )

  private val routes: HttpApp[IO] = HttpApp[IO] { request =>
    publicRoutes(request).value.flatMap {
      case Some(response) => response.pure[IO]
      case None           => authenticate(request).flatMap(protectedResponse(request, _))
    }
  }

  private val publicRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "login" => Ok(loginPage(None), `Content-Type`(MediaType.text.html))
    case request @ POST -> Root / "login" =>
      request.as[UrlForm].flatMap { form =>
        val username = form.values.get("username").flatMap(_.headOption).getOrElse("")
        val password = form.values.get("password").flatMap(_.headOption).getOrElse("")
        credentialsAuthenticator.authenticate(username, password).flatMap {
          case Some(principal) =>
            sessionManager.create(principal).flatMap { session =>
              SeeOther(Location(Uri.unsafeFromString("/"))).map(
                _.addCookie(sessionCookie(session)).addCookie(csrfCookie(session))
              )
            }
          case None =>
            IO.pure(
              Response[IO](Status.Unauthorized)
                .withEntity(loginPage(Some("Invalid username or password.")))
                .putHeaders(`Content-Type`(MediaType.text.html))
            )
        }
      }
  }

  private def protectedResponse(
      request: Request[IO],
      authenticatedSession: Option[AuthenticatedSession]
  ): IO[Response[IO]] =
    authenticatedSession match {
      case Some(session)                => protectedRoutes(session).orNotFound(request)
      case None if expectsHtml(request) => SeeOther(Location(Uri.unsafeFromString("/login")))
      case None                         =>
        IO.pure(
          Response[IO](Status.Unauthorized)
            .withEntity("{\"code\":\"unauthorized\",\"message\":\"Authentication required\"}")
            .withContentType(`Content-Type`(MediaType.application.json))
        )
    }

  private def protectedRoutes(session: AuthenticatedSession): HttpRoutes[IO] =
    apiRoutes.routes(session) <+> browserPages.routes(
      session
    ) <+> staticRoutes.routes <+> healthRoutes.routes <+> logoutRoutes(session)

  private def logoutRoutes(session: AuthenticatedSession): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "logout" =>
      request.as[UrlForm].flatMap { form =>
        form.values
          .get("csrf_token")
          .flatMap(_.headOption)
          .traverse(sessionManager.validateCsrf(session, _))
          .map(_.contains(true))
          .flatMap {
            case true =>
              sessionManager
                .invalidate(session.token) *> SeeOther(Location(Uri.unsafeFromString("/login")))
                .map(_.removeCookie(sessionCookieName).removeCookie(csrfCookieName))
            case false => Forbidden("Invalid CSRF token.")
          }
      }
  }

  private def loginPage(error: Option[String]): String = browserPages.loginPage(error)

  private def authenticate(request: Request[IO]): IO[Option[AuthenticatedSession]] =
    request.cookies
      .find(_.name == sessionCookieName)
      .fold(none[AuthenticatedSession].pure[IO])(cookie =>
        sessionManager.authenticate(
          cookie.content,
          request.cookies.find(_.name == csrfCookieName).map(_.content)
        )
      )

  private def expectsHtml(request: Request[IO]): Boolean =
    !request.uri.path.renderString.startsWith("/api/") && request.headers
      .get[headers.Accept]
      .forall(_.values.exists(_.mediaRange.satisfiedBy(MediaType.text.html)))

  private def sessionCookie(session: IssuedSession): ResponseCookie = ResponseCookie(
    sessionCookieName,
    session.token,
    expires = Some(HttpDate.unsafeFromInstant(session.expiresAt)),
    maxAge = Some(authConfig.sessionLifetime.toSeconds),
    path = Some("/"),
    secure = authConfig.cookieSecure,
    httpOnly = true,
    sameSite = Some(SameSite.Strict)
  )

  private def csrfCookie(session: IssuedSession): ResponseCookie = ResponseCookie(
    csrfCookieName,
    session.csrfSecret,
    expires = Some(HttpDate.unsafeFromInstant(session.expiresAt)),
    maxAge = Some(authConfig.sessionLifetime.toSeconds),
    path = Some("/"),
    secure = authConfig.cookieSecure,
    httpOnly = false,
    sameSite = Some(SameSite.Strict)
  )
}
