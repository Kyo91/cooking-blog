package cookingblog.http

import cats.effect.*
import cats.syntax.all.*
import ciris.Secret
import cookingblog.auth.*
import cookingblog.config.{AuthConfig, DatabaseConfig}
import cookingblog.database.Database
import cookingblog.storage.LocalPhotoStore
import cookingblog.service.{PhotoCleanup, PhotoService, RecipeApiService}
import io.circe.Json
import io.circe.jawn.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.Method.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import java.security.SecureRandom
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

final class ApiIntegrationSuite extends CatsEffectSuite {
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

  test("authenticated API supports recipe, meal, and reference lifecycles") {
    testApp.use { app =>
      val suffix = UUID.randomUUID().toString
      val firstTitle = s"API Alpha $suffix"
      val secondTitle = s"API Beta $suffix"
      val firstCookedAt = Instant.parse("2026-07-20T12:00:00Z")
      val secondCookedAt = Instant.parse("2026-07-21T12:00:00Z")

      for {
        auth <- login(app)
        missingCsrf <- app.run(
          auth.request(
            Request[IO](POST, uri"/api/v1/recipes")
              .withEntity(Json.obj("title" -> Json.fromString("Rejected"))),
            includeCsrf = false
          )
        )
        blank <- app.run(
          auth.request(
            Request[IO](POST, uri"/api/v1/recipes")
              .withEntity(Json.obj("title" -> Json.fromString("  ")))
          )
        )
        _ <- IO.raiseUnless(blank.status == Status.BadRequest)(
          AssertionError(s"blank recipe returned ${blank.status}")
        )
        blankJson <- responseJson(blank)
        first <- createRecipe(app, auth, firstTitle)
        _ <- IO.raiseUnless(first.status == Status.Created)(
          AssertionError(s"first recipe returned ${first.status}")
        )
        firstJson <- responseJson(first)
        firstId <- jsonString(firstJson, "id")
        second <- createRecipe(app, auth, secondTitle)
        _ <- IO.raiseUnless(second.status == Status.Created)(
          AssertionError(s"second recipe returned ${second.status}")
        )
        secondJson <- responseJson(second)
        secondId <- jsonString(secondJson, "id")
        duplicate <- createRecipe(app, auth, s"  ${firstTitle.toUpperCase}  ")
        firstPage <- app.run(
          auth.request(
            Request[IO](
              GET,
              Uri.unsafeFromString(
                s"/api/v1/recipes?q=$suffix&sort=title&limit=1"
              )
            )
          )
        )
        firstPageJson <- responseJson(firstPage)
        cursor <- jsonString(firstPageJson, "nextCursor")
        firstPageId <- firstItemId(firstPageJson)
        secondPage <- app.run(
          auth.request(
            Request[IO](
              GET,
              Uri.unsafeFromString(
                s"/api/v1/recipes?q=$suffix&sort=title&limit=1&cursor=$cursor"
              )
            )
          )
        )
        secondPageJson <- responseJson(secondPage)
        secondPageId <- firstItemId(secondPageJson)
        staleCursor <- app.run(
          auth.request(
            Request[IO](
              GET,
              Uri.unsafeFromString(
                s"/api/v1/recipes?q=unrelated&sort=title&limit=1&cursor=$cursor"
              )
            )
          )
        )
        patched <- app.run(
          auth.request(
            jsonRequest(
              PATCH,
              s"/api/v1/recipes/$firstId",
              Json.obj("description" -> Json.fromString("Weeknight favorite"))
            )
          )
        )
        patchedJson <- responseJson(patched)
        firstMeal <- app.run(
          auth.request(
            jsonRequest(
              POST,
              s"/api/v1/recipes/$firstId/meals",
              Json.obj(
                "notes" -> Json.fromString("first"),
                "cookedAt" -> Json.fromString(firstCookedAt.toString)
              )
            )
          )
        )
        firstMealJson <- responseJson(firstMeal)
        firstMealId <- jsonString(firstMealJson, "id")
        secondMeal <- app.run(
          auth.request(
            jsonRequest(
              POST,
              s"/api/v1/recipes/$firstId/meals",
              Json.obj(
                "notes" -> Json.fromString("second"),
                "cookedAt" -> Json.fromString(secondCookedAt.toString)
              )
            )
          )
        )
        secondMealJson <- responseJson(secondMeal)
        secondMealId <- jsonString(secondMealJson, "id")
        afterMeals <- get(app, auth, s"/api/v1/recipes/$firstId")
        afterMealsJson <- responseJson(afterMeals)
        wrongParent <- get(
          app,
          auth,
          s"/api/v1/recipes/$secondId/meals/$firstMealId"
        )
        deletedSecondMeal <- delete(
          app,
          auth,
          s"/api/v1/recipes/$firstId/meals/$secondMealId"
        )
        afterMealDelete <- get(app, auth, s"/api/v1/recipes/$firstId")
        afterMealDeleteJson <- responseJson(afterMealDelete)
        urlReference <- app.run(
          auth.request(
            jsonRequest(
              POST,
              s"/api/v1/recipes/$firstId/references",
              Json.obj(
                "kind" -> Json.fromString("url"),
                "url" -> Json.fromString(s"https://example.com/$suffix"),
                "displayName" -> Json.fromString("Example")
              )
            )
          )
        )
        urlReferenceJson <- responseJson(urlReference)
        urlReferenceId <- jsonString(urlReferenceJson, "id")
        pendingScrapeStatus <- get(
          app,
          auth,
          s"/api/v1/recipes/$firstId/references/$urlReferenceId/scrape"
        )
        pendingScrapeStatusJson <- responseJson(pendingScrapeStatus)
        emptyReferencePatch <- app.run(
          auth.request(
            jsonRequest(
              PATCH,
              s"/api/v1/recipes/$firstId/references/$urlReferenceId",
              Json.obj()
            )
          )
        )
        retried <- app.run(
          auth.request(
            jsonRequest(
              POST,
              s"/api/v1/recipes/$firstId/references/$urlReferenceId/scrape",
              Json.obj()
            )
          )
        )
        updatedReference <- app.run(
          auth.request(
            jsonRequest(
              PATCH,
              s"/api/v1/recipes/$firstId/references/$urlReferenceId",
              Json.obj(
                "url" -> Json.fromString(
                  s"https://example.com/$suffix/updated"
                )
              )
            )
          )
        )
        bookReference <- app.run(
          auth.request(
            jsonRequest(
              POST,
              s"/api/v1/recipes/$firstId/references",
              Json.obj(
                "kind" -> Json.fromString("book"),
                "citation" -> Json.fromString("A Great Cookbook, p. 42")
              )
            )
          )
        )
        bookReferenceJson <- responseJson(bookReference)
        bookReferenceId <- jsonString(bookReferenceJson, "id")
        bookScrape <- app.run(
          auth.request(
            jsonRequest(
              POST,
              s"/api/v1/recipes/$firstId/references/$bookReferenceId/scrape",
              Json.obj()
            )
          )
        )
        bookScrapeStatus <- get(
          app,
          auth,
          s"/api/v1/recipes/$firstId/references/$bookReferenceId/scrape"
        )
        malformedId <- get(app, auth, "/api/v1/recipes/not-a-uuid")
        deletedUrlReference <- delete(
          app,
          auth,
          s"/api/v1/recipes/$firstId/references/$urlReferenceId"
        )
        deletedBookReference <- delete(
          app,
          auth,
          s"/api/v1/recipes/$firstId/references/$bookReferenceId"
        )
        deletedFirstMeal <- delete(
          app,
          auth,
          s"/api/v1/recipes/$firstId/meals/$firstMealId"
        )
        deletedFirst <- delete(app, auth, s"/api/v1/recipes/$firstId")
        deletedSecond <- delete(app, auth, s"/api/v1/recipes/$secondId")
        afterDelete <- get(app, auth, s"/api/v1/recipes/$firstId")
      } yield {
        assertEquals(missingCsrf.status, Status.Forbidden)
        assertEquals(blank.status, Status.BadRequest)
        assertEquals(
          blankJson.hcursor.get[String]("code"),
          Right("validation_error")
        )
        assertEquals(first.status, Status.Created)
        assertEquals(second.status, Status.Created)
        assertEquals(duplicate.status, Status.Conflict)
        assertEquals(firstPage.status, Status.Ok)
        assertEquals(secondPage.status, Status.Ok)
        assertNotEquals(firstPageId, secondPageId)
        assertEquals(staleCursor.status, Status.BadRequest)
        assertEquals(patched.status, Status.Ok)
        assertEquals(
          patchedJson.hcursor.get[String]("description"),
          Right("Weeknight favorite")
        )
        assertEquals(firstMeal.status, Status.Created)
        assertEquals(secondMeal.status, Status.Created)
        assertEquals(
          afterMealsJson.hcursor.get[String]("lastMadeAt"),
          Right(secondCookedAt.toString)
        )
        assertEquals(wrongParent.status, Status.Conflict)
        assertEquals(deletedSecondMeal.status, Status.NoContent)
        assertEquals(
          afterMealDeleteJson.hcursor.get[String]("lastMadeAt"),
          Right(firstCookedAt.toString)
        )
        assertEquals(urlReference.status, Status.Created)
        assertEquals(
          urlReferenceJson.hcursor.get[String]("importStatus"),
          Right("pending")
        )
        assertEquals(pendingScrapeStatus.status, Status.Ok)
        assertEquals(
          pendingScrapeStatusJson.hcursor.get[String]("importStatus"),
          Right("pending")
        )
        assertEquals(
          pendingScrapeStatusJson.hcursor
            .downField("latestJob")
            .get[String]("status"),
          Right("pending")
        )
        assertEquals(emptyReferencePatch.status, Status.BadRequest)
        assertEquals(retried.status, Status.Accepted)
        assertEquals(updatedReference.status, Status.Ok)
        assertEquals(bookReference.status, Status.Created)
        assertEquals(bookScrape.status, Status.BadRequest)
        assertEquals(bookScrapeStatus.status, Status.BadRequest)
        assertEquals(malformedId.status, Status.BadRequest)
        assertEquals(deletedUrlReference.status, Status.NoContent)
        assertEquals(deletedBookReference.status, Status.NoContent)
        assertEquals(deletedFirstMeal.status, Status.NoContent)
        assertEquals(deletedFirst.status, Status.NoContent)
        assertEquals(deletedSecond.status, Status.NoContent)
        assertEquals(afterDelete.status, Status.NotFound)
      }
    }
  }

