package cookingblog

import ciris.Secret
import cookingblog.config.*
import munit.CatsEffectSuite

import java.net.URI
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

  test("laptop production accepts local photos and file-supplied release credentials") {
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
      ).copy(photos = LocalPhotoConfig(Paths.get("./relative/photos")))
    interceptIO[IllegalArgumentException](Main.validateConfig(config)).map(error =>
      assert(error.getMessage.contains("PHOTO_DIRECTORY"))
    )
  }

  test("cloud production accepts private S3-compatible storage configuration") {
    Main.validateConfig(cloudConfiguration)
  }

  test("cloud production requires HTTPS, secure cookies, and S3 photos") {
    val config =
      configuration(
        "a-database-password-that-is-not-development",
        "a-unique-release-password"
      ).copy(
        runtime = RuntimeConfig(
          RuntimeEnvironment.Production,
          DeploymentTarget.Cloud,
          105_000_000L
        ),
        http = HttpConfig(
          "0.0.0.0",
          8080,
          Some(URI.create("http://recipes.example.com"))
        )
      )
    interceptIO[IllegalArgumentException](Main.validateConfig(config)).map { error =>
      assert(error.getMessage.contains("AUTH_COOKIE_SECURE"))
      assert(error.getMessage.contains("PUBLIC_ORIGIN"))
      assert(error.getMessage.contains("PHOTO_BACKEND"))
    }
  }

  test("S3 static credentials and endpoint configuration are validated") {
    val invalid =
      cloudConfiguration.copy(
        photos = cloudPhotoConfig.copy(
          endpoint = Some(URI.create("http://minio:9000")),
          credentialsMode = S3CredentialsMode.Static,
          accessKeyId = "",
          secretAccessKey = Secret("")
        )
      )
    interceptIO[IllegalArgumentException](Main.validateConfig(invalid)).map { error =>
      assert(error.getMessage.contains("PHOTO_S3_ENDPOINT"))
      assert(error.getMessage.contains("PHOTO_S3_ACCESS_KEY_ID"))
      assert(error.getMessage.contains("PHOTO_S3_SECRET_ACCESS_KEY"))
    }
  }

  test("disabled scraping ignores worker-only limits") {
    val disabled =
      configuration(
        "a-database-password-that-is-not-development",
        "a-unique-release-password"
      ).copy(
        scraping = scrapeConfig.copy(
          enabled = false,
          workerCount = 0,
          perHostConcurrency = 0,
          maximumAttempts = 0
        )
      )
    Main.validateConfig(disabled)
  }

  private def configuration(
      databasePassword: String,
      authPassword: String
  ): AppConfig =
    AppConfig(
      RuntimeConfig(
        RuntimeEnvironment.Production,
        DeploymentTarget.Laptop,
        105_000_000L
      ),
      HttpConfig("0.0.0.0", 8080, None),
      DatabaseConfig(
        "jdbc:postgresql://postgres:5432/cooking_blog",
        "cooking_blog",
        Secret(databasePassword),
        4
      ),
      AuthConfig("admin", Secret(authPassword), 24.hours, cookieSecure = false),
      LocalPhotoConfig(Paths.get("/var/lib/cooking-blog/photos")),
      scrapeConfig
    )

  private val scrapeConfig =
    ScrapeConfig(
      enabled = true,
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

  private val cloudPhotoConfig =
    S3PhotoConfig(
      bucket = "cooking-blog-photos",
      prefix = "production/photos",
      region = "us-east-1",
      endpoint = Some(URI.create("https://objects.example.com")),
      pathStyleAccess = false,
      credentialsMode = S3CredentialsMode.Default,
      accessKeyId = "",
      secretAccessKey = Secret(""),
      maximumConcurrency = 4,
      connectionTimeout = 5.seconds,
      requestTimeout = 30.seconds
    )

  private val cloudConfiguration =
    configuration(
      "a-database-password-that-is-not-development",
      "a-unique-release-password"
    ).copy(
      runtime = RuntimeConfig(
        RuntimeEnvironment.Production,
        DeploymentTarget.Cloud,
        105_000_000L
      ),
      http = HttpConfig(
        "0.0.0.0",
        8080,
        Some(URI.create("https://recipes.example.com"))
      ),
      auth = AuthConfig(
        "admin",
        Secret("a-unique-release-password"),
        24.hours,
        cookieSecure = true
      ),
      photos = cloudPhotoConfig
    )
}
