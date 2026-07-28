package cookingblog.config

import cats.data.ValidatedNec
import cats.effect.IO
import cats.syntax.all.*
import ciris.*

import scala.concurrent.duration.*
import java.net.URI
import java.nio.file.{Path, Paths}

final case class HttpConfig(host: String, port: Int, publicOrigin: Option[URI])

enum RuntimeEnvironment {
  case Development
  case Production
}

enum DeploymentTarget {
  case Laptop
  case Cloud
}

final case class RuntimeConfig(
    environment: RuntimeEnvironment,
    deploymentTarget: DeploymentTarget,
    maximumRequestBytes: Long
)

final case class DatabaseConfig(
    url: String,
    user: String,
    password: Secret[String],
    poolSize: Int
)

final case class AuthConfig(
    username: String,
    password: Secret[String],
    sessionLifetime: FiniteDuration,
    cookieSecure: Boolean
)

sealed trait PhotoConfig

final case class LocalPhotoConfig(directory: Path) extends PhotoConfig

enum S3CredentialsMode {
  case Default
  case Static
}

final case class S3PhotoConfig(
    bucket: String,
    prefix: String,
    region: String,
    endpoint: Option[URI],
    pathStyleAccess: Boolean,
    credentialsMode: S3CredentialsMode,
    accessKeyId: String,
    secretAccessKey: Secret[String],
    maximumConcurrency: Int,
    connectionTimeout: FiniteDuration,
    requestTimeout: FiniteDuration
) extends PhotoConfig

final case class ScrapeConfig(
    enabled: Boolean,
    workerCount: Int,
    perHostConcurrency: Int,
    pollInterval: FiniteDuration,
    staleJobTimeout: FiniteDuration,
    requestTimeout: FiniteDuration,
    totalJobTimeout: FiniteDuration,
    maximumResponseBytes: Long,
    maximumRedirects: Int,
    maximumAttempts: Int,
    baseRetryDelay: FiniteDuration,
    maximumRetryDelay: FiniteDuration,
    userAgent: String
)

final case class AppConfig(
    runtime: RuntimeConfig,
    http: HttpConfig,
    database: DatabaseConfig,
    auth: AuthConfig,
    photos: PhotoConfig,
    scraping: ScrapeConfig
)

object AppConfig {
  private def secret(
      environmentName: String,
      fileEnvironmentName: String,
      developmentDefault: String
  ): ConfigValue[Effect, Secret[String]] =
    env(fileEnvironmentName)
      .flatMap(path => file(Paths.get(path)).map(_.trim))
      .or(env(environmentName))
      .default(developmentDefault)
      .secret

  private val runtime =
    (
      env("APP_ENV")
        .as[String]
        .default("development")
        .map(_.trim.toLowerCase),
      env("DEPLOYMENT_TARGET")
        .as[String]
        .default("laptop")
        .map(_.trim.toLowerCase),
      env("HTTP_MAX_REQUEST_BYTES").as[Long].default(105_000_000L)
    ).parMapN { (environment, deploymentTarget, maximumRequestBytes) =>
      (
        parseEnvironment(environment),
        parseDeploymentTarget(deploymentTarget)
      ).mapN((parsedEnvironment, parsedDeploymentTarget) =>
        RuntimeConfig(parsedEnvironment, parsedDeploymentTarget, maximumRequestBytes)
      )
    }

  private val http =
    (
      env("HTTP_HOST").as[String].default("127.0.0.1"),
      env("HTTP_PORT").as[Int].default(8080),
      env("PUBLIC_ORIGIN")
        .as[String]
        .default("")
        .map(value => Option(value.trim).filter(_.nonEmpty).map(URI.create))
    ).parMapN(HttpConfig.apply)

  private val database =
    (
      env("DATABASE_URL")
        .as[String]
        .default("jdbc:postgresql://localhost:5432/cooking_blog"),
      env("DATABASE_USER").as[String].default("cooking_blog"),
      secret(
        "DATABASE_PASSWORD",
        "DATABASE_PASSWORD_FILE",
        "cooking_blog_dev"
      ),
      env("DATABASE_POOL_SIZE").as[Int].default(4)
    ).parMapN(DatabaseConfig.apply)

  private val auth =
    (
      env("AUTH_USERNAME").as[String].default("admin"),
      secret("AUTH_PASSWORD", "AUTH_PASSWORD_FILE", "test"),
      env("AUTH_SESSION_HOURS").as[Long].default(24L),
      env("AUTH_COOKIE_SECURE").as[Boolean].default(false)
    ).parMapN { (username, password, sessionHours, cookieSecure) =>
      AuthConfig(username, password, sessionHours.hours, cookieSecure)
    }