  test("ranked search uses normalized keywords and title trigram fallback") {
    testApp.use { app =>
      val suffix = UUID.randomUUID().toString.take(8)
      for {
        auth <- login(app)
        titleMatch <- app.run(
          auth.request(
            jsonRequest(
              POST,
              "/api/v1/recipes",
              Json.obj(
                "title" -> Json.fromString(s"Grilled Chicken $suffix"),
                "keywords" -> Json.fromString("summer, grill")
              )
            )
          )
        )
        keywordMatch <- app.run(
          auth.request(
            jsonRequest(
              POST,
              "/api/v1/recipes",
              Json.obj(
                "title" -> Json.fromString(s"Smoky Drumsticks $suffix"),
                "keywords" -> Json.fromString(" grilled chicken , GrillED Chicken, weeknight ")
              )
            )
          )
        )
        titleResult <- responseJson(titleMatch)
        titleId <- jsonString(titleResult, "id")
        keywordResult <- responseJson(keywordMatch)
        keywordId <- jsonString(keywordResult, "id")
        ranked <- get(app, auth, s"/api/v1/recipes?q=grilled%20chicken%20$suffix")
        rankedJson <- responseJson(ranked)
        rankedTitles <- itemTitles(rankedJson)
        keywordOnly <- get(app, auth, "/api/v1/recipes?q=grilled%20chicken")
        keywordOnlyJson <- responseJson(keywordOnly)
        keywordOnlyIds <- itemIds(keywordOnlyJson)
        typo <- get(app, auth, s"/api/v1/recipes?q=griled%20chicken%20$suffix")
        typoJson <- responseJson(typo)
        typoIds <- itemIds(typoJson)
        _ <- delete(app, auth, s"/api/v1/recipes/$titleId")
        _ <- delete(app, auth, s"/api/v1/recipes/$keywordId")
      } yield {
        assertEquals(titleMatch.status, Status.Created)
        assertEquals(keywordMatch.status, Status.Created)
        assertEquals(ranked.status, Status.Ok)
        assertEquals(
          rankedTitles.take(2),
          List(s"Grilled Chicken $suffix", s"Smoky Drumsticks $suffix")
        )
        assert(keywordOnlyIds.contains(keywordId))
        assert(typoIds.contains(titleId))
      }
    }
  }

