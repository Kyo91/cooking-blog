package cookingblog.database

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Host, Port}
import cookingblog.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.postgresql.{Driver, PGProperty}
import skunk.{SSL, Session}

import java.util.Properties

/** JDBC URL compatibility and Flyway are JVM-only concerns. */
object JvmDatabase {
  def migrate(config: DatabaseConfig): IO[Unit] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(config.url, config.user, config.password.value)
        .load()
        .migrate()
    }.void

  def pooled(config: DatabaseConfig): Resource[IO, Database] =
    Resource.eval(IO(connectionSettings(config))).flatMap(Database.pooled)

  // Parse the same JDBC URL used by Flyway, but JDBC never opens a session for application queries.
  private def connectionSettings(config: DatabaseConfig): Database.ConnectionSettings = {
    val props = Option(Driver.parseURL(config.url, new Properties()))
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
    Database.ConnectionSettings(
      Host.fromString(host).getOrElse(throw IllegalArgumentException("Invalid DATABASE_URL host")),
      Port.fromString(port).getOrElse(throw IllegalArgumentException("Invalid DATABASE_URL port")),
      database,
      config.user,
      config.password,
      config.poolSize,
      tls,
      params
    )
  }
}
