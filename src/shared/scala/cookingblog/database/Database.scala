package cookingblog.database

import cats.effect.*
import ciris.Secret
import com.comcast.ip4s.{Host, Port}
import cookingblog.repository.SkunkRepositories
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.{Codec, RedactionStrategy, SSL, Session}
import skunk.codec.all.{int4, timestamptz}
import skunk.data.Completion
import skunk.implicits.*

import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration.*

final class Database private (private[cookingblog] val session: Resource[IO, Session[IO]]) {
  private[cookingblog] def withSession[A](use: Session[IO] => IO[A]): IO[A] =
    session.use(use)

  def ping: IO[Unit] = withSession(_.unique(sql"select 1".query(int4))).void

  def use[A](use: SkunkRepositories => IO[A]): IO[A] =
    withSession(connection => use(new SkunkRepositories(connection)))

  def transaction[A](use: SkunkRepositories => IO[A]): IO[A] =
    session.use { connection =>
      connection.transaction.use(_ => use(new SkunkRepositories(connection)))
    }
}

object Database {
  private given Meter[IO] = Meter.noop[IO]
  private given Tracer[IO] = Tracer.noop[IO]

  val instant: Codec[Instant] =
    timestamptz.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))

  def affectedRows(completion: Completion): Int = completion match {
    case Completion.Update(count) => count
    case Completion.Delete(count) => count
    case Completion.Insert(count) => count
    case other => throw IllegalStateException(s"Expected a row count, received: $other")
  }

  final case class ConnectionSettings(
      host: Host,
      port: Port,
      database: String,
      user: String,
      password: Secret[String],
      poolSize: Int,
      ssl: SSL,
      parameters: Map[String, String] = Session.DefaultConnectionParameters
  )

  def pooled(settings: ConnectionSettings): Resource[IO, Database] =
    Session
      .Builder[IO]
      .withHost(settings.host)
      .withPort(settings.port)
      .withUserAndPassword(settings.user, settings.password.value)
      .withDatabase(settings.database)
      .withConnectionParameters(settings.parameters)
      .withSSL(settings.ssl)
      .withRedactionStrategy(RedactionStrategy.All)
      .withReadTimeout(30.seconds)
      .pooled(settings.poolSize)
      .map(new Database(_))
      .evalTap(_.ping)
}
