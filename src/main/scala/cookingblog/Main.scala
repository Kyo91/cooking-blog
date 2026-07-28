package cookingblog

import cats.effect.*
import com.comcast.ip4s.{Host, Port}
import cookingblog.auth.*
import cookingblog.config.*
import cookingblog.database.Database
import cookingblog.http.AppHttp
import cookingblog.observability.OperationalMetrics
import cookingblog.scraping.Scraping
import cookingblog.service.{PhotoCleanup, PhotoService, RecipeApiService}
import cookingblog.storage.PhotoStore
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.security.SecureRandom
import scala.concurrent.duration.*

object Main extends IOApp.Simple {
  given logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  override def run: IO[Unit] = resources.useForever

  private val resources: Resource[IO, Unit] =
    for {
      config <- Resource.eval(
        AppConfig.load.flatMap(
          _.fold(
            errors =>
              IO.raiseError(IllegalArgumentException(errors.toNonEmptyList.toList.mkString("; "))),
            IO.pure
          )
        )
      )
      _ <- Resource.eval(Database.migrate(config.database))
      transactor <- Database.transactor(config.database)
      sessionStore = DoobieSessionStore(transactor)
      secureRandom <- Resource.eval(IO.blocking(SecureRandom()))
      sessionManager =
        SessionManager[IO](sessionStore, config.auth.sessionLifetime, secureRandom)
      credentialsAuthenticator <- Resource.eval(authenticator(config))
      metrics <- Resource.eval(OperationalMetrics.create)
      photoStore <- PhotoStore.create(config.photos)
      photoCleanup = PhotoCleanup(photoStore)
      photoService = PhotoService(transactor, photoStore, photoCleanup, metrics)
      recipeService = RecipeApiService(transactor, photoCleanup)
      _ <- Scraping.resources(config.scraping, transactor, metrics)
      http =
        AppHttp(
          credentialsAuthenticator,
          sessionManager,
          transactor,
          config.auth,
          photoService,
          recipeService,
          metrics,
          config.runtime.maximumRequestBytes,
          config.scraping.enabled
        )
      _ <- Resource.make(
        (IO.sleep(1.minute) *> sessionManager.deleteExpiredOrInvalidated.flatMap(count =>
          logger.info(s"Deleted $count expired or invalidated authentication sessions")
        )).foreverM.start
      )(_.cancel)
      _ <- Resource.make(
        (
          http.cleanupOrphanPhotos.flatMap(count =>
            logger.info(s"Deleted $count orphaned photo storage directories")
          ) *> IO.sleep(15.minutes)
        ).foreverM.start
      )(_.cancel)
      host <- Resource.eval(
        IO.fromOption(Host.fromString(config.http.host))(
          IllegalArgumentException(s"Invalid HTTP_HOST: ${config.http.host}")
        )
      )
      port <- Resource.eval(
        IO.fromOption(Port.fromInt(config.http.port))(
          IllegalArgumentException(s"Invalid HTTP_PORT: ${config.http.port}")
        )
      )
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(http.app)
        .build
      _ <- Resource.eval(logger.info(s"Cooking Blog listening on http://$host:$port"))
    } yield ()

  private def authenticator(
      config: AppConfig
  ): IO[CredentialsAuthenticator[IO]] =
    config.runtime.environment match {
      case RuntimeEnvironment.Development =>
        IO.pure(DummyCredentialsAuthenticator[IO](config.auth))
      case RuntimeEnvironment.Production =>
        ConfiguredCredentialsAuthenticator.create[IO](config.auth)
    }
}
