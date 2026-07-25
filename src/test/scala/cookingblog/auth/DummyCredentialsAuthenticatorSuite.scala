package cookingblog.auth

import cats.effect.IO
import ciris.Secret
import cookingblog.config.AuthConfig
import munit.CatsEffectSuite

import scala.concurrent.duration.*

final class DummyCredentialsAuthenticatorSuite extends CatsEffectSuite {
  private val authenticator =
    DummyCredentialsAuthenticator[IO](
      AuthConfig("admin", Secret("test"), 24.hours, cookieSecure = false)
    )

  test("accepts the configured development credentials") {
    authenticator
      .authenticate("admin", "test")
      .map(result => assertEquals(result, Some(Principal("admin"))))
  }

  test("rejects invalid credentials") {
    authenticator
      .authenticate("admin", "wrong")
      .map(result => assertEquals(result, None))
  }
}
