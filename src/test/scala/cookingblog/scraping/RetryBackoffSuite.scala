package cookingblog.scraping

import munit.FunSuite

import scala.concurrent.duration.*

final class RetryBackoffSuite extends FunSuite {
  test("uses capped exponential backoff with bounded jitter") {
    assertEquals(
      RetryBackoff.delay(1, 30.seconds, 10.minutes, 0.5d),
      30.seconds
    )
    assertEquals(
      RetryBackoff.delay(3, 30.seconds, 10.minutes, 0.5d),
      2.minutes
    )
    assertEquals(
      RetryBackoff.delay(20, 30.seconds, 10.minutes, 1d),
      10.minutes
    )
  }
}
