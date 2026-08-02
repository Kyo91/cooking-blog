package cookingblog.http

import cats.effect.*
import ciris.Secret
import cookingblog.auth.*
import cookingblog.config.{AuthConfig, DatabaseConfig}
import cookingblog.database.Database
import cookingblog.observability.OperationalMetrics
import cookingblog.storage.LocalPhotoStore
import cookingblog.service.{PhotoCleanup, PhotoService, RecipeApiService}
import doobie.implicits.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.Method.*
import org.http4s.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.ci.CIString

import java.security.SecureRandom
import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

final class AppHttpIntegrationSuite extends CatsEffectSuite {
  given Logger[IO] = NoOpLogger[IO]

  private val databaseConfig =
    DatabaseConfig(
      sys.env.getOrElse(
        "DATABASE_URL",
        "jdbc:postgresql://localhost:5432/cooking_blog"
      ),
      sys.env.getOrElse("DATABASE_USER", "cooking_blog"),
      Secret(sys.env.getOrElse("DATABASE_PASSWORD", "cooking_blog_dev")),
      poolSize = 2
    )

  private val authConfig =
    AuthConfig("admin", Secret("test"), 24.hours, cookieSecure = false)

  test("migrates the database and protects every non-login request") {
    testApp().use { app =>
      for {
        loginPage <- app.run(Request[IO](GET, uri"/login"))
        anonymousHome <- app.run(Request[IO](GET, uri"/"))
        anonymousApi <- app.run(
          Request[IO](GET, uri"/api/v1/recipes")
            .putHeaders(headers.Accept(MediaType.application.json))
        )
        anonymousMedia <- app.run(
          Request[IO](GET, uri"/media/00000000-0000-0000-0000-000000000000")
        )
        anonymousDownload <- app.run(
          Request[IO](GET, uri"/media/00000000-0000-0000-0000-000000000000/download")
        )
        anonymousStatic <- app.run(Request[IO](GET, uri"/static/app-v1.js"))
        anonymousLive <- app.run(Request[IO](GET, uri"/health/live"))
        anonymousHealth <- app.run(Request[IO](GET, uri"/health/ready"))
        anonymousMetrics <- app.run(Request[IO](GET, uri"/metrics"))
        anonymousLogout <- app.run(Request[IO](POST, uri"/logout"))
        invalidLogin <- app.run(
          Request[IO](POST, uri"/login")
            .withEntity(UrlForm("username" -> "admin", "password" -> "wrong"))
        )
        login <- app.run(
          Request[IO](POST, uri"/login")
            .withEntity(UrlForm("username" -> "admin", "password" -> "test"))
        )
        sessionCookie <- requiredCookie(login, "cooking_blog_session")
        csrfCookie <- requiredCookie(login, "cooking_blog_csrf")
        authenticatedHome <- app.run(
          withCookies(Request[IO](GET, uri"/"), sessionCookie, csrfCookie)
        )
        authenticatedHealth <- app.run(
          withCookies(
            Request[IO](GET, uri"/health/ready"),
            sessionCookie,
            csrfCookie
          )
        )
        authenticatedMetrics <- app.run(
          withCookies(
            Request[IO](GET, uri"/metrics"),
            sessionCookie,
            csrfCookie
          )
        )
        metricsBody <- authenticatedMetrics.as[String]
        logout <- app.run(
          withCookies(
            Request[IO](POST, uri"/logout")
              .withEntity(UrlForm("csrf_token" -> csrfCookie.content)),
            sessionCookie,
            csrfCookie
          )
        )
        afterLogout <- app.run(
          withCookies(Request[IO](GET, uri"/"), sessionCookie, csrfCookie)
        )
      } yield {
        assertEquals(loginPage.status, Status.Ok)
        assertEquals(anonymousHome.status, Status.SeeOther)
        assertEquals(anonymousApi.status, Status.Unauthorized)
        assertEquals(anonymousMedia.status, Status.SeeOther)
        assertEquals(anonymousDownload.status, Status.SeeOther)
        assertEquals(anonymousStatic.status, Status.SeeOther)
        assertEquals(anonymousLive.status, Status.SeeOther)
        assertEquals(anonymousHealth.status, Status.SeeOther)
        assertEquals(anonymousMetrics.status, Status.SeeOther)
        assertEquals(anonymousLogout.status, Status.SeeOther)
        assertEquals(invalidLogin.status, Status.Unauthorized)
        assertEquals(login.status, Status.SeeOther)
        assertEquals(authenticatedHome.status, Status.Ok)
        assertEquals(authenticatedHealth.status, Status.Ok)
        assertEquals(authenticatedMetrics.status, Status.Ok)
        assert(metricsBody.contains("cooking_blog_http_requests_total"))
        assert(metricsBody.contains("""cooking_blog_scrape_jobs{status="failed"}"""))
        assertEquals(logout.status, Status.SeeOther)
        assertEquals(afterLogout.status, Status.SeeOther)
      }
    }
  }

