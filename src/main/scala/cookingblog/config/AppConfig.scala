package cookingblog.config

import cats.syntax.all.*
import ciris.*

import scala.concurrent.duration.*

final case class HttpConfig(host: String, port: Int)

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

final case class AppConfig(http: HttpConfig, database: DatabaseConfig, auth: AuthConfig)

object AppConfig {
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
      env("DATABASE_PASSWORD").secret.default(Secret("cooking_blog_dev")),
      env("DATABASE_POOL_SIZE").as[Int].default(4)
    ).parMapN(DatabaseConfig.apply)

  private val auth =
    (
      env("AUTH_USERNAME").as[String].default("admin"),
      env("AUTH_PASSWORD").secret.default(Secret("test")),
      env("AUTH_SESSION_HOURS").as[Long].default(24L),
      env("AUTH_COOKIE_SECURE").as[Boolean].default(false)
    ).parMapN { (username, password, sessionHours, cookieSecure) =>
      AuthConfig(username, password, sessionHours.hours, cookieSecure)
    }

  val load: ConfigValue[Effect, AppConfig] =
    (http, database, auth).parMapN(AppConfig.apply)
}
