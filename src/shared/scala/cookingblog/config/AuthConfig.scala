package cookingblog.config

import ciris.Secret

import scala.concurrent.duration.FiniteDuration

final case class AuthConfig(
    username: String,
    password: Secret[String],
    sessionLifetime: FiniteDuration,
    cookieSecure: Boolean
)
