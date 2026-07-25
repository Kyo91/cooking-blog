package cookingblog.auth

import cats.effect.Sync
import cookingblog.config.AuthConfig

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

trait CredentialsAuthenticator[F[_]] {
  def authenticate(username: String, password: String): F[Option[Principal]]
}

final class DummyCredentialsAuthenticator[F[_]: Sync](config: AuthConfig)
    extends CredentialsAuthenticator[F] {
  override def authenticate(username: String, password: String): F[Option[Principal]] =
    Sync[F].delay {
      val usernameMatches = constantTimeEquals(username, config.username)
      val passwordMatches = constantTimeEquals(password, config.password.value)

      Option.when(usernameMatches && passwordMatches)(Principal(config.username))
    }

  private def constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
      left.getBytes(StandardCharsets.UTF_8),
      right.getBytes(StandardCharsets.UTF_8)
    )
}
