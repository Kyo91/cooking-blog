package cookingblog.http

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import cookingblog.auth.*
import cookingblog.config.AuthConfig
import cookingblog.http.api.ApiRoutes
import cookingblog.http.pages.BrowserPageRoutes
import cookingblog.observability.OperationalMetrics
import cookingblog.service.{PhotoService, RecipeApiService}
import doobie.Transactor
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}
import org.http4s.server.middleware.{EntityLimiter, ErrorAction, ErrorHandling, RequestId}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger

/** Application HTTP composition and the default-deny authentication boundary. */
final class AppHttp(
    credentialsAuthenticator: CredentialsAuthenticator[IO],
    sessionManager: SessionManager[IO],
    transactor: Transactor[IO],
    authConfig: AuthConfig,
    photoService: PhotoService,
    recipeService: RecipeApiService,
    metrics: OperationalMetrics = OperationalMetrics.noop,
    maximumRequestBytes: Long = 105_000_000L,
    scrapingEnabled: Boolean = true
)(using logger: Logger[IO]) {
  private val sessionCookieName = "cooking_blog_session"
  private val csrfCookieName = "cooking_blog_csrf"
  private val apiRoutes =
    ApiRoutes(recipeService, photoService, sessionManager, scrapingEnabled)
  private val browserPages =
    BrowserPageRoutes(
      sessionManager,
      recipeService,
      photoService,
      transactor,
      scrapingEnabled
    )
  private val healthRoutes = HealthRoutes(transactor, photoService)
  private val metricsRoutes = MetricsRoutes(transactor, metrics)
  private val staticRoutes = StaticRoutes(getClass.getClassLoader)

  def cleanupOrphanPhotos: IO[Int] = photoService.cleanupOrphans

  lazy val app: HttpApp[IO] =
    RequestId.httpApp(
      recordRequests(
        secureHeaders(
          EntityLimiter.httpApp(
            ErrorHandling.Recover.total(
              ErrorAction.log(
                routes,
                messageFailureLogAction = (throwable, message) => logger.warn(throwable)(message),
                serviceErrorLogAction = (throwable, message) => logger.error(throwable)(message)
              )
            ),
            maximumRequestBytes
          )
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
    ) <+> staticRoutes.routes <+> healthRoutes.routes <+> metricsRoutes.routes <+>
      logoutRoutes(session)

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

  private def secureHeaders(next: HttpApp[IO]): HttpApp[IO] =
    HttpApp[IO](request =>
      next(request).map(
        _.putHeaders(
          Header.Raw(
            CIString("Content-Security-Policy"),
            "default-src 'self'; base-uri 'none'; connect-src 'self'; " +
              "form-action 'self'; frame-ancestors 'none'; img-src 'self' blob:; " +
              "object-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'"
          ),
          Header.Raw(CIString("Referrer-Policy"), "no-referrer"),
          Header
            .Raw(CIString("Permissions-Policy"), "camera=(self), microphone=(), geolocation=()"),
          Header.Raw(CIString("X-Content-Type-Options"), "nosniff"),
          Header.Raw(CIString("X-Frame-Options"), "DENY")
        )
      )
    )

  private def recordRequests(next: HttpApp[IO]): HttpApp[IO] =
    HttpApp[IO] { request =>
      Clock[IO].monotonic.flatMap { startedAt =>
        next(request).flatTap { response =>
          Clock[IO].monotonic.flatMap { finishedAt =>
            val duration = finishedAt - startedAt
            val requestId =
              request.headers
                .get(CIString("X-Request-ID"))
                .map(_.head.value)
                .getOrElse("unknown")
            metrics.recordRequest(
              request.method.name,
              response.status.code,
              duration
            ) *> logger.info(
              s"http_request_complete request_id=$requestId method=${request.method.name} " +
                s"path=${request.uri.path.renderString} status=${response.status.code} " +
                s"duration_ms=${duration.toMillis}"
            )
          }
        }
      }
    }
}
