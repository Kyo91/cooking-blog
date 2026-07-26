package cookingblog.scraping

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.Uri

import java.net.InetAddress

final class NetworkSafetySuite extends CatsEffectSuite {
  private val publicAddress = InetAddress.getByName("93.184.216.34")

  test("allows public HTTP destinations") {
    val safety = NetworkSafety(_ => IO.pure(List(publicAddress)))

    safety.validate(Uri.unsafeFromString("https://example.com/recipe"))
  }

  test("rejects credentials and non-HTTP schemes") {
    val safety = NetworkSafety(_ => IO.pure(List(publicAddress)))

    (
      safety
        .validate(Uri.unsafeFromString("https://user:secret@example.com/recipe"))
        .attempt,
      safety.validate(Uri.unsafeFromString("file:///etc/passwd")).attempt
    ).mapN { (credentials, scheme) =>
      assert(credentials.isLeft)
      assert(scheme.isLeft)
    }
  }

  test("rejects loopback, private, link-local, carrier, and documentation ranges") {
    val addresses =
      List(
        "127.0.0.1",
        "10.0.0.1",
        "172.16.0.1",
        "192.168.1.1",
        "169.254.169.254",
        "100.64.0.1",
        "192.0.2.1",
        "198.51.100.1",
        "203.0.113.1",
        "::1",
        "fc00::1",
        "2001:db8::1"
      ).map(InetAddress.getByName)
    val safety = NetworkSafety(_ => IO.pure(addresses))

    safety
      .validate(Uri.unsafeFromString("https://blocked.example/recipe"))
      .attempt
      .map(result => assert(result.isLeft))
  }
}
