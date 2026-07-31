package cookingblog.http

import cats.effect.*
import cats.syntax.all.*
import ciris.Secret
import cookingblog.auth.*
import cookingblog.config.{AuthConfig, DatabaseConfig}
import cookingblog.database.Database
import cookingblog.storage.PhotoExtension
import cookingblog.storage.LocalPhotoStore
import cookingblog.storage.StorageKey
import cookingblog.service.{PhotoCleanup, PhotoService, RecipeApiService}
import fs2.Stream
import io.circe.Json
import io.circe.jawn.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.Method.*
import org.http4s.circe.*
import org.http4s.multipart.*
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final class PhotoApiIntegrationSuite extends CatsEffectSuite {
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

  test("photo API uploads, serves, captions, selects, falls back, and cleans up") {
    testApp.use { context =>
      val suffix = UUID.randomUUID().toString
      for {
        auth <- login(context.app)
        recipe <- createRecipe(context.app, auth, s"Photo recipe $suffix")
        recipeId <- jsonField(recipe, "id")
        meal <- createMeal(context.app, auth, recipeId)
        mealId <- jsonField(meal, "id")
        red <- imageBytes(Color.RED)
        blue <- imageBytes(Color.BLUE)
        firstUpload <- upload(
          context.app,
          auth,
          recipeId,
          mealId,
          "red.png",
          red,
          Some("Before")
        )
        _ <- requireStatus(firstUpload, Status.Created, "first photo upload")
        firstPhotoId <- firstArrayField(firstUpload, "id")
        secondUpload <- upload(
          context.app,
          auth,
          recipeId,
          mealId,
          "blue.png",
          blue,
          None
        )
        _ <- requireStatus(secondUpload, Status.Created, "second photo upload")
        secondPhotoId <- firstArrayField(secondUpload, "id")
        storedAfterUpload <- context.photoStore.listStorageKeys
        fallback <- context.app.run(
          auth.request(
            Request[IO](
              GET,
              Uri.unsafeFromString(
                s"/media/recipes/$recipeId/primary?variant=thumbnail"
              )
            )
          )
        )
        fallbackColor <- responseColor(fallback)
        download <- context.app.run(
          auth.request(
            Request[IO](
              GET,
              Uri.unsafeFromString(s"/media/$firstPhotoId/download")
            )
          )
        )
        downloadColor <- responseColor(download)
        captioned <- context.app.run(
          auth.request(
            Request[IO](
              PATCH,
              Uri.unsafeFromString(
                s"/api/v1/recipes/$recipeId/meals/$mealId/photos/$firstPhotoId"
              )
            ).withEntity(Json.obj("comment" -> Json.fromString("After")))
          )
        )
        selected <- context.app.run(
          auth.request(
            Request[IO](
              PUT,
              Uri.unsafeFromString(
                s"/api/v1/recipes/$recipeId/primary-photo/$firstPhotoId"
              )
            )
          )
        )
        explicit <- context.app.run(
          auth.request(
            Request[IO](
              GET,
              Uri.unsafeFromString(s"/media/recipes/$recipeId/primary")
            )
          )
        )
        explicitColor <- responseColor(explicit)
        invalid <- upload(
          context.app,
          auth,
          recipeId,
          mealId,
          "invalid.jpg",
          "not an image".getBytes,
          None
        )
        oversized <- uploadStream(
          context.app,
          auth,
          recipeId,
          mealId,
          "oversized.png",
          Stream.constant[IO, Byte](0).take(10_000_001L),
          None
        )
        deleteFirst <- context.app.run(
          auth.request(
            Request[IO](
              DELETE,
              Uri.unsafeFromString(
                s"/api/v1/recipes/$recipeId/meals/$mealId/photos/$firstPhotoId"
              )
            )
          )
        )
        mediaAfterDelete <- context.app.run(
          auth.request(
            Request[IO](
              GET,
              Uri.unsafeFromString(s"/media/$firstPhotoId")
            )
          )
        )
        fallbackAfterDelete <- context.app.run(
          auth.request(
            Request[IO](
              GET,
              Uri.unsafeFromString(s"/media/recipes/$recipeId/primary")
            )
          )
        )
        fallbackAfterDeleteColor <- responseColor(fallbackAfterDelete)
        storedAfterPhotoDelete <- context.photoStore.listStorageKeys
        deleteRecipe <- context.app.run(
          auth.request(
            Request[IO](
              DELETE,
              Uri.unsafeFromString(s"/api/v1/recipes/$recipeId")
            )
          )
        )
        storedAfterRecipeDelete <- context.photoStore.listStorageKeys
      } yield {
        assertEquals(firstUpload.status, Status.Created)
        assertEquals(secondUpload.status, Status.Created)
        assertEquals(storedAfterUpload.size, 2)
        assertEquals(fallback.status, Status.Ok)
        assertEquals(fallbackColor, Color.BLUE)
        assertEquals(download.status, Status.Ok)
        assertEquals(downloadColor, Color.RED)
        assertEquals(
          download.headers.get(CIString("Content-Disposition")).map(_.head.value),
          Some("attachment; filename=\"red.png\"")
        )
        assertEquals(
          download.headers.get(CIString("Cache-Control")).map(_.head.value),
          Some("private, no-store")
        )
        assertEquals(captioned.status, Status.Ok)
        assertEquals(selected.status, Status.Ok)
        assertEquals(explicitColor, Color.RED)
        assertEquals(invalid.status, Status.UnsupportedMediaType)
        assertEquals(oversized.status, Status.PayloadTooLarge)
        assertEquals(deleteFirst.status, Status.NoContent)
        assertEquals(mediaAfterDelete.status, Status.NotFound)
        assertEquals(fallbackAfterDeleteColor, Color.BLUE)
        assertEquals(storedAfterPhotoDelete.size, 1)
        assertEquals(deleteRecipe.status, Status.NoContent)
        assertEquals(storedAfterRecipeDelete, Set.empty[StorageKey])
        assertNotEquals(firstPhotoId, secondPhotoId)
      }
    }
  }

  test("unsupported persisted photo content types are rejected") {
    assertEquals(PhotoExtension.fromContentType("image/avif"), None)
  }

  private final case class TestContext(
      app: HttpApp[IO],
      photoStore: LocalPhotoStore
  )

  private final case class Authenticated(
      session: ResponseCookie,
      csrf: ResponseCookie
  ) {
    def request(request: Request[IO]): Request[IO] = {
      val authenticated =
        request
          .addCookie(RequestCookie(session.name, session.content))
          .addCookie(RequestCookie(csrf.name, csrf.content))
      if (request.method == GET) {
        authenticated
      } else {
        authenticated.putHeaders(
          Header.Raw(CIString("X-CSRF-Token"), csrf.content)
        )
      }
    }
  }

  private val testApp: Resource[IO, TestContext] =
    for {
      _ <- Resource.eval(Database.migrate(databaseConfig))
      transactor <- Database.transactor(databaseConfig)
      sessionStore = DoobieSessionStore(transactor)
      random <- Resource.eval(IO.blocking(SecureRandom()))
      manager =
        SessionManager[IO](
          sessionStore,
          authConfig.sessionLifetime,
          random
        )
      credentials = DummyCredentialsAuthenticator[IO](authConfig)
      directory <- temporaryDirectory
      photoStore <- Resource.eval(LocalPhotoStore.create(directory))
      photoService = PhotoService(transactor, photoStore, PhotoCleanup(photoStore))
      http =
        AppHttp(
          credentials,
          manager,
          transactor,
          authConfig,
          photoService,
          RecipeApiService(transactor, PhotoCleanup(photoStore))
        )
    } yield TestContext(http.app, photoStore)

  private def login(app: HttpApp[IO]): IO[Authenticated] =
    for {
      response <- app.run(
        Request[IO](POST, Uri.unsafeFromString("/login"))
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
        Request[IO](POST, Uri.unsafeFromString("/api/v1/recipes"))
          .withEntity(Json.obj("title" -> Json.fromString(title)))
      )
    )

  private def createMeal(
      app: HttpApp[IO],
      auth: Authenticated,
      recipeId: String
  ): IO[Response[IO]] =
    app.run(
      auth.request(
        Request[IO](
          POST,
          Uri.unsafeFromString(s"/api/v1/recipes/$recipeId/meals")
        ).withEntity(
          Json.obj(
            "cookedAt" -> Json.fromString(Instant.now().toString)
          )
        )
      )
    )

  private def upload(
      app: HttpApp[IO],
      auth: Authenticated,
      recipeId: String,
      mealId: String,
      filename: String,
      bytes: Array[Byte],
      comment: Option[String]
  ): IO[Response[IO]] =
    uploadStream(
      app,
      auth,
      recipeId,
      mealId,
      filename,
      Stream.emits(bytes).covary[IO],
      comment
    )

  private def uploadStream(
      app: HttpApp[IO],
      auth: Authenticated,
      recipeId: String,
      mealId: String,
      filename: String,
      body: Stream[IO, Byte],
      comment: Option[String]
  ): IO[Response[IO]] = {
    val parts =
      Vector(Part.fileData[IO]("photo", filename, body)) ++
        comment.toVector.map(value => Part.formData[IO]("comment", value))
    Multiparts.forSync[IO].flatMap(_.multipart(parts)).flatMap { multipart =>
      app.run(
        auth.request(
          Request[IO](
            POST,
            Uri.unsafeFromString(
              s"/api/v1/recipes/$recipeId/meals/$mealId/photos"
            )
          ).withEntity(multipart).withHeaders(multipart.headers)
        )
      )
    }
  }

  private def imageBytes(color: Color): IO[Array[Byte]] =
    IO.blocking {
      val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
      val graphics = image.createGraphics()
      try {
        graphics.setColor(color)
        graphics.fillRect(0, 0, image.getWidth, image.getHeight)
      } finally {
        graphics.dispose()
      }
      val output = ByteArrayOutputStream()
      assert(ImageIO.write(image, "png", output))
      output.toByteArray
    }

  private def responseColor(response: Response[IO]): IO[Color] =
    response.body.compile.to(Array).flatMap { bytes =>
      IO.blocking {
        val image = ImageIO.read(java.io.ByteArrayInputStream(bytes))
        Color(image.getRGB(0, 0))
      }
    }

  private def jsonField(response: Response[IO], field: String): IO[String] =
    responseJson(response).flatMap(json =>
      IO.fromEither(
        json.hcursor
          .get[String](field)
          .leftMap(error => RuntimeException(error.message))
      )
    )

  private def firstArrayField(
      response: Response[IO],
      field: String
  ): IO[String] =
    responseJson(response).flatMap(json =>
      IO.fromEither(
        json.hcursor.downArray
          .get[String](field)
          .leftMap(error => RuntimeException(error.message))
      )
    )

  private def responseJson(response: Response[IO]): IO[Json] =
    response.bodyText.compile.string.flatMap(body =>
      IO.fromEither(
        parse(body).leftMap(error =>
          RuntimeException(
            s"${response.status} response was not JSON: ${error.message}; body=$body"
          )
        )
      )
    )

  private def requireStatus(
      response: Response[IO],
      expected: Status,
      operation: String
  ): IO[Unit] =
    if (response.status == expected) {
      IO.unit
    } else {
      response.bodyText.compile.string.flatMap(body =>
        IO.raiseError(
          AssertionError(
            s"$operation returned ${response.status}; body=$body"
          )
        )
      )
    }

  private def requiredCookie(
      response: Response[IO],
      name: String
  ): IO[ResponseCookie] =
    IO.fromOption(response.cookies.find(_.name == name))(
      AssertionError(s"Response did not contain $name cookie")
    )

  private val temporaryDirectory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("cooking-blog-photo-test-")))(directory =>
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
