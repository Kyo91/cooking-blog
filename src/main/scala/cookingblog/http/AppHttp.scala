package cookingblog.http

import cookingblog.database.Database
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import cookingblog.auth.*
import cookingblog.config.AuthConfig
import cookingblog.http.api.ApiRoutes
import cookingblog.http.pages.BrowserPageRoutes
import cookingblog.observability.OperationalMetrics
import cookingblog.service.{PhotoService, RecipeApiService}
import org.http4s.*
import org.http4s.server.middleware.{EntityLimiter, ErrorAction, ErrorHandling, RequestId}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger

/** Application HTTP composition and the default-deny authentication boundary. */
final class AppHttp(
    credentialsAuthenticator: CredentialsAuthenticator[IO],
    sessionManager: SessionManager[IO],
    pool: Database,
    authConfig: AuthConfig,
    photoService: PhotoService,
    recipeService: RecipeApiService,
    metrics: OperationalMetrics = OperationalMetrics.noop,
    maximumRequestBytes: Long = 105_000_000L,
    scrapingEnabled: Boolean = true
)(using logger: Logger[IO]) {
  private val apiRoutes =
    ApiRoutes(recipeService, photoService, sessionManager, scrapingEnabled)
  private val browserPages =
    BrowserPageRoutes(
      sessionManager,
      recipeService,
      photoService,
      pool,
      scrapingEnabled
    )
  private val healthRoutes = HealthRoutes(pool, photoService)
  private val metricsRoutes = MetricsRoutes(pool.use(_.scrapeJobs.countsByStatus), metrics)
  private val staticRoutes = StaticRoutes(getClass.getClassLoader)

  private val sessionHttp =
    SessionHttp(
      credentialsAuthenticator,
      sessionManager,
      authConfig,
      browserPages.loginPage,
      protectedRoutes
    )

  def cleanupOrphanPhotos: IO[Int] = photoService.cleanupOrphans

  lazy val app: HttpApp[IO] =
    RequestId.httpApp(
      recordRequests(
        secureHeaders(
          EntityLimiter.httpApp(
            ErrorHandling.Recover.total(
              ErrorAction.log(
                sessionHttp.routes,
                messageFailureLogAction = (throwable, message) => logger.warn(throwable)(message),
                serviceErrorLogAction = (throwable, message) => logger.error(throwable)(message)
              )
            ),
            maximumRequestBytes
          )
        )
      )
    )

  private def protectedRoutes(session: AuthenticatedSession): HttpRoutes[IO] =
    apiRoutes.routes(session) <+> browserPages.routes(
      session
    ) <+> staticRoutes.routes <+> healthRoutes.routes <+> metricsRoutes.routes

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
