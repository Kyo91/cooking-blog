package cookingblog

import cats.effect.*
import com.comcast.ip4s.{Host, Port}
import cookingblog.auth.*
import cookingblog.config.AppConfig
import cookingblog.database.Database
import cookingblog.http.AppHttp
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.security.SecureRandom
import scala.concurrent.duration.*

object Main extends IOApp.Simple {
  given logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  override def run: IO[Unit] =
    resources.useForever

  private val resources: Resource[IO, Unit] =
    for {
      config <- Resource.eval(AppConfig.load.load[IO])
      _ <- Resource.eval(validateConfig(config))
      _ <- Resource.eval(Database.migrate(config.database))
      transactor <- Database.transactor(config.database)
      sessionStore = DoobieSessionStore(transactor)
      secureRandom <- Resource.eval(IO.blocking(SecureRandom()))
      sessionManager =
        SessionManager[IO](sessionStore, config.auth.sessionLifetime, secureRandom)
      credentialsAuthenticator = DummyCredentialsAuthenticator[IO](config.auth)
      http = AppHttp(credentialsAuthenticator, sessionManager, transactor, config.auth)
      _ <- Resource.make(
        (IO.sleep(1.minute) *> sessionManager.deleteExpiredOrInvalidated.flatMap(count =>
          logger.info(s"Deleted $count expired or invalidated authentication sessions")
        )).foreverM.start
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
      _ <- Resource.eval(
        logger.info(s"Cooking Blog listening on http://$host:$port")
      )
    } yield ()

  private def validateConfig(config: AppConfig): IO[Unit] = {
    val errors = List(
      Option.when(config.database.poolSize <= 0)("DATABASE_POOL_SIZE must be positive"),
      Option.when(config.auth.sessionLifetime != 24.hours)(
        "AUTH_SESSION_HOURS must be 24 for the Phase 1 session policy"
      )
    ).flatten

    IO.raiseWhen(errors.nonEmpty)(IllegalArgumentException(errors.mkString("; ")))
  }
}
