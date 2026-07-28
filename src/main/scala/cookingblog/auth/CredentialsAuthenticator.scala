package cookingblog.auth

import cats.effect.{Async, Ref, Sync}
import cats.syntax.all.*
import cookingblog.config.AuthConfig

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.concurrent.duration.*

/** Verifies supplied credentials and yields the authenticated principal when valid. */
trait CredentialsAuthenticator[F[_]] {
  def authenticate(username: String, password: String): F[Option[Principal]]
}

final class DummyCredentialsAuthenticator[F[_]: Sync](config: AuthConfig)
    extends CredentialsAuthenticator[F] {
  override def authenticate(username: String, password: String): F[Option[Principal]] =
    Sync[F].delay {
      val usernameMatches =
        CredentialsAuthenticator.constantTimeEquals(username, config.username)
      val passwordMatches =
        CredentialsAuthenticator.constantTimeEquals(password, config.password.value)

      Option.when(usernameMatches && passwordMatches)(Principal(config.username))
    }
}

/** Single configured production user with process-local, bounded backoff after failed logins. */
final class ConfiguredCredentialsAuthenticator[F[_]: Async] private (
    config: AuthConfig,
    consecutiveFailures: Ref[F, Int],
    baseFailureDelay: FiniteDuration,
    maximumFailureDelay: FiniteDuration
) extends CredentialsAuthenticator[F] {
  private[auth] def failureCount: F[Int] = consecutiveFailures.get

  override def authenticate(username: String, password: String): F[Option[Principal]] =
    Async[F]
      .delay {
        val usernameMatches =
          CredentialsAuthenticator.constantTimeEquals(username, config.username)
        val passwordMatches =
          CredentialsAuthenticator.constantTimeEquals(password, config.password.value)
        usernameMatches && passwordMatches
      }
      .flatMap {
        case true =>
          consecutiveFailures.set(0).as(Some(Principal(config.username)))
        case false =>
          consecutiveFailures
            .modify { previousFailures =>
              val failures = math.min(previousFailures, 20) + 1
              val exponent = math.min(failures - 1, 20)
              val delay =
                (baseFailureDelay * (1L << exponent)).min(maximumFailureDelay)
              (failures, delay)
            }
            .flatMap(Async[F].sleep)
            .as(None)
      }
}

object ConfiguredCredentialsAuthenticator {
  def create[F[_]: Async](
      config: AuthConfig
  ): F[ConfiguredCredentialsAuthenticator[F]] =
    create(config, 100.millis, 2.seconds)

  private[auth] def create[F[_]: Async](
      config: AuthConfig,
      baseFailureDelay: FiniteDuration,
      maximumFailureDelay: FiniteDuration
  ): F[ConfiguredCredentialsAuthenticator[F]] =
    Ref
      .of[F, Int](0)
      .map(
        new ConfiguredCredentialsAuthenticator(
          config,
          _,
          baseFailureDelay,
          maximumFailureDelay
        )
      )
}

object CredentialsAuthenticator {
  private[auth] def constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
      left.getBytes(StandardCharsets.UTF_8),
      right.getBytes(StandardCharsets.UTF_8)
    )
}
