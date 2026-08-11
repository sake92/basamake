

ThisBuild / semanticdbEnabled := true
ThisBuild / scalacOptions += "-Ybest-effort" // generate semanticdb even on compile errors (Scala ≥3.5)

val scala3Version = "3.8.4"

lazy val root = project
  .in(file("."))
  .settings(
    name := "Basamake hello sbt",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    scalacOptions += "-Ybest-effort",
    libraryDependencies += "com.lihaoyi" %% "upickle" % "4.0.0",
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.2" % Test
  )