  private val photos =
    (
      env("PHOTO_BACKEND").as[String].default("local"),
      env("PHOTO_DIRECTORY").as[String].default("./data/photos"),
      env("PHOTO_S3_BUCKET").as[String].default(""),
      env("PHOTO_S3_PREFIX").as[String].default("cooking-blog/photos"),
      env("PHOTO_S3_REGION").as[String].default("us-east-1"),
      env("PHOTO_S3_ENDPOINT")
        .as[String]
        .default("")
        .map(value => Option(value.trim).filter(_.nonEmpty).map(URI.create)),
      env("PHOTO_S3_PATH_STYLE").as[Boolean].default(false),
      env("PHOTO_S3_CREDENTIALS_MODE").as[String].default("default"),
      env("PHOTO_S3_ACCESS_KEY_ID").as[String].default(""),
      secret(
        "PHOTO_S3_SECRET_ACCESS_KEY",
        "PHOTO_S3_SECRET_ACCESS_KEY_FILE",
        ""
      ),
      env("PHOTO_S3_MAX_CONCURRENCY").as[Int].default(4),
      env("PHOTO_S3_CONNECTION_TIMEOUT_SECONDS").as[Long].default(5L),
      env("PHOTO_S3_REQUEST_TIMEOUT_SECONDS").as[Long].default(30L)
    ).parMapN {
      (
          rawBackend,
          directory,
          bucket,
          prefix,
          region,
          endpoint,
          pathStyleAccess,
          rawCredentialsMode,
          accessKeyId,
          secretAccessKey,
          maximumConcurrency,
          connectionTimeoutSeconds,
          requestTimeoutSeconds
      ) =>
        rawBackend.trim.toLowerCase match {
          case "local" => LocalPhotoConfig(Paths.get(directory)).validNec
          case "s3"    =>
            parseCredentialsMode(rawCredentialsMode).map(credentialsMode =>
              S3PhotoConfig(
                bucket.trim,
                prefix.trim.stripPrefix("/").stripSuffix("/"),
                region.trim,
                endpoint,
                pathStyleAccess,
                credentialsMode,
                accessKeyId.trim,
                secretAccessKey,
                maximumConcurrency,
                connectionTimeoutSeconds.seconds,
                requestTimeoutSeconds.seconds
              )
            )
          case value => s"PHOTO_BACKEND must be local or s3 (was $value)".invalidNec
        }
    }

  private val scraping =
    (
      env("SCRAPE_ENABLED").as[Boolean].default(true),
      env("SCRAPE_WORKERS").as[Int].default(2),
      env("SCRAPE_PER_HOST_CONCURRENCY").as[Int].default(1),
      env("SCRAPE_POLL_MILLIS").as[Long].default(500L),
      env("SCRAPE_STALE_JOB_MINUTES").as[Long].default(5L),
      env("SCRAPE_REQUEST_SECONDS").as[Long].default(15L),
      env("SCRAPE_TOTAL_JOB_SECONDS").as[Long].default(45L),
      env("SCRAPE_MAX_RESPONSE_BYTES").as[Long].default(2_000_000L),
      env("SCRAPE_MAX_REDIRECTS").as[Int].default(5),
      env("SCRAPE_MAX_ATTEMPTS").as[Int].default(5),
      env("SCRAPE_BASE_RETRY_SECONDS").as[Long].default(30L),
      env("SCRAPE_MAX_RETRY_MINUTES").as[Long].default(60L),
      env("SCRAPE_USER_AGENT")
        .as[String]
        .default("CookingBlog/0.1 (+personal recipe archive)")
    ).parMapN {
      (
          enabled,
          workerCount,
          perHostConcurrency,
          pollMillis,
          staleJobMinutes,
          requestSeconds,
          totalJobSeconds,
          maximumResponseBytes,
          maximumRedirects,
          maximumAttempts,
          baseRetrySeconds,
          maximumRetryMinutes,
          userAgent
      ) =>
        ScrapeConfig(
          enabled,
          workerCount,
          perHostConcurrency,
          pollMillis.millis,
          staleJobMinutes.minutes,
          requestSeconds.seconds,
          totalJobSeconds.seconds,
          maximumResponseBytes,
          maximumRedirects,
          maximumAttempts,
          baseRetrySeconds.seconds,
          maximumRetryMinutes.minutes,
          userAgent
        )
    }

