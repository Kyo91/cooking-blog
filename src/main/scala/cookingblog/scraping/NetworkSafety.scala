package cookingblog.scraping

import cats.effect.IO
import org.http4s.Uri

import java.net.{Inet4Address, Inet6Address, InetAddress}
import java.util.Locale

trait HostResolver {
  def resolve(host: String): IO[List[InetAddress]]
}

object SystemHostResolver extends HostResolver {
  override def resolve(host: String): IO[List[InetAddress]] =
    IO.blocking(InetAddress.getAllByName(host).toList)
}

final class NetworkSafety(resolver: HostResolver) {
  def validate(uri: Uri): IO[Unit] = {
    val scheme = uri.scheme.map(_.value.toLowerCase(Locale.ROOT))
    val host = uri.host.map(_.value)
    if (!scheme.exists(value => value == "http" || value == "https")) {
      IO.raiseError(
        ScrapeFailure("Only http and https URLs can be scraped", retryable = false)
      )
    } else if (host.isEmpty || uri.userInfo.nonEmpty) {
      IO.raiseError(
        ScrapeFailure(
          "Scrape URLs must have a host and cannot contain credentials",
          retryable = false
        )
      )
    } else {
      resolver
        .resolve(host.get)
        .adaptError(error =>
          ScrapeFailure(
            "The destination host could not be resolved",
            retryable = true,
            Some(error)
          )
        )
        .flatMap { addresses =>
          IO.raiseWhen(addresses.isEmpty || addresses.exists(!isPublic(_)))(
            ScrapeFailure(
              "The destination resolves to a non-public network address",
              retryable = false
            )
          )
        }
    }
  }

  private[scraping] def isPublic(address: InetAddress): Boolean =
    !address.isAnyLocalAddress &&
      !address.isLoopbackAddress &&
      !address.isLinkLocalAddress &&
      !address.isSiteLocalAddress &&
      !address.isMulticastAddress &&
      (address match {
        case ipv4: Inet4Address => publicIpv4(ipv4.getAddress)
        case ipv6: Inet6Address => publicIpv6(ipv6.getAddress)
        case _                  => false
      })

  private def publicIpv4(bytes: Array[Byte]): Boolean = {
    val first = unsigned(bytes(0))
    val second = unsigned(bytes(1))
    val third = unsigned(bytes(2))
    val special =
      first == 0 ||
        first == 10 ||
        first == 127 ||
        first >= 224 ||
        (first == 100 && second >= 64 && second <= 127) ||
        (first == 169 && second == 254) ||
        (first == 172 && second >= 16 && second <= 31) ||
        (first == 192 && second == 0 && third == 0) ||
        (first == 192 && second == 0 && third == 2) ||
        (first == 192 && second == 88 && third == 99) ||
        (first == 192 && second == 168) ||
        (first == 198 && (second == 18 || second == 19)) ||
        (first == 198 && second == 51 && third == 100) ||
        (first == 203 && second == 0 && third == 113)
    !special
  }

  private def publicIpv6(bytes: Array[Byte]): Boolean = {
    val first = unsigned(bytes(0))
    val second = unsigned(bytes(1))
    val documentation =
      first == 0x20 && second == 0x01 &&
        unsigned(bytes(2)) == 0x0d && unsigned(bytes(3)) == 0xb8
    val uniqueLocal = (first & 0xfe) == 0xfc
    val ipv4Mapped =
      bytes.take(10).forall(_ == 0) &&
        unsigned(bytes(10)) == 0xff &&
        unsigned(bytes(11)) == 0xff
    val mappedPublic =
      !ipv4Mapped || publicIpv4(bytes.drop(12))
    !documentation && !uniqueLocal && mappedPublic
  }

  private def unsigned(value: Byte): Int = value & 0xff
}