  private final case class Authenticated(
      session: ResponseCookie,
      csrf: ResponseCookie
  ) {
    def request(
        request: Request[IO],
        includeCsrf: Boolean = true
    ): Request[IO] = {
      val withCookies =
        request
          .addCookie(RequestCookie(session.name, session.content))
          .addCookie(RequestCookie(csrf.name, csrf.content))
      if (includeCsrf && request.method != GET) {
        withCookies.putHeaders(
          Header.Raw(CIString("X-CSRF-Token"), csrf.content)
        )
      } else {
        withCookies
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
    } yield http.app

  private def login(app: HttpApp[IO]): IO[Authenticated] =
    for {
      response <- app.run(
        Request[IO](POST, uri"/login")
          .withEntity(UrlForm("username" -> "admin", "password" -> "test"))
      )
      session <- requiredCookie(response, "cooking_blog_session")
      csrf <- requiredCookie(response, "cooking_blog_csrf")
    } yield Authenticated(session, csrf)

  private def createRecipe(
      app: HttpApp[IO],
      auth: Authenticated,
      title: String
  ): IO[Response[IO]] =
    app.run(
      auth.request(
        jsonRequest(
          POST,
          "/api/v1/recipes",
          Json.obj("title" -> Json.fromString(title))
        )
      )
    )

  private def get(
      app: HttpApp[IO],
      auth: Authenticated,
      path: String
  ): IO[Response[IO]] =
    app.run(auth.request(Request[IO](GET, Uri.unsafeFromString(path))))

  private def delete(
      app: HttpApp[IO],
      auth: Authenticated,
      path: String
  ): IO[Response[IO]] =
    app.run(auth.request(Request[IO](DELETE, Uri.unsafeFromString(path))))

  private def jsonRequest(
      method: Method,
      path: String,
      body: Json
  ): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path)).withEntity(body)

