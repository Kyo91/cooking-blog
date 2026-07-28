package cookingblog

import cats.effect.*
import cats.effect.std.Random
import com.comcast.ip4s.{Host, Port}
import cookingblog.auth.*
import cookingblog.config.*
import cookingblog.database.Database
import cookingblog.http.AppHttp
import cookingblog.observability.OperationalMetrics
import cookingblog.scraping.*
import cookingblog.service.{PhotoCleanup, PhotoService, RecipeApiService}
import cookingblog.storage.{LocalPhotoStore, PhotoStore}
import doobie.Transactor
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
      credentialsAuthenticator <- Resource.eval(authenticator(config))
      metrics <- Resource.eval(OperationalMetrics.create)
      photoStore <- photoStore(config.photos)
      photoCleanup = PhotoCleanup(photoStore)
      photoService = PhotoService(transactor, photoStore, photoCleanup, metrics)
      recipeService = RecipeApiService(transactor, photoCleanup)
      _ <- scraping(config.scraping, transactor, metrics)
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
      _ <- Resource.eval(
        logger.info(s"Cooking Blog listening on http://$host:$port")
      )
    } yield ()

  private def authenticator(
      config: AppConfig
  ): IO[CredentialsAuthenticator[IO]] =
    config.runtime.environment match {
      case RuntimeEnvironment.Development =>
        IO.pure(DummyCredentialsAuthenticator[IO](config.auth))
      case RuntimeEnvironment.Production =>
        ConfiguredCredentialsAuthenticator.create[IO](config.auth)
      case RuntimeEnvironment.Invalid(_) =>
        IO.raiseError(IllegalStateException("Invalid runtime environment"))
    }

  private def photoStore(config: PhotoConfig): Resource[IO, PhotoStore] =
    config match {
      case LocalPhotoConfig(directory) =>
        Resource.eval(LocalPhotoStore.create(directory))
      case _: S3PhotoConfig =>
        Resource.eval(
          IO.raiseError[PhotoStore](
            IllegalStateException(
              "PHOTO_BACKEND=s3 requires the Phase 10 S3PhotoStore implementation"
            )
          )
        )
      case InvalidPhotoConfig(backend) =>
        Resource.eval(
          IO.raiseError[PhotoStore](
            IllegalStateException(s"Unsupported photo backend: $backend")
          )
        )
    }

  private def scraping(
      config: ScrapeConfig,
      transactor: Transactor[IO],
      metrics: OperationalMetrics
  ): Resource[IO, Unit] =
    if (config.enabled) {
      for {
        client <- EmberClientBuilder
          .default[IO]
          .withMaxTotal(config.workerCount)
          .withMaxPerKey(_ => config.perHostConcurrency)
          .withTimeout(config.requestTimeout)
          .withIdleConnectionTime(config.totalJobTimeout)
          .build
        random <- Resource.eval(Random.scalaUtilRandom[IO])
        pageFetcher =
          SecurePageFetcher(client, config, NetworkSafety(SystemHostResolver))
        scrapeWorker =
          ScrapeWorker(
            transactor,
            config,
            HttpPageScraper(pageFetcher),
            random,
            metrics
          )
        _ <- scrapeWorker.run
      } yield ()
    } else {
      Resource.eval(
        logger.info(
          "Recipe scraping is disabled; durable pending jobs will remain queued"
        )
      )
    }

  private[cookingblog] def validateConfig(config: AppConfig): IO[Unit] = {
    val cloudDeployment =
      config.runtime.deploymentTarget == DeploymentTarget.Cloud
    val localPhotoDirectory =
      config.photos match {
        case LocalPhotoConfig(directory) => Some(directory)
        case _                           => None
      }
    val errors = List(
      Option.when(config.runtime.environment.isInstanceOf[RuntimeEnvironment.Invalid])(
        "APP_ENV must be development or production"
      ),
      Option.when(config.runtime.deploymentTarget.isInstanceOf[DeploymentTarget.Invalid])(
        "DEPLOYMENT_TARGET must be laptop or cloud"
      ),
      Option.when(
        cloudDeployment &&
          config.runtime.environment != RuntimeEnvironment.Production
      )(
        "DEPLOYMENT_TARGET=cloud requires APP_ENV=production"
      ),
      Option.when(config.database.poolSize <= 0)("DATABASE_POOL_SIZE must be positive"),
      Option.when(config.database.poolSize > 32)(
        "DATABASE_POOL_SIZE cannot exceed 32"
      ),
      Option.when(config.runtime.maximumRequestBytes < 10_100_000L)(
        "HTTP_MAX_REQUEST_BYTES must allow one maximum-size photo"
      ),
      Option.when(config.runtime.maximumRequestBytes > 110_000_000L)(
        "HTTP_MAX_REQUEST_BYTES cannot exceed 110000000"
      ),
      Option.when(config.auth.username.trim.isEmpty)(
        "AUTH_USERNAME must not be blank"
      ),
      Option.when(config.auth.sessionLifetime != 24.hours)(
        "AUTH_SESSION_HOURS must be 24 for the Phase 1 session policy"
      ),
      Option.when(config.scraping.enabled && config.scraping.workerCount <= 0)(
        "SCRAPE_WORKERS must be positive"
      ),
      Option.when(config.scraping.enabled && config.scraping.workerCount > 8)(
        "SCRAPE_WORKERS cannot exceed 8"
      ),
      Option.when(config.scraping.enabled && config.scraping.perHostConcurrency <= 0)(
        "SCRAPE_PER_HOST_CONCURRENCY must be positive"
      ),
      Option.when(
        config.scraping.enabled &&
          config.scraping.perHostConcurrency > config.scraping.workerCount
      )(
        "SCRAPE_PER_HOST_CONCURRENCY cannot exceed SCRAPE_WORKERS"
      ),
      Option.when(
        config.scraping.enabled && config.scraping.maximumResponseBytes <= 0
      )(
        "SCRAPE_MAX_RESPONSE_BYTES must be positive"
      ),
      Option.when(
        config.scraping.enabled &&
          config.scraping.maximumResponseBytes > Int.MaxValue.toLong
      )(
        s"SCRAPE_MAX_RESPONSE_BYTES cannot exceed ${Int.MaxValue}"
      ),
      Option.when(config.scraping.enabled && config.scraping.maximumRedirects < 0)(
        "SCRAPE_MAX_REDIRECTS cannot be negative"
      ),
      Option.when(config.scraping.enabled && config.scraping.maximumRedirects > 10)(
        "SCRAPE_MAX_REDIRECTS cannot exceed 10"
      ),
      Option.when(config.scraping.enabled && config.scraping.maximumAttempts <= 0)(
        "SCRAPE_MAX_ATTEMPTS must be positive"
      ),
      Option.when(config.scraping.enabled && config.scraping.maximumAttempts > 10)(
        "SCRAPE_MAX_ATTEMPTS cannot exceed 10"
      ),
      Option.when(
        config.scraping.enabled && config.scraping.pollInterval <= Duration.Zero
      )(
        "SCRAPE_POLL_MILLIS must be positive"
      ),
      Option.when(
        config.scraping.enabled && config.scraping.staleJobTimeout <= Duration.Zero
      )(
        "SCRAPE_STALE_JOB_MINUTES must be positive"
      ),
      Option.when(
        config.scraping.enabled && config.scraping.requestTimeout <= Duration.Zero
      )(
        "SCRAPE_REQUEST_SECONDS must be positive"
      ),
      Option.when(
        config.scraping.enabled && config.scraping.totalJobTimeout <= Duration.Zero
      )(
        "SCRAPE_TOTAL_JOB_SECONDS must be positive"
      ),
      Option.when(
        config.scraping.enabled && config.scraping.baseRetryDelay <= Duration.Zero
      )(
        "SCRAPE_BASE_RETRY_SECONDS must be positive"
      ),
      Option.when(
        config.scraping.enabled &&
          config.scraping.maximumRetryDelay < config.scraping.baseRetryDelay
      )(
        "SCRAPE_MAX_RETRY_MINUTES cannot be shorter than SCRAPE_BASE_RETRY_SECONDS"
      ),
      Option.when(config.scraping.enabled && config.scraping.userAgent.trim.isEmpty)(
        "SCRAPE_USER_AGENT must not be blank"
      ),
      Option.when(
        config.runtime.environment == RuntimeEnvironment.Production &&
          config.database.password.value == "cooking_blog_dev"
      )(
        "DATABASE_PASSWORD must not use the development default in production"
      ),
      Option.when(
        config.runtime.environment == RuntimeEnvironment.Production &&
          config.auth.password.value == "test"
      )(
        "AUTH_PASSWORD must not use the development default in production"
      ),
      Option.when(
        config.runtime.environment == RuntimeEnvironment.Production &&
          config.auth.password.value.length < 16
      )(
        "AUTH_PASSWORD must contain at least 16 characters in production"
      ),
      Option.when(
        cloudDeployment && !config.auth.cookieSecure
      )(
        "AUTH_COOKIE_SECURE must be true for cloud deployments"
      ),
      Option.when(
        cloudDeployment &&
          !config.http.publicOrigin.exists(origin =>
            origin.getScheme == "https" &&
              Option(origin.getHost).exists(_.nonEmpty) &&
              Option(origin.getUserInfo).isEmpty &&
              Option(origin.getQuery).isEmpty &&
              Option(origin.getFragment).isEmpty &&
              Set("", "/").contains(Option(origin.getPath).getOrElse(""))
          )
      )(
        "PUBLIC_ORIGIN must be an HTTPS origin without credentials, query, or fragment " +
          "for cloud deployments"
      ),
      Option.when(
        cloudDeployment && !config.photos.isInstanceOf[S3PhotoConfig]
      )(
        "PHOTO_BACKEND must be s3 for cloud deployments"
      ),
      Option.when(
        config.runtime.environment == RuntimeEnvironment.Production &&
          localPhotoDirectory.exists(directory => !directory.isAbsolute)
      )(
        "PHOTO_DIRECTORY must be absolute in production"
      )
    ).flatten

    val photoErrors =
      config.photos match {
        case InvalidPhotoConfig(_) =>
          List("PHOTO_BACKEND must be local or s3")
        case _: LocalPhotoConfig =>
          Nil
        case s3: S3PhotoConfig =>
          List(
            Option.when(s3.bucket.isEmpty)("PHOTO_S3_BUCKET must not be blank"),
            Option.when(s3.region.isEmpty)("PHOTO_S3_REGION must not be blank"),
            Option.when(
              s3.prefix
                .split("/")
                .exists(segment => segment == "." || segment == "..")
            )(
              "PHOTO_S3_PREFIX must not contain . or .. path segments"
            ),
            Option.when(
              s3.endpoint.exists(endpoint =>
                !Set("http", "https").contains(Option(endpoint.getScheme).getOrElse("")) ||
                  Option(endpoint.getHost).forall(_.isEmpty) ||
                  Option(endpoint.getUserInfo).nonEmpty ||
                  Option(endpoint.getQuery).nonEmpty ||
                  Option(endpoint.getFragment).nonEmpty ||
                  !Set("", "/").contains(Option(endpoint.getPath).getOrElse(""))
              )
            )(
              "PHOTO_S3_ENDPOINT must be an HTTP(S) origin without credentials, query, or fragment"
            ),
            Option.when(
              cloudDeployment &&
                s3.endpoint.exists(_.getScheme != "https")
            )(
              "PHOTO_S3_ENDPOINT must use HTTPS for cloud deployments"
            ),
            Option.when(
              s3.credentialsMode.isInstanceOf[S3CredentialsMode.Invalid]
            )(
              "PHOTO_S3_CREDENTIALS_MODE must be default or static"
            ),
            Option.when(
              s3.credentialsMode == S3CredentialsMode.Static &&
                s3.accessKeyId.isEmpty
            )(
              "PHOTO_S3_ACCESS_KEY_ID must not be blank for static credentials"
            ),
            Option.when(
              s3.credentialsMode == S3CredentialsMode.Static &&
                s3.secretAccessKey.value.isEmpty
            )(
              "PHOTO_S3_SECRET_ACCESS_KEY must not be blank for static credentials"
            ),
            Option.when(s3.maximumConcurrency <= 0)(
              "PHOTO_S3_MAX_CONCURRENCY must be positive"
            ),
            Option.when(s3.maximumConcurrency > 32)(
              "PHOTO_S3_MAX_CONCURRENCY cannot exceed 32"
            ),
            Option.when(s3.connectionTimeout <= Duration.Zero)(
              "PHOTO_S3_CONNECTION_TIMEOUT_SECONDS must be positive"
            ),
            Option.when(s3.requestTimeout <= Duration.Zero)(
              "PHOTO_S3_REQUEST_TIMEOUT_SECONDS must be positive"
            )
          ).flatten
      }

    val allErrors = errors ++ photoErrors
    IO.raiseWhen(allErrors.nonEmpty)(IllegalArgumentException(allErrors.mkString("; ")))
  }
}
