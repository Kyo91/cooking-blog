package cookingblog.auth

import cats.effect.IO
import cats.syntax.all.*
import ciris.Secret
import cookingblog.config.AuthConfig
import munit.CatsEffectSuite

import scala.concurrent.duration.*

final class ConfiguredCredentialsAuthenticatorSuite extends CatsEffectSuite {
  private val config =
    AuthConfig(
      "admin",
      Secret("a-production-password"),
      24.hours,
      cookieSecure = true
    )

  test("accepts configured credentials and resets the failed-login backoff") {
    for {
      authenticator <- ConfiguredCredentialsAuthenticator.create[IO](
        config,
        1.millis,
        2.millis
      )
      rejected <- authenticator.authenticate("admin", "wrong")
      failuresAfterRejection <- authenticator.failureCount
      accepted <- authenticator.authenticate("admin", "a-production-password")
      failuresAfterSuccess <- authenticator.failureCount
    } yield {
      assertEquals(rejected, None)
      assertEquals(failuresAfterRejection, 1)
      assertEquals(accepted, Some(Principal("admin")))
      assertEquals(failuresAfterSuccess, 0)
    }
  }

  test("tracks consecutive failed logins") {
    for {
      authenticator <- ConfiguredCredentialsAuthenticator.create[IO](
        config,
        1.millisecond,
        1.millisecond
      )
      _ <- List.fill(3)(()).traverse_(_ => authenticator.authenticate("unknown", "wrong"))
      failures <- authenticator.failureCount
    } yield assertEquals(failures, 3)
  }
}
