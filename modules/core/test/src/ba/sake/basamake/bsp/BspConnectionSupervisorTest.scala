package ba.sake.basamake.bsp

import munit.FunSuite

class BspConnectionSupervisorTest extends FunSuite:

  test("targetIdsForUri picks only targets whose source roots own the URI") {
    val uri = "file:///ws/examples/src/Main.scala"
    val targetToSourceRoots = Map(
      "target://root" -> List("file:///ws/src/"),
      "target://examples" -> List("file:///ws/examples/")
    )

    val selected = BspConnectionSupervisor.targetIdsForUri(uri, targetToSourceRoots)
    assertEquals(selected, List("target://examples"))
  }

  test("targetIdsForUri matches both directory and file source entries") {
    val uri = "file:///ws/scripts/hello.sc"
    val targetToSourceRoots = Map(
      "target://scripts-dir" -> List("file:///ws/scripts/"),
      "target://single-file" -> List("file:///ws/scripts/hello.sc")
    )

    val selected = BspConnectionSupervisor.targetIdsForUri(uri, targetToSourceRoots)
    assertEquals(selected.toSet, Set("target://scripts-dir", "target://single-file"))
  }

  test("targetIdsForUri matches even when source roots use file:/ and uri uses file:///") {
    val uri = "file:///ws/sbt/src/main/scala/Main.scala"
    val targetToSourceRoots = Map(
      "target://sbt-main" -> List("file:/ws/sbt/src/main/scala/")
    )

    val selected = BspConnectionSupervisor.targetIdsForUri(uri, targetToSourceRoots)
    assertEquals(selected, List("target://sbt-main"))
  }

  test("selectCompileTargetIds falls back to all connection targets when lookup misses") {
    val uri = "file:///ws/sbt/src/main/scala/Main.scala"
    val selected = BspConnectionSupervisor.selectCompileTargetIds(
      uri = uri,
      targetToSourceRoots = Map.empty,
      allTargetIds = List("target://sbt-main", "target://sbt-test")
    )

    assertEquals(selected.toSet, Set("target://sbt-main", "target://sbt-test"))
  }
