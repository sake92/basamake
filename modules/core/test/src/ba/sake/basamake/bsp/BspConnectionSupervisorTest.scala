package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import munit.FunSuite

class BspConnectionSupervisorTest extends FunSuite:

  private val root = new BuildTargetIdentifier("target://root")
  private val examples = new BuildTargetIdentifier("target://examples")
  private val scriptsDir = new BuildTargetIdentifier("target://scripts-dir")
  private val singleFile = new BuildTargetIdentifier("target://single-file")
  private val sbtMain = new BuildTargetIdentifier("target://sbt-main")
  private val sbtTest = new BuildTargetIdentifier("target://sbt-test")

  test("targetIdsForUri picks only targets whose source roots own the URI") {
    val uri = "file:///ws/examples/src/Main.scala"
    val targetToSourceRoots = Map(
      root -> List("file:///ws/src/"),
      examples -> List("file:///ws/examples/")
    )

    val selected = BspConnectionSupervisor.targetIdsForUri(uri, targetToSourceRoots)
    assertEquals(selected, List(examples))
  }

  test("targetIdsForUri matches both directory and file source entries") {
    val uri = "file:///ws/scripts/hello.sc"
    val targetToSourceRoots = Map(
      scriptsDir -> List("file:///ws/scripts/"),
      singleFile -> List("file:///ws/scripts/hello.sc")
    )

    val selected = BspConnectionSupervisor.targetIdsForUri(uri, targetToSourceRoots)
    assertEquals(selected.toSet, Set(scriptsDir, singleFile))
  }

  test("targetIdsForUri matches even when source roots use file:/ and uri uses file:///") {
    val uri = "file:///ws/sbt/src/main/scala/Main.scala"
    val targetToSourceRoots = Map(
      sbtMain -> List("file:/ws/sbt/src/main/scala/")
    )

    val selected = BspConnectionSupervisor.targetIdsForUri(uri, targetToSourceRoots)
    assertEquals(selected, List(sbtMain))
  }

  test("selectCompileTargetIds falls back to all connection targets when lookup misses") {
    val uri = "file:///ws/sbt/src/main/scala/Main.scala"
    val selected = BspConnectionSupervisor.selectCompileTargetIds(
      uri = uri,
      buildServer = null,
      targetToSourceRoots = Map.empty,
      allTargetIds = List(sbtMain, sbtTest)
    )

    assertEquals(selected.toSet, Set(sbtMain, sbtTest))
  }