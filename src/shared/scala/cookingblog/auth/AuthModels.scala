package cookingblog.auth

import java.time.Instant

final case class Principal(name: String)

final case class SessionRecord(
    tokenHash: String,
    principal: Principal,
    csrfSecretHash: String,
    createdAt: Instant,
    expiresAt: Instant,
    invalidatedAt: Option[Instant]
)

final case class IssuedSession(
    token: String,
    csrfSecret: String,
    principal: Principal,
    expiresAt: Instant
)

final case class AuthenticatedSession(
    token: String,
    csrfSecret: Option[String],
    record: SessionRecord
)
