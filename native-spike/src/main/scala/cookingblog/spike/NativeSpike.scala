package cookingblog.spike

import cats.effect.{IO, IOApp, Resource}
import ciris.Secret
import com.comcast.ip4s.*
import cookingblog.auth.*
import cookingblog.config.AuthConfig
import cookingblog.database.Database
import cookingblog.http.SessionHttp
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.EntityLimiter
import skunk.SSL
import skunk.codec.all.int4
import skunk.implicits.*

import scala.concurrent.duration.*

/** Local-only executable for the Native linker and runtime smoke check. */
object NativeSpike extends IOApp.Simple {
  private def required(name: String): IO[String] =
    IO(sys.env.get(name).filter(_.nonEmpty)).flatMap { value =>
      IO.fromOption(value)(IllegalArgumentException(s"$name must be set"))
    }

  private val databaseSettings: IO[Database.ConnectionSettings] =
    for {
      hostText <- required("SPIKE_DB_HOST")
      host <- IO.fromOption(Host.fromString(hostText))(
        IllegalArgumentException("Invalid SPIKE_DB_HOST")
      )
      portText <- required("SPIKE_DB_PORT")
      port <- IO.fromOption(Port.fromString(portText).filter(_.value > 0))(
        IllegalArgumentException("Invalid SPIKE_DB_PORT")
      )
      database <- required("SPIKE_DB_NAME")
      user <- required("SPIKE_DB_USER")
      password <- required("SPIKE_DB_PASSWORD")
    } yield Database.ConnectionSettings(host, port, database, user, Secret(password), 1, SSL.None)

  private val authSettings: IO[AuthConfig] =
    for {
      user <- required("SPIKE_AUTH_USER")
      password <- required("SPIKE_AUTH_PASSWORD")
    } yield AuthConfig(user, Secret(password), 24.hours, cookieSecure = false)

  override def run: IO[Unit] = {
    val server = for {
      settings <- Resource.eval(databaseSettings)
      pool <- Database.pooled(settings)
      auth <- Resource.eval(authSettings)
      credentials <- Resource.eval(ConfiguredCredentialsAuthenticator.create[IO](auth))
      sessions = SessionManager[IO](
        SkunkSessionStore(pool),
        auth.sessionLifetime,
        AuthCrypto.fillRandom
      )
      sessionHttp = SessionHttp(
        credentials,
        sessions,
        auth,
        error => error.getOrElse("Native login smoke fixture"),
        session =>
          HttpRoutes.of[IO] { case GET -> Root / "api" / "v1" / "smoke" =>
            pool.withSession(
              _.unique(
                sql"select 1 where to_regclass('public.recipes') is not null".query(int4)
              )
            ) *> Ok(s"postgres=1 principal=${session.record.principal.name}")
          }
      )
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(ipv4"127.0.0.1")
        .withPort(port"8080")
        .withHttpApp(EntityLimiter.httpApp(sessionHttp.routes, 4096L))
        .build
    } yield ()
    server.useForever
  }
}
