package cookingblog

import cats.effect.*
import cats.effect.std.Random
import com.comcast.ip4s.{Host, Port}
import cookingblog.auth.*
import cookingblog.config.AppConfig
import cookingblog.database.Database
import cookingblog.http.AppHttp
import cookingblog.scraping.*
import cookingblog.storage.LocalPhotoStore
import org.http4s.ember.client.EmberClientBuilder
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
      photoStore <- Resource.eval(LocalPhotoStore.create(config.photos.directory))
      client <- EmberClientBuilder
        .default[IO]
        .withMaxTotal(config.scraping.workerCount)
        .withMaxPerKey(_ => config.scraping.perHostConcurrency)
        .withTimeout(config.scraping.requestTimeout)
        .withIdleConnectionTime(config.scraping.totalJobTimeout)
        .build
      random <- Resource.eval(Random.scalaUtilRandom[IO])
      pageFetcher =
        SecurePageFetcher(client, config.scraping, NetworkSafety(SystemHostResolver))
      scrapeWorker =
        ScrapeWorker(
          transactor,
          config.scraping,
          HttpPageScraper(pageFetcher),
          random
        )
      _ <- scrapeWorker.run
      http =
        AppHttp(
          credentialsAuthenticator,
          sessionManager,
          transactor,
          config.auth,
          photoStore
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
      _ <- Resource.eval(
        logger.info(s"Cooking Blog listening on http://$host:$port")
      )
    } yield ()

  private def validateConfig(config: AppConfig): IO[Unit] = {
    val errors = List(
      Option.when(config.database.poolSize <= 0)("DATABASE_POOL_SIZE must be positive"),
      Option.when(config.auth.sessionLifetime != 24.hours)(
        "AUTH_SESSION_HOURS must be 24 for the Phase 1 session policy"
      ),
      Option.when(config.scraping.workerCount <= 0)(
        "SCRAPE_WORKERS must be positive"
      ),
      Option.when(config.scraping.perHostConcurrency <= 0)(
        "SCRAPE_PER_HOST_CONCURRENCY must be positive"
      ),
      Option.when(
        config.scraping.perHostConcurrency > config.scraping.workerCount
      )(
        "SCRAPE_PER_HOST_CONCURRENCY cannot exceed SCRAPE_WORKERS"
      ),
      Option.when(config.scraping.maximumResponseBytes <= 0)(
        "SCRAPE_MAX_RESPONSE_BYTES must be positive"
      ),
      Option.when(config.scraping.maximumResponseBytes > Int.MaxValue.toLong)(
        s"SCRAPE_MAX_RESPONSE_BYTES cannot exceed ${Int.MaxValue}"
      ),
      Option.when(config.scraping.maximumRedirects < 0)(
        "SCRAPE_MAX_REDIRECTS cannot be negative"
      ),
      Option.when(config.scraping.maximumAttempts <= 0)(
        "SCRAPE_MAX_ATTEMPTS must be positive"
      ),
      Option.when(config.scraping.pollInterval <= Duration.Zero)(
        "SCRAPE_POLL_MILLIS must be positive"
      ),
      Option.when(config.scraping.staleJobTimeout <= Duration.Zero)(
        "SCRAPE_STALE_JOB_MINUTES must be positive"
      ),
      Option.when(config.scraping.requestTimeout <= Duration.Zero)(
        "SCRAPE_REQUEST_SECONDS must be positive"
      ),
      Option.when(config.scraping.totalJobTimeout <= Duration.Zero)(
        "SCRAPE_TOTAL_JOB_SECONDS must be positive"
      ),
      Option.when(config.scraping.baseRetryDelay <= Duration.Zero)(
        "SCRAPE_BASE_RETRY_SECONDS must be positive"
      ),
      Option.when(
        config.scraping.maximumRetryDelay < config.scraping.baseRetryDelay
      )(
        "SCRAPE_MAX_RETRY_MINUTES cannot be shorter than SCRAPE_BASE_RETRY_SECONDS"
      ),
      Option.when(config.scraping.userAgent.trim.isEmpty)(
        "SCRAPE_USER_AGENT must not be blank"
      )
    ).flatten

    IO.raiseWhen(errors.nonEmpty)(IllegalArgumentException(errors.mkString("; ")))
  }
}
