package cookingblog.config

import cats.syntax.all.*
import ciris.*

import scala.concurrent.duration.*
import java.nio.file.{Path, Paths}

final case class HttpConfig(host: String, port: Int)

enum RuntimeEnvironment {
  case Development
  case Production
  case Invalid(value: String)
}

final case class RuntimeConfig(
    environment: RuntimeEnvironment,
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

final case class PhotoConfig(directory: Path)

final case class ScrapeConfig(
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
        .map(_.trim.toLowerCase)
        .map {
          case "development" => RuntimeEnvironment.Development
          case "production"  => RuntimeEnvironment.Production
          case value         => RuntimeEnvironment.Invalid(value)
        },
      env("HTTP_MAX_REQUEST_BYTES").as[Long].default(105_000_000L)
    ).parMapN(RuntimeConfig.apply)

  private val http =
    (
      env("HTTP_HOST").as[String].default("127.0.0.1"),
      env("HTTP_PORT").as[Int].default(8080)
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
    env("PHOTO_DIRECTORY")
      .as[String]
      .default("./data/photos")
      .map(value => PhotoConfig(Paths.get(value)))

  private val scraping =
    (
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

  val load: ConfigValue[Effect, AppConfig] =
    (runtime, http, database, auth, photos, scraping).parMapN(AppConfig.apply)
}
