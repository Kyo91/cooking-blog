package cookingblog.http

import cats.effect.*
import ciris.Secret
import cookingblog.auth.*
import cookingblog.config.{AuthConfig, DatabaseConfig}
import cookingblog.database.Database
import cookingblog.storage.LocalPhotoStore
import cookingblog.service.{PhotoCleanup, PhotoService, RecipeApiService}
import doobie.implicits.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.Method.*
import org.http4s.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

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
    testApp.use { app =>
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
        anonymousStatic <- app.run(Request[IO](GET, uri"/static/app-v1.js"))
        anonymousLive <- app.run(Request[IO](GET, uri"/health/live"))
        anonymousHealth <- app.run(Request[IO](GET, uri"/health/ready"))
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
        assertEquals(anonymousStatic.status, Status.SeeOther)
        assertEquals(anonymousLive.status, Status.SeeOther)
        assertEquals(anonymousHealth.status, Status.SeeOther)
        assertEquals(anonymousLogout.status, Status.SeeOther)
        assertEquals(invalidLogin.status, Status.Unauthorized)
        assertEquals(login.status, Status.SeeOther)
        assertEquals(authenticatedHome.status, Status.Ok)
        assertEquals(authenticatedHealth.status, Status.Ok)
        assertEquals(logout.status, Status.SeeOther)
        assertEquals(afterLogout.status, Status.SeeOther)
      }
    }
  }

  test("authenticated browser pages provide searchable recipe capture flow") {
    testApp.use { app =>
      for {
        login <- app.run(
          Request[IO](POST, uri"/login")
            .withEntity(UrlForm("username" -> "admin", "password" -> "test"))
        )
        sessionCookie <- requiredCookie(login, "cooking_blog_session")
        csrfCookie <- requiredCookie(login, "cooking_blog_csrf")
        home <- app.run(
          withCookies(Request[IO](GET, uri"/?q=grilled+chicken"), sessionCookie, csrfCookie)
        )
        homeBody <- home.as[String]
        newRecipe <- app.run(
          withCookies(
            Request[IO](GET, uri"/recipes/new?title=grilled+chicken"),
            sessionCookie,
            csrfCookie
          )
        )
        newRecipeBody <- newRecipe.as[String]
        search <- app.run(
          withCookies(
            Request[IO](GET, uri"/recipes/search?q=grilled+chicken"),
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
        assert(homeBody.contains("/recipes/new?title=grilled+chicken"))
        assertEquals(newRecipe.status, Status.Ok)
        assert(newRecipeBody.contains("value=\"grilled chicken\""))
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

  private val testApp: Resource[IO, HttpApp[IO]] =
    for {
      _ <- Resource.eval(Database.migrate(databaseConfig))
      transactor <- Database.transactor(databaseConfig)
      store = DoobieSessionStore(transactor)
      random <- Resource.eval(IO.blocking(SecureRandom()))
      manager = SessionManager[IO](store, authConfig.sessionLifetime, random)
      credentials = DummyCredentialsAuthenticator[IO](authConfig)
      photoDirectory <- temporaryDirectory
      photoStore <- Resource.eval(LocalPhotoStore.create(photoDirectory))
      cleanup = PhotoCleanup(photoStore)
      photoService = PhotoService(transactor, photoStore, cleanup)
      recipeService = RecipeApiService(transactor, cleanup)
      http = AppHttp(credentials, manager, transactor, authConfig, photoService, recipeService)
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
