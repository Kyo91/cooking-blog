package cookingblog.auth

import cats.effect.kernel.{Clock, Sync}
import cats.syntax.all.*

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import scala.concurrent.duration.FiniteDuration

final class SessionManager[F[_]: Clock: Sync](
    store: SessionStore[F],
    sessionLifetime: FiniteDuration,
    secureRandom: SecureRandom
) {
  def create(principal: Principal): F[IssuedSession] =
    for {
      now <- Clock[F].realTimeInstant
      token <- randomToken
      csrfSecret <- randomToken
      expiresAt = now.plusMillis(sessionLifetime.toMillis)
      record = SessionRecord(
        hash(token),
        principal,
        hash(csrfSecret),
        now,
        expiresAt,
        None
      )
      _ <- store.create(record)
    } yield IssuedSession(token, csrfSecret, principal, expiresAt)

  def authenticate(token: String, csrfSecret: Option[String]): F[Option[AuthenticatedSession]] =
    Clock[F].realTimeInstant
      .flatMap(store.findActive(hash(token), _))
      .map(
        _.map(AuthenticatedSession(token, csrfSecret, _))
      )

  def validateCsrf(session: AuthenticatedSession, submittedSecret: String): F[Boolean] =
    Sync[F].delay(
      MessageDigest.isEqual(
        hash(submittedSecret).getBytes(StandardCharsets.UTF_8),
        session.record.csrfSecretHash.getBytes(StandardCharsets.UTF_8)
      )
    )

  def invalidate(token: String): F[Unit] =
    Clock[F].realTimeInstant.flatMap(store.invalidate(hash(token), _))

  def deleteExpiredOrInvalidated: F[Int] =
    Clock[F].realTimeInstant.flatMap(store.deleteExpiredOrInvalidated)

  private def randomToken: F[String] =
    Sync[F].delay {
      val bytes = Array.ofDim[Byte](32)
      secureRandom.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }

  private def hash(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8))
    bytes.map(byte => f"${byte & 0xff}%02x").mkString
  }
}
