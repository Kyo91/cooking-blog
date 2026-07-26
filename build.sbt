import Dependencies.*

ThisBuild / organization := "cookingblog"
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = project
  .in(file("."))
  .settings(
    name := "cooking-blog",
    Compile / mainClass := Some("cookingblog.Main"),
    Compile / run / fork := true,
    Test / fork := true,
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
