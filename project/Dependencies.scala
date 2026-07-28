import sbt.*

object Dependencies {
  private val awsSdkVersion = "2.49.4"
  private val catsEffectVersion = "3.7.0"
  private val circeVersion = "0.14.15"
  private val cirisVersion = "3.10.0"
  private val doobieVersion = "1.0.0-RC12"
  private val flywayVersion = "11.13.2"
  private val http4sVersion = "0.23.32"
  private val jsoupVersion = "1.22.2"
  private val log4catsVersion = "2.7.1"
  private val logbackVersion = "1.5.18"
  private val metadataExtractorVersion = "2.21.0"
  private val munitCatsEffectVersion = "2.2.0"
  private val scalaTagsVersion = "0.13.1"
  private val webpImageIoVersion = "3.12.0"

  val catsEffect = "org.typelevel" %% "cats-effect" % catsEffectVersion
  val ciris = "is.cir" %% "ciris" % cirisVersion
  val circeCore = "io.circe" %% "circe-core" % circeVersion
  val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  val circeParser = "io.circe" %% "circe-parser" % circeVersion
  val doobieCore = "org.tpolecat" %% "doobie-core" % doobieVersion
  val doobieHikari = "org.tpolecat" %% "doobie-hikari" % doobieVersion
  val doobiePostgres = "org.tpolecat" %% "doobie-postgres" % doobieVersion
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
  val munitCatsEffect =
    "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion
}
