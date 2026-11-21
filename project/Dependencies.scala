import sbt.*

object Dependencies {
  private val awsSdkVersion = "2.49.4"
  private val catsEffectVersion = "3.7.0"
  private val circeVersion = "0.14.15"
  private val cirisVersion = "3.10.0"
  private val skunkVersion = "1.0.0"
  private val postgresJdbcVersion = "42.7.10"
  private val flywayVersion = "11.13.2"
  private val nativeSpikeHttp4sVersion = "0.23.39"
  private val http4sVersion = "0.23.32"
  private val jsoupVersion = "1.22.2"
  private val log4catsVersion = "2.7.1"
  private val logbackVersion = "1.5.18"
  private val metadataExtractorVersion = "2.21.0"
  private val munitCatsEffectVersion = "2.2.0"
  private val scalaTagsVersion = "0.13.1"
  private val testcontainersVersion = "2.0.5"
  private val webpImageIoVersion = "3.12.0"

  val catsEffect = "org.typelevel" %% "cats-effect" % catsEffectVersion
  val ciris = "is.cir" %% "ciris" % cirisVersion
  val circeCore = "io.circe" %% "circe-core" % circeVersion
  val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  val circeParser = "io.circe" %% "circe-parser" % circeVersion
  val skunk = "org.tpolecat" %% "skunk-core" % skunkVersion
  val postgresJdbc = "org.postgresql" % "postgresql" % postgresJdbcVersion
  val flywayCore = "org.flywaydb" % "flyway-core" % flywayVersion
  val flywayPostgres =
    "org.flywaydb" % "flyway-database-postgresql" % flywayVersion
  val http4sCirce = "org.http4s" %% "http4s-circe" % http4sVersion
  val http4sDsl = "org.http4s" %% "http4s-dsl" % http4sVersion
  val http4sEmberClient =
    "org.http4s" %% "http4s-ember-client" % http4sVersion
  val http4sEmberServer =
    "org.http4s" %% "http4s-ember-server" % http4sVersion
  val jsoup = "org.jsoup" % "jsoup" % jsoupVersion
  val log4cats = "org.typelevel" %% "log4cats-slf4j" % log4catsVersion
  val logback = "ch.qos.logback" % "logback-classic" % logbackVersion % Runtime
  val metadataExtractor =
    "com.drewnoakes" % "metadata-extractor" % metadataExtractorVersion
  val scalaTags = "com.lihaoyi" %% "scalatags" % scalaTagsVersion
  val webpImageIo =
    "com.twelvemonkeys.imageio" % "imageio-webp" % webpImageIoVersion
  val awsS3 = "software.amazon.awssdk" % "s3" % awsSdkVersion
  val awsNettyNioClient =
    "software.amazon.awssdk" % "netty-nio-client" % awsSdkVersion
  // Only the isolated Native target cross-publishes these modules.
  val nativeSpikeDependencies = Seq(catsEffect, ciris.withRevision("3.15.2"), skunk) ++
    Seq("http4s-ember-server", "http4s-dsl").map("org.http4s" % _ % nativeSpikeHttp4sVersion)

  val munitCatsEffect =
    "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion
  val testcontainersPostgres =
    "org.testcontainers" % "testcontainers-postgresql" % testcontainersVersion
}
