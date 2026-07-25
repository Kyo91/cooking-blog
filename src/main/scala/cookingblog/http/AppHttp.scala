package cookingblog.http

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.*
import cookingblog.config.AuthConfig
import cookingblog.http.api.ApiRoutes
import cookingblog.service.RecipeApiService
import doobie.Transactor
import doobie.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}
import org.http4s.server.middleware.{ErrorAction, ErrorHandling, RequestId}
import org.typelevel.log4cats.Logger
import scalatags.Text.Frag
import scalatags.Text.implicits.*
import scalatags.Text.attrs.{
  action,
  autocomplete,
  charset,
  cls,
  content,
  `for`,
  id,
  lang,
  method,
  name,
  required,
  `type`,
  value
}
import scalatags.Text.tags.{body, button, form, h1, head, html, input, label, meta, p}
import scalatags.Text.tags2.{main, style, title}

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

final class AppHttp(
    credentialsAuthenticator: CredentialsAuthenticator[IO],
    sessionManager: SessionManager[IO],
    transactor: Transactor[IO],
    authConfig: AuthConfig
)(using logger: Logger[IO]) {
  private val sessionCookieName = "cooking_blog_session"
  private val csrfCookieName = "cooking_blog_csrf"
  private val apiRoutes =
    ApiRoutes(RecipeApiService(transactor), sessionManager)

  lazy val app: HttpApp[IO] =
    RequestId.httpApp(
      ErrorHandling.Recover.total(
        ErrorAction.log(
          routes,
          messageFailureLogAction = (throwable, message) => logger.warn(throwable)(message),
          serviceErrorLogAction = (throwable, message) => logger.error(throwable)(message)
        )
      )
    )

  private val routes: HttpApp[IO] =
    HttpApp[IO] { request =>
      publicRoutes(request).value.flatMap {
        case Some(response) => response.pure[IO]
        case None           => authenticate(request).flatMap(protectedResponse(request, _))
      }
    }

  private val publicRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "login" =>
      Ok(loginPage(None), `Content-Type`(MediaType.text.html))

    case request @ POST -> Root / "login" =>
      request.as[UrlForm].flatMap { form =>
        val username = form.values.get("username").flatMap(_.headOption).getOrElse("")
        val password = form.values.get("password").flatMap(_.headOption).getOrElse("")

        credentialsAuthenticator.authenticate(username, password).flatMap {
          case Some(principal) =>
            sessionManager.create(principal).flatMap { session =>
              SeeOther(Location(Uri.unsafeFromString("/")))
                .map(
                  _.addCookie(sessionCookie(session))
                    .addCookie(csrfCookie(session))
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
      case None if expectsHtml(request) =>
        SeeOther(Location(Uri.unsafeFromString("/login")))
      case None =>
        IO.pure(
          Response[IO](Status.Unauthorized)
            .withEntity("""{"code":"unauthorized","message":"Authentication required"}""")
            .withContentType(`Content-Type`(MediaType.application.json))
        )
    }

  private def protectedRoutes(session: AuthenticatedSession): HttpRoutes[IO] =
    apiRoutes.routes(session) <+> HttpRoutes.of[IO] {
      case GET -> Root =>
        Ok(homePage(session), `Content-Type`(MediaType.text.html))

      case GET -> Root / "health" / "live" =>
        Ok("""{"status":"ok"}""")
          .map(_.withContentType(`Content-Type`(MediaType.application.json)))

      case GET -> Root / "health" / "ready" =>
        sql"select 1".query[Int].unique.transact(transactor).attempt.flatMap {
          case Right(1) =>
            Ok("""{"status":"ready"}""")
              .map(_.withContentType(`Content-Type`(MediaType.application.json)))
          case Right(_) | Left(_) =>
            ServiceUnavailable("""{"status":"not_ready"}""")
              .map(_.withContentType(`Content-Type`(MediaType.application.json)))
        }

      case request @ POST -> Root / "logout" =>
        request.as[UrlForm].flatMap { form =>
          val submitted = form.values.get("csrf_token").flatMap(_.headOption)
          submitted
            .traverse(sessionManager.validateCsrf(session, _))
            .map(_.contains(true))
            .flatMap {
              case true =>
                sessionManager.invalidate(session.token) *>
                  SeeOther(Location(Uri.unsafeFromString("/login")))
                    .map(
                      _.removeCookie(sessionCookieName)
                        .removeCookie(csrfCookieName)
                    )
              case false =>
                Forbidden("Invalid CSRF token.")
            }
        }
    }

  private def authenticate(request: Request[IO]): IO[Option[AuthenticatedSession]] =
    request.cookies.find(_.name == sessionCookieName) match {
      case None         => none[AuthenticatedSession].pure[IO]
      case Some(cookie) =>
        val csrfSecret = request.cookies.find(_.name == csrfCookieName).map(_.content)
        sessionManager.authenticate(cookie.content, csrfSecret)
    }

  private def expectsHtml(request: Request[IO]): Boolean =
    !request.uri.path.renderString.startsWith("/api/")
      && request.headers
        .get[headers.Accept]
        .forall(_.values.exists(_.mediaRange.satisfiedBy(MediaType.text.html)))

  private def sessionCookie(session: IssuedSession): ResponseCookie =
    ResponseCookie(
      name = sessionCookieName,
      content = session.token,
      expires = Some(HttpDate.unsafeFromInstant(session.expiresAt)),
      maxAge = Some(authConfig.sessionLifetime.toSeconds),
      path = Some("/"),
      secure = authConfig.cookieSecure,
      httpOnly = true,
      sameSite = Some(SameSite.Strict)
    )

  private def csrfCookie(session: IssuedSession): ResponseCookie =
    ResponseCookie(
      name = csrfCookieName,
      content = session.csrfSecret,
      expires = Some(HttpDate.unsafeFromInstant(session.expiresAt)),
      maxAge = Some(authConfig.sessionLifetime.toSeconds),
      path = Some("/"),
      secure = authConfig.cookieSecure,
      httpOnly = false,
      sameSite = Some(SameSite.Strict)
    )

  private def loginPage(error: Option[String]): String =
    page(
      "Sign in",
      main(
        h1("Cooking Blog"),
        p("Sign in to continue."),
        error.fold(p())(message => p(cls := "error", message)),
        form(
          method := "post",
          action := "/login",
          label(`for` := "username", "Username"),
          input(id := "username", name := "username", autocomplete := "username", required),
          label(`for` := "password", "Password"),
          input(
            id := "password",
            name := "password",
            `type` := "password",
            autocomplete := "current-password",
            required
          ),
          button(`type` := "submit", "Sign in")
        )
      )
    )

  private def homePage(session: AuthenticatedSession): String = {
    val csrfToken = session.csrfSecret.getOrElse("")
    val expiresAt =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        session.record.expiresAt.atOffset(ZoneOffset.UTC)
      )

    page(
      "Cooking Blog",
      main(
        h1("Cooking Blog"),
        p(s"Signed in as ${session.record.principal.name}."),
        p(s"Session expires at $expiresAt."),
        p("The authenticated recipe API is running."),
        form(
          method := "post",
          action := "/logout",
          input(`type` := "hidden", name := "csrf_token", value := csrfToken),
          button(`type` := "submit", "Sign out")
        )
      )
    )
  }

  private def page(titleText: String, pageContent: Frag): String =
    "<!doctype html>" + html(
      lang := "en",
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", content := "width=device-width, initial-scale=1"),
        title(titleText),
        style(
          """
            |:root { color-scheme: light dark; font-family: system-ui, sans-serif; }
            |body { margin: 0; padding: 2rem; }
            |main { max-width: 32rem; margin: 4rem auto; }
            |form { display: grid; gap: .75rem; }
            |input, button { box-sizing: border-box; font: inherit; padding: .7rem; }
            |button { cursor: pointer; }
            |.error { color: #c62828; }
            |""".stripMargin
        )
      ),
      body(pageContent)
    ).render
}
