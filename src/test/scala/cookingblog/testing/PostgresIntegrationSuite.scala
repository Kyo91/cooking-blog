package cookingblog.testing

import cats.effect.{IO, Resource}
import ciris.Secret
import cookingblog.config.DatabaseConfig
import cookingblog.database.Database
import doobie.Transactor
import munit.{AnyFixture, CatsEffectSuite}
import org.testcontainers.postgresql.PostgreSQLContainer

import java.util.UUID
import scala.concurrent.duration.*

/** Each suite owns a fresh PostgreSQL container, independent of application DATABASE_* settings.
  * Migrations run once; callers can allocate separate connection pools against the same database to
  * test application restarts without restarting PostgreSQL.
  */
abstract class PostgresIntegrationSuite extends CatsEffectSuite {
  override def munitIOTimeout: Duration = 3.minutes

  protected def databasePoolSize: Int = 2
  protected val databaseApplicationName: String = s"cooking-blog-test-${UUID.randomUUID()}"

  private val postgres = ResourceSuiteLocalFixture(
    "postgresql",
    Resource
      .make(IO.blocking {
        new PostgreSQLContainer("postgres:17.10-alpine")
          .withDatabaseName("cooking_blog_test")
          .withUrlParam("ApplicationName", databaseApplicationName)
      })(container => IO.blocking(container.stop()))
      .evalTap(container => IO.blocking(container.start()))
      .evalMap { container =>
        val config = DatabaseConfig(
          container.getJdbcUrl,
          container.getUsername,
          Secret(container.getPassword),
          poolSize = databasePoolSize
        )
        Database.migrate(config).as(config)
      }
  )

  override def munitFixtures: Seq[AnyFixture[?]] = super.munitFixtures :+ postgres

  protected def databaseConfig: DatabaseConfig = postgres()

  protected def database: Resource[IO, Transactor[IO]] =
    Resource.eval(IO(databaseConfig)).flatMap(Database.transactor)
}