  test("responses include release security headers and enforce the request limit") {
    testApp(maximumRequestBytes = 1024L).use { app =>
      for {
        loginPage <- app.run(Request[IO](GET, uri"/login"))
        oversizedLogin <- app.run(
          Request[IO](POST, uri"/login").withEntity(
            UrlForm("username" -> "admin", "password" -> ("x" * 2000))
          )
        )
      } yield {
        assertEquals(
          header(loginPage, "Content-Security-Policy"),
          Some(
            "default-src 'self'; base-uri 'none'; connect-src 'self'; " +
              "form-action 'self'; frame-ancestors 'none'; img-src 'self' blob:; " +
              "object-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'"
          )
        )
        assertEquals(header(loginPage, "X-Content-Type-Options"), Some("nosniff"))
        assertEquals(header(loginPage, "X-Frame-Options"), Some("DENY"))
        assert(header(loginPage, "X-Request-ID").exists(_.nonEmpty))
        assertEquals(oversizedLogin.status, Status.PayloadTooLarge)
      }
    }
  }

  test("authenticated browser pages provide searchable recipe capture flow") {
    val query = s"browser-capture-${System.nanoTime()}"
    testApp().use { app =>
      for {
        login <- app.run(
          Request[IO](POST, uri"/login")
            .withEntity(UrlForm("username" -> "admin", "password" -> "test"))
        )
        sessionCookie <- requiredCookie(login, "cooking_blog_session")
        csrfCookie <- requiredCookie(login, "cooking_blog_csrf")
        home <- app.run(
          withCookies(
            Request[IO](GET, Uri.unsafeFromString(s"/?q=$query")),
            sessionCookie,
            csrfCookie
          )
        )
        homeBody <- home.as[String]
        newRecipe <- app.run(
          withCookies(
            Request[IO](
              GET,
              Uri.unsafeFromString(s"/recipes/new?title=$query")
            ),
            sessionCookie,
            csrfCookie
          )
        )
        newRecipeBody <- newRecipe.as[String]
        search <- app.run(
          withCookies(
            Request[IO](
              GET,
              Uri.unsafeFromString(s"/recipes/search?q=$query")
            ),
            sessionCookie,
            csrfCookie
          )
        )
        htmx <- app.run(
          withCookies(
            Request[IO](GET, uri"/static/htmx-2.0.4.min.js"),
            sessionCookie,
            csrfCookie
          )
        )
        appScript <- app.run(
          withCookies(Request[IO](GET, uri"/static/app-v1.js"), sessionCookie, csrfCookie)
        )
        invalidRecipe <- app.run(
          withCookies(
            Request[IO](POST, uri"/recipes")
              .withEntity(UrlForm("csrf_token" -> csrfCookie.content, "title" -> "")),
            sessionCookie,
            csrfCookie
          )
        )
        invalidRecipeBody <- invalidRecipe.as[String]
      } yield {
        assertEquals(home.status, Status.Ok)
        assert(homeBody.contains("id=\"recipe-search\""))
        assert(homeBody.contains("id=\"recipe-sort\""))
        assert(homeBody.contains("Most recently cooked"))
        assert(homeBody.contains("id=\"recipe-results\""))
        assert(homeBody.contains(s"/recipes/new?title=$query"))
        assertEquals(newRecipe.status, Status.Ok)
        assert(newRecipeBody.contains(s"value=\"$query\""))
        assert(newRecipeBody.contains("id=\"keywords\""))
        assert(newRecipeBody.contains("id=\"add-recipe-source\""))
        assertEquals(search.status, Status.Ok)
        assertEquals(htmx.status, Status.Ok)
        assertEquals(appScript.status, Status.Ok)
        assertEquals(invalidRecipe.status, Status.BadRequest)
        assert(invalidRecipeBody.contains("form-error"))
      }
    }
  }

