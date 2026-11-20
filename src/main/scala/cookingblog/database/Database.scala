package cookingblog.database

import cats.effect.*
import cookingblog.config.DatabaseConfig
import cookingblog.repository.SkunkRepositories
import org.flywaydb.core.Flyway
import org.postgresql.{Driver, PGProperty}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.{Codec, RedactionStrategy, SSL, Session}
import skunk.codec.all.{int4, timestamptz}
import skunk.data.Completion
import skunk.implicits.*

import java.time.{Instant, ZoneOffset}
import java.util.Properties
import scala.concurrent.duration.*

final class Database private (val session: Resource[IO, Session[IO]]) {
  def ping: IO[Unit] = session.use(_.unique(sql"select 1".query(int4))).void

  def use[A](use: SkunkRepositories => IO[A]): IO[A] =
    session.use(connection => use(new SkunkRepositories(connection)))

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

  def migrate(config: DatabaseConfig): IO[Unit] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(config.url, config.user, config.password.value)
        .load()
        .migrate()
    }.void

  def pooled(config: DatabaseConfig): Resource[IO, Database] =
    Resource.eval(IO(connectionSettings(config.url))).flatMap {
      case (host, port, name, ssl, params) =>
        Session
          .Builder[IO]
          .withHost(host)
          .withPort(port)
          .withUserAndPassword(config.user, config.password.value)
          .withDatabase(name)
          .withConnectionParameters(params)
          .withSSL(ssl)
          .withRedactionStrategy(RedactionStrategy.All)
          .withReadTimeout(30.seconds)
          .pooled(config.poolSize)
          .map(new Database(_))
          .evalTap(_.ping)
    }

  // Parse the same JDBC URL used by Flyway, but JDBC never opens a session for application queries.
  private def connectionSettings(url: String): (String, Int, String, SSL, Map[String, String]) = {
    val props = Option(Driver.parseURL(url, new Properties()))
      .getOrElse(throw IllegalArgumentException("DATABASE_URL must be a PostgreSQL JDBC URL"))
    require(
      !PGProperty.USER.isPresent(props) && !PGProperty.PASSWORD.isPresent(props),
      "Set database credentials with DATABASE_USER and DATABASE_PASSWORD, not DATABASE_URL"
    )
    val host = PGProperty.PG_HOST.getOrDefault(props).stripPrefix("[").stripSuffix("]")
    val port = PGProperty.PG_PORT.getOrDefault(props)
    val database = Option(PGProperty.PG_DBNAME.getOrDefault(props))
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalArgumentException("DATABASE_URL must include a database name"))
    require(
      !host.contains(",") && !port.contains(","),
      "DATABASE_URL must specify one PostgreSQL host"
    )
    val tls = Option(PGProperty.SSL_MODE.getOrDefault(props))
      .filter(_.nonEmpty)
      .getOrElse(if (PGProperty.SSL.getBoolean(props)) "verify-full" else "prefer") match {
      case "disable"     => SSL.None
      case "prefer"      => SSL.Trusted.withFallback(true)
      case "require"     => SSL.Trusted
      case "verify-ca"   => SSL.System
      case "verify-full" =>
        throw IllegalArgumentException(
          "The Skunk pool does not support DATABASE_URL sslmode=verify-full"
        )
      case _ => throw IllegalArgumentException("Unsupported DATABASE_URL sslmode")
    }
    val params = Session.DefaultConnectionParameters ++
      Option(PGProperty.APPLICATION_NAME.getOrDefault(props)).map("application_name" -> _) ++
      Option(PGProperty.CURRENT_SCHEMA.getOrDefault(props)).map("search_path" -> _)
    (host, port.toInt, database, tls, params)
  }
}