  private def requiredCookie(
      response: Response[IO],
      name: String
  ): IO[ResponseCookie] =
    IO.fromOption(response.cookies.find(_.name == name))(
      AssertionError(s"Response did not contain $name cookie")
    )

  private def jsonString(json: Json, field: String): IO[String] =
    IO.fromEither(
      json.hcursor
        .get[String](field)
        .leftMap(error => RuntimeException(error.message))
    )

  private def responseJson(response: Response[IO]): IO[Json] =
    response.bodyText.compile.string.flatMap { body =>
      IO.fromEither(
        parse(body).leftMap(error =>
          RuntimeException(
            s"${response.status} response was not JSON: ${error.message}; body=$body"
          )
        )
      )
    }

  private def firstItemId(json: Json): IO[String] =
    IO.fromEither(
      json.hcursor
        .downField("items")
        .downArray
        .get[String]("id")
        .leftMap(error => RuntimeException(error.message))
    )

  private def itemTitles(json: Json): IO[List[String]] =
    IO.fromEither(
      json.hcursor
        .downField("items")
        .as[List[Json]]
        .flatMap(_.traverse(_.hcursor.get[String]("title")))
        .leftMap(error => RuntimeException(error.message))
    )

  private def itemIds(json: Json): IO[List[String]] =
    IO.fromEither(
      json.hcursor
        .downField("items")
        .as[List[Json]]
        .flatMap(_.traverse(_.hcursor.get[String]("id")))
        .leftMap(error => RuntimeException(error.message))
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
