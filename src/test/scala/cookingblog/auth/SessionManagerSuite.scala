package cookingblog.auth

import cats.effect.IO
import munit.CatsEffectSuite

import java.security.SecureRandom
import scala.concurrent.duration.*

final class SessionManagerSuite extends CatsEffectSuite {
  test("creates, authenticates, validates CSRF, and invalidates a session") {
    for {
      store <- InMemorySessionStore.create[IO]
      manager = SessionManager[IO](store, 24.hours, SecureRandom())
      issued <- manager.create(Principal("admin"))
      authenticated <- manager.authenticate(issued.token, Some(issued.csrfSecret))
      csrfIsValid <- authenticated.fold(IO.pure(false))(manager.validateCsrf(_, issued.csrfSecret))
      _ <- manager.invalidate(issued.token)
      afterLogout <- manager.authenticate(issued.token, Some(issued.csrfSecret))
    } yield {
      assertEquals(authenticated.map(_.record.principal), Some(Principal("admin")))
      assert(csrfIsValid)
      assertEquals(afterLogout, None)
    }
  }

  test("rejects an invalid CSRF secret") {
    for {
      store <- InMemorySessionStore.create[IO]
      manager = SessionManager[IO](store, 24.hours, SecureRandom())
      issued <- manager.create(Principal("admin"))
      authenticated <- manager.authenticate(issued.token, Some(issued.csrfSecret))
      csrfIsValid <- authenticated.fold(IO.pure(false))(manager.validateCsrf(_, "wrong"))
    } yield assert(!csrfIsValid)
  }
}
