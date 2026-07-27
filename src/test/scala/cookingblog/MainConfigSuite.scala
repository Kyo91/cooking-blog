package cookingblog

import ciris.Secret
import cookingblog.config.*
import munit.CatsEffectSuite

import java.nio.file.Paths
import scala.concurrent.duration.*

final class MainConfigSuite extends CatsEffectSuite {
  test("production rejects development credentials") {
    interceptIO[IllegalArgumentException](
      Main.validateConfig(configuration("cooking_blog_dev", "test"))
    ).map { error =>
      assert(error.getMessage.contains("DATABASE_PASSWORD"))
      assert(error.getMessage.contains("AUTH_PASSWORD"))
    }
  }

  test("production accepts file-supplied release credentials and bounded limits") {
    Main.validateConfig(
      configuration(
        "a-database-password-that-is-not-development",
        "a-unique-release-password"
      )
    )
  }

  test("production requires an absolute persistent photo directory") {
    val config =
      configuration(
        "a-database-password-that-is-not-development",
        "a-unique-release-password"
      ).copy(photos = PhotoConfig(Paths.get("./relative/photos")))
    interceptIO[IllegalArgumentException](Main.validateConfig(config)).map(error =>
      assert(error.getMessage.contains("PHOTO_DIRECTORY"))
    )
  }

  private def configuration(
      databasePassword: String,
      authPassword: String
  ): AppConfig =
    AppConfig(
      RuntimeConfig(RuntimeEnvironment.Production, 105_000_000L),
      HttpConfig("0.0.0.0", 8080),
      DatabaseConfig(
        "jdbc:postgresql://postgres:5432/cooking_blog",
        "cooking_blog",
        Secret(databasePassword),
        4
      ),
      AuthConfig("admin", Secret(authPassword), 24.hours, cookieSecure = false),
      PhotoConfig(Paths.get("/var/lib/cooking-blog/photos")),
      ScrapeConfig(
        workerCount = 2,
        perHostConcurrency = 1,
        pollInterval = 500.millis,
        staleJobTimeout = 5.minutes,
        requestTimeout = 15.seconds,
        totalJobTimeout = 45.seconds,
        maximumResponseBytes = 2_000_000L,
        maximumRedirects = 5,
        maximumAttempts = 5,
        baseRetryDelay = 30.seconds,
        maximumRetryDelay = 60.minutes,
        userAgent = "CookingBlog/0.1 (+personal recipe archive)"
      )
    )
}