  private val values: ConfigValue[Effect, ValidatedNec[String, AppConfig]] =
    (runtime, http, database, auth, photos, scraping).parMapN {
      (parsedRuntime, http, database, auth, parsedPhotos, scraping) =>
        (parsedRuntime, parsedPhotos).mapN((runtime, photos) =>
          AppConfig(runtime, http, database, auth, photos, scraping)
        )
    }

  /** Parses environment configuration into an application configuration that satisfies all runtime
    * invariants. Invalid external input never reaches resource assembly.
    */
  def load: IO[ValidatedNec[String, AppConfig]] =
    values.load[IO].attempt.map {
      case Right(config) => config.andThen(parse)
      case Left(error)   => error.getMessage.invalidNec[AppConfig]
    }

  def parse(config: AppConfig): ValidatedNec[String, AppConfig] = {
    val cloudDeployment = config.runtime.deploymentTarget == DeploymentTarget.Cloud
    val localPhotoDirectory = config.photos match {
      case LocalPhotoConfig(directory) => Some(directory)
      case _                           => None
    }
    val generalErrors = List(
      Option.when(cloudDeployment && config.runtime.environment != RuntimeEnvironment.Production)(
        "DEPLOYMENT_TARGET=cloud requires APP_ENV=production"
      ),
      Option.when(config.database.poolSize <= 0)("DATABASE_POOL_SIZE must be positive"),
      Option.when(config.database.poolSize > 32)("DATABASE_POOL_SIZE cannot exceed 32"),
      Option.when(config.runtime.maximumRequestBytes < 10_100_000L)(
        "HTTP_MAX_REQUEST_BYTES must allow one maximum-size photo"
      ),
      Option.when(config.runtime.maximumRequestBytes > 110_000_000L)(
        "HTTP_MAX_REQUEST_BYTES cannot exceed 110000000"
      ),
      Option.when(config.auth.username.trim.isEmpty)("AUTH_USERNAME must not be blank"),
      Option.when(config.auth.sessionLifetime != 24.hours)(
        "AUTH_SESSION_HOURS must be 24 for the Phase 1 session policy"
      ),
      Option.when(
        config.runtime.environment == RuntimeEnvironment.Production && config.database.password.value == "cooking_blog_dev"
      )(
        "DATABASE_PASSWORD must not use the development default in production"
      ),
      Option.when(
        config.runtime.environment == RuntimeEnvironment.Production && config.auth.password.value == "test"
      )(
        "AUTH_PASSWORD must not use the development default in production"
      ),
      Option.when(
        config.runtime.environment == RuntimeEnvironment.Production && config.auth.password.value.length < 16
      )(
        "AUTH_PASSWORD must contain at least 16 characters in production"
      ),
      Option.when(cloudDeployment && !config.auth.cookieSecure)(
        "AUTH_COOKIE_SECURE must be true for cloud deployments"
      ),
      Option.when(cloudDeployment && !config.http.publicOrigin.exists(validCloudOrigin))(
        "PUBLIC_ORIGIN must be an HTTPS origin without credentials, query, or fragment for cloud deployments"
      ),
      Option.when(cloudDeployment && !config.photos.isInstanceOf[S3PhotoConfig])(
        "PHOTO_BACKEND must be s3 for cloud deployments"
      ),
      Option.when(
        config.runtime.environment == RuntimeEnvironment.Production && localPhotoDirectory.exists(
          directory => !directory.isAbsolute
        )
      )(
        "PHOTO_DIRECTORY must be absolute in production"
      )
    ).flatten
    val scrapeErrors = if (config.scraping.enabled) {
      List(
        Option.when(config.scraping.workerCount <= 0)("SCRAPE_WORKERS must be positive"),
        Option.when(config.scraping.workerCount > 8)("SCRAPE_WORKERS cannot exceed 8"),
        Option.when(config.scraping.perHostConcurrency <= 0)(
          "SCRAPE_PER_HOST_CONCURRENCY must be positive"
        ),
        Option.when(config.scraping.perHostConcurrency > config.scraping.workerCount)(
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
        Option.when(config.scraping.maximumRedirects > 10)("SCRAPE_MAX_REDIRECTS cannot exceed 10"),
        Option.when(config.scraping.maximumAttempts <= 0)("SCRAPE_MAX_ATTEMPTS must be positive"),
        Option.when(config.scraping.maximumAttempts > 10)("SCRAPE_MAX_ATTEMPTS cannot exceed 10"),
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
        Option.when(config.scraping.maximumRetryDelay < config.scraping.baseRetryDelay)(
          "SCRAPE_MAX_RETRY_MINUTES cannot be shorter than SCRAPE_BASE_RETRY_SECONDS"
        ),
        Option.when(config.scraping.userAgent.trim.isEmpty)("SCRAPE_USER_AGENT must not be blank")
      ).flatten
    } else Nil
    val photoErrors = config.photos match {
      case _: LocalPhotoConfig => Nil
      case s3: S3PhotoConfig   =>
        List(
          Option.when(s3.bucket.isEmpty)("PHOTO_S3_BUCKET must not be blank"),
          Option.when(s3.region.isEmpty)("PHOTO_S3_REGION must not be blank"),
          Option.when(s3.prefix.split("/").exists(segment => segment == "." || segment == ".."))(
            "PHOTO_S3_PREFIX must not contain . or .. path segments"
          ),
          Option.when(s3.endpoint.exists(endpoint => !validEndpoint(endpoint)))(
            "PHOTO_S3_ENDPOINT must be an HTTP(S) origin without credentials, query, or fragment"
          ),
          Option.when(cloudDeployment && s3.endpoint.exists(_.getScheme != "https"))(
            "PHOTO_S3_ENDPOINT must use HTTPS for cloud deployments"
          ),
          Option.when(s3.credentialsMode == S3CredentialsMode.Static && s3.accessKeyId.isEmpty)(
            "PHOTO_S3_ACCESS_KEY_ID must not be blank for static credentials"
          ),
          Option.when(
            s3.credentialsMode == S3CredentialsMode.Static && s3.secretAccessKey.value.isEmpty
          )("PHOTO_S3_SECRET_ACCESS_KEY must not be blank for static credentials"),
          Option.when(s3.maximumConcurrency <= 0)("PHOTO_S3_MAX_CONCURRENCY must be positive"),
          Option.when(s3.maximumConcurrency > 32)("PHOTO_S3_MAX_CONCURRENCY cannot exceed 32"),
          Option.when(s3.connectionTimeout <= Duration.Zero)(
            "PHOTO_S3_CONNECTION_TIMEOUT_SECONDS must be positive"
          ),
          Option.when(s3.requestTimeout <= Duration.Zero)(
            "PHOTO_S3_REQUEST_TIMEOUT_SECONDS must be positive"
          )
        ).flatten
    }
    (generalErrors ++ scrapeErrors ++ photoErrors)
      .traverse_(error => error.invalidNec[Unit])
      .as(config)
  }

  private def validCloudOrigin(origin: URI): Boolean =
    origin.getScheme == "https" && Option(origin.getHost).exists(_.nonEmpty) &&
      Option(origin.getUserInfo).isEmpty && Option(origin.getQuery).isEmpty &&
      Option(origin.getFragment).isEmpty && Set("", "/").contains(
        Option(origin.getPath).getOrElse("")
      )

  private def validEndpoint(endpoint: URI): Boolean =
    Set("http", "https").contains(Option(endpoint.getScheme).getOrElse("")) &&
      Option(endpoint.getHost).exists(_.nonEmpty) && Option(endpoint.getUserInfo).isEmpty &&
      Option(endpoint.getQuery).isEmpty && Option(endpoint.getFragment).isEmpty &&
      Set("", "/").contains(Option(endpoint.getPath).getOrElse(""))

  private def parseEnvironment(value: String): ValidatedNec[String, RuntimeEnvironment] =
    value match {
      case "development" => RuntimeEnvironment.Development.validNec
      case "production"  => RuntimeEnvironment.Production.validNec
      case other         => s"APP_ENV must be development or production (was $other)".invalidNec
    }

  private def parseDeploymentTarget(value: String): ValidatedNec[String, DeploymentTarget] =
    value match {
      case "laptop" => DeploymentTarget.Laptop.validNec
      case "cloud"  => DeploymentTarget.Cloud.validNec
      case other    => s"DEPLOYMENT_TARGET must be laptop or cloud (was $other)".invalidNec
    }

  private def parseCredentialsMode(value: String): ValidatedNec[String, S3CredentialsMode] =
    value.trim.toLowerCase match {
      case "default" => S3CredentialsMode.Default.validNec
      case "static"  => S3CredentialsMode.Static.validNec
      case other => s"PHOTO_S3_CREDENTIALS_MODE must be default or static (was $other)".invalidNec
    }
}
