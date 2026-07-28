import Dependencies.*

ThisBuild / organization := "cookingblog"
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := "0.1.0"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "cooking-blog",
    Compile / mainClass := Some("cookingblog.Main"),
    Compile / run / fork := true,
    Test / fork := true,
    Test / parallelExecution := false,
    Docker / packageName := "cooking-blog",
    Docker / version := version.value,
    dockerBaseImage := "eclipse-temurin:21-jre-jammy",
    dockerExposedPorts := Seq(8080),
    dockerUpdateLatest := false,
    dockerUsername := None,
    Docker / daemonUser := "cooking-blog",
    dockerEnvVars := Map(
      "JAVA_OPTS" -> "-XX:MaxRAMPercentage=75.0 -Djava.awt.headless=true"
    ),
    scalacOptions ++= Seq(
      "-release:21",
      "-no-indent",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Werror"
    ),
    libraryDependencies ++= Seq(
      catsEffect,
      ciris,
      circeCore,
      circeGeneric,
      circeParser,
      doobieCore,
      doobieHikari,
      doobiePostgres,
      flywayCore,
      flywayPostgres,
      http4sCirce,
      http4sDsl,
      http4sEmberClient,
      http4sEmberServer,
      jsoup,
      log4cats,
      logback,
      metadataExtractor,
      scalaTags,
      webpImageIo,
      munitCatsEffect % Test
    )
  )
