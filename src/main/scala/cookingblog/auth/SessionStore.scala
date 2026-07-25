package cookingblog.auth

import cats.effect.kernel.Ref
import cats.effect.Sync
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant

trait SessionStore[F[_]] {
  def create(session: SessionRecord): F[Unit]
  def findActive(tokenHash: String, now: Instant): F[Option[SessionRecord]]
  def invalidate(tokenHash: String, now: Instant): F[Unit]
  def deleteExpiredOrInvalidated(now: Instant): F[Int]
}

final class DoobieSessionStore(transactor: Transactor[cats.effect.IO])
    extends SessionStore[cats.effect.IO] {
  override def create(session: SessionRecord): cats.effect.IO[Unit] =
    sql"""
      insert into auth_sessions (
        token_hash,
        principal,
        csrf_secret_hash,
        created_at,
        expires_at,
        invalidated_at
      ) values (
        ${session.tokenHash},
        ${session.principal.name},
        ${session.csrfSecretHash},
        ${session.createdAt},
        ${session.expiresAt},
        ${session.invalidatedAt}
      )
    """.update.run.transact(transactor).void

  override def findActive(
      tokenHash: String,
      now: Instant
  ): cats.effect.IO[Option[SessionRecord]] =
    sql"""
      select
        token_hash,
        principal,
        csrf_secret_hash,
        created_at,
        expires_at,
        invalidated_at
      from auth_sessions
      where token_hash = $tokenHash
        and invalidated_at is null
        and expires_at > $now
    """
      .query[(String, String, String, Instant, Instant, Option[Instant])]
      .option
      .map(
        _.map { case (hash, principal, csrfHash, createdAt, expiresAt, invalidatedAt) =>
          SessionRecord(
            hash,
            Principal(principal),
            csrfHash,
            createdAt,
            expiresAt,
            invalidatedAt
          )
        }
      )
      .transact(transactor)

  override def invalidate(tokenHash: String, now: Instant): cats.effect.IO[Unit] =
    sql"""
      update auth_sessions
      set invalidated_at = $now
      where token_hash = $tokenHash
        and invalidated_at is null
    """.update.run.transact(transactor).void

  override def deleteExpiredOrInvalidated(now: Instant): cats.effect.IO[Int] =
    sql"""
      delete from auth_sessions
      where expires_at <= $now
        or invalidated_at is not null
    """.update.run.transact(transactor)
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
