ThisBuild / scalaVersion := "3.3.1"

lazy val core = project
lazy val cli = project.dependsOn(core)
