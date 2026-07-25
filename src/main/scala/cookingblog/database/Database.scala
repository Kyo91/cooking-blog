package cookingblog.database

import cats.effect.*
import cookingblog.config.DatabaseConfig
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.flywaydb.core.Flyway

object Database {
  def migrate(config: DatabaseConfig): IO[Unit] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(config.url, config.user, config.password.value)
        .load()
        .migrate()
    }.void

  def transactor(config: DatabaseConfig): Resource[IO, HikariTransactor[IO]] =
    for {
      connectEc <- ExecutionContexts.fixedThreadPool[IO](config.poolSize)
      transactor <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver",
        config.url,
        config.user,
        config.password.value,
        connectEc
      )
    } yield transactor
}