  test("browser labels pending imports when scraping is disabled") {
    val title = s"disabled-browser-scraping-${System.nanoTime()}"
    testApp(scrapingEnabled = false).use { app =>
      for {
        login <- app.run(
          Request[IO](POST, uri"/login")
            .withEntity(UrlForm("username" -> "admin", "password" -> "test"))
        )
        sessionCookie <- requiredCookie(login, "cooking_blog_session")
        csrfCookie <- requiredCookie(login, "cooking_blog_csrf")
        created <- app.run(
          withCookies(
            Request[IO](POST, uri"/recipes").withEntity(
              UrlForm(
                "csrf_token" -> csrfCookie.content,
                "title" -> title,
                "description" -> "",
                "keywords" -> "",
                "source_kind" -> "url",
                "source_url" -> "https://example.com/queued-browser-recipe",
                "source_citation" -> ""
              )
            ),
            sessionCookie,
            csrfCookie
          )
        )
        location <- IO.fromOption(created.headers.get[headers.Location])(
          AssertionError("Created recipe did not redirect to its detail page")
        )
        detail <- app.run(
          withCookies(
            Request[IO](GET, location.uri),
            sessionCookie,
            csrfCookie
          )
        )
        body <- detail.as[String]
        deleted <- app.run(
          withCookies(
            Request[IO](POST, Uri.unsafeFromString(s"${location.uri.path.renderString}/delete"))
              .withEntity(UrlForm("csrf_token" -> csrfCookie.content)),
            sessionCookie,
            csrfCookie
          )
        )
      } yield {
        assertEquals(created.status, Status.SeeOther)
        assertEquals(detail.status, Status.Ok)
        assertEquals(deleted.status, Status.SeeOther)
        assert(body.contains("queued (scraping disabled)"))
        assert(!body.contains("data-import-active=\"true\""))
      }
    }
  }

  private def testApp(
      maximumRequestBytes: Long = 105_000_000L,
      scrapingEnabled: Boolean = true
  ): Resource[IO, HttpApp[IO]] =
    for {
      _ <- Resource.eval(Database.migrate(databaseConfig))
      transactor <- Database.transactor(databaseConfig)
      store = DoobieSessionStore(transactor)
      random <- Resource.eval(IO.blocking(SecureRandom()))
      manager = SessionManager[IO](store, authConfig.sessionLifetime, random)
      credentials = DummyCredentialsAuthenticator[IO](authConfig)
      metrics <- Resource.eval(OperationalMetrics.create)
      photoDirectory <- temporaryDirectory
      photoStore <- Resource.eval(LocalPhotoStore.create(photoDirectory))
      cleanup = PhotoCleanup(photoStore)
      photoService = PhotoService(transactor, photoStore, cleanup, metrics)
      recipeService = RecipeApiService(transactor, cleanup)
      http =
        AppHttp(
          credentials,
          manager,
          transactor,
          authConfig,
          photoService,
          recipeService,
          metrics,
          maximumRequestBytes,
          scrapingEnabled
        )
      migrationExists <- Resource.eval(
        sql"""
          select exists (
            select 1
            from information_schema.tables
            where table_schema = 'public'
              and table_name = 'auth_sessions'
          )
        """.query[Boolean].unique.transact(transactor)
      )
      _ <- Resource.eval(
        IO.raiseUnless(migrationExists)(
          IllegalStateException("auth_sessions migration was not applied")
        )
      )
    } yield http.app

  private def requiredCookie(
      response: Response[IO],
      name: String
  ): IO[ResponseCookie] =
    IO.fromOption(response.cookies.find(_.name == name))(
      AssertionError(s"Response did not contain $name cookie")
    )

  private def withCookies(
      request: Request[IO],
      cookies: ResponseCookie*
  ): Request[IO] =
    cookies.foldLeft(request)((current, cookie) =>
      current.addCookie(RequestCookie(cookie.name, cookie.content))
    )

  private def header(response: Response[IO], name: String): Option[String] =
    response.headers.get(CIString(name)).map(_.head.value)

  private val temporaryDirectory: Resource[IO, java.nio.file.Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("cooking-blog-test-")))(directory =>
      IO.blocking {
        val stream = Files.walk(directory)
        try {
          stream
            .iterator()
            .asScala
            .toList
            .sortBy(_.getNameCount)
            .reverse
            .foreach(path => {
              val _ = Files.deleteIfExists(path)
            })
        } finally {
          stream.close()
        }
      }
    )
}
