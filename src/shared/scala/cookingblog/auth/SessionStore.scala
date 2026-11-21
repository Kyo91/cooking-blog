package cookingblog.auth

import cookingblog.database.Database
import cats.effect.kernel.Ref
import cats.effect.{IO, Sync}
import cookingblog.database.Database.{affectedRows, instant}
import skunk.codec.all.text
import skunk.implicits.*
import cats.syntax.all.*

import java.time.Instant

/** Persistence boundary for hashed, absolute-lifetime browser sessions. */
trait SessionStore[F[_]] {
  def create(session: SessionRecord): F[Unit]
  def findActive(tokenHash: String, now: Instant): F[Option[SessionRecord]]
  def invalidate(tokenHash: String, now: Instant): F[Unit]
  def deleteExpiredOrInvalidated(now: Instant): F[Int]
}

final class SkunkSessionStore(pool: Database) extends SessionStore[IO] {
  private val principalCodec = text.imap(Principal.apply)(_.name)
  private val sessionCodec =
    (text *: principalCodec *: text *: instant *: instant *: instant.opt).to[SessionRecord]
  private val columns =
    sql"token_hash, principal, csrf_secret_hash, created_at, expires_at, invalidated_at"

  override def create(session: SessionRecord): IO[Unit] =
    pool
      .withSession(_.execute(sql"""
      insert into auth_sessions ($columns) values ($sessionCodec)
    """.command)(session))
      .void

  override def findActive(
      tokenHash: String,
      now: Instant
  ): IO[Option[SessionRecord]] =
    pool.withSession(_.option(sql"""
      select $columns
      from auth_sessions
      where token_hash = $text
        and invalidated_at is null
        and expires_at > $instant
    """.query(sessionCodec))((tokenHash, now)))

  override def invalidate(tokenHash: String, now: Instant): IO[Unit] =
    pool
      .withSession(_.execute(sql"""
      update auth_sessions
      set invalidated_at = $instant
      where token_hash = $text
        and invalidated_at is null
    """.command)((now, tokenHash)))
      .void

  override def deleteExpiredOrInvalidated(now: Instant): IO[Int] =
    pool
      .withSession(_.execute(sql"""
      delete from auth_sessions
      where expires_at <= $instant
        or invalidated_at is not null
    """.command)(now))
      .map(affectedRows)
}

object InMemorySessionStore {
  def create[F[_]: Sync]: F[InMemorySessionStore[F]] =
    Ref.of[F, Map[String, SessionRecord]](Map.empty).map(new InMemorySessionStore(_))
}

final class InMemorySessionStore[F[_]: Sync] private (
    sessions: Ref[F, Map[String, SessionRecord]]
) extends SessionStore[F] {
  override def create(session: SessionRecord): F[Unit] =
    sessions.update(_.updated(session.tokenHash, session))

  override def findActive(tokenHash: String, now: Instant): F[Option[SessionRecord]] =
    sessions.get.map(
      _.get(tokenHash).filter(session =>
        session.invalidatedAt.isEmpty && session.expiresAt.isAfter(now)
      )
    )

  override def invalidate(tokenHash: String, now: Instant): F[Unit] =
    sessions.update(
      _.updatedWith(tokenHash)(_.map(_.copy(invalidatedAt = Some(now))))
    )

  override def deleteExpiredOrInvalidated(now: Instant): F[Int] =
    sessions.modify { current =>
      val (expired, active) =
        current.partition((_, session) =>
          !session.expiresAt.isAfter(now) || session.invalidatedAt.nonEmpty
        )
      (active, expired.size)
    }
}
