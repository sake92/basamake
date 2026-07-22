package ba.sake.basamake.util

import munit.FunSuite

class ScalacOptionsUtilsTest extends FunSuite {

  // -- hasSemanticdbFlags --

  test("hasSemanticdbFlags detects -Xsemanticdb") {
    assert(ScalacOptionsUtils.hasSemanticdbFlags(List("-Xsemanticdb")))
  }

  test("hasSemanticdbFlags detects -semanticdb-target colon form") {
    assert(ScalacOptionsUtils.hasSemanticdbFlags(List("-semanticdb-target:/tmp/out")))
  }

  test("hasSemanticdbFlags detects -semanticdb-target space form") {
    assert(ScalacOptionsUtils.hasSemanticdbFlags(List("-semanticdb-target", "/tmp/out")))
  }

  test("hasSemanticdbFlags detects -P:semanticdb: prefix") {
    assert(ScalacOptionsUtils.hasSemanticdbFlags(List("-P:semanticdb:targetroot:/tmp/out")))
  }

  test("hasSemanticdbFlags detects -Xplugin:semanticdb") {
    assert(ScalacOptionsUtils.hasSemanticdbFlags(List("-Xplugin:semanticdb")))
  }

  test("hasSemanticdbFlags returns false when no flags present") {
    assert(!ScalacOptionsUtils.hasSemanticdbFlags(List("-deprecation", "-unchecked")))
  }

  test("hasSemanticdbFlags returns false for empty list") {
    assert(!ScalacOptionsUtils.hasSemanticdbFlags(Nil))
  }

  // -- semanticdbTargetPaths --

  test("semanticdbTargetPaths parses Scala 3 -semanticdb-target") {
    val paths = ScalacOptionsUtils.semanticdbTargetPaths(
      List("-Xsemanticdb", "-semanticdb-target:/tmp/custom/output")
    )
    assertEquals(paths, List(os.Path("/tmp/custom/output")))
  }

  test("semanticdbTargetPaths parses Scala 2 -P:semanticdb:targetroot:") {
    val paths = ScalacOptionsUtils.semanticdbTargetPaths(
      List("-P:semanticdb:targetroot:/tmp/custom-s2")
    )
    assertEquals(paths, List(os.Path("/tmp/custom-s2")))
  }

  test("semanticdbTargetPaths returns empty when no target flags present") {
    val paths = ScalacOptionsUtils.semanticdbTargetPaths(
      List("-Xsemanticdb", "-deprecation")
    )
    assertEquals(paths, Nil)
  }

  test("semanticdbTargetPaths handles multiple target flags") {
    val paths = ScalacOptionsUtils.semanticdbTargetPaths(
      List("-Xsemanticdb", "-semanticdb-target:/tmp/out1", "-P:semanticdb:targetroot:/tmp/out2")
    )
    assertEquals(paths, List(os.Path("/tmp/out1"), os.Path("/tmp/out2")))
  }

  test("semanticdbTargetPaths handles space-separated -semanticdb-target") {
    val paths = ScalacOptionsUtils.semanticdbTargetPaths(
      List("-Xsemanticdb", "-semanticdb-target", "/tmp/custom/space")
    )
    assertEquals(paths, List(os.Path("/tmp/custom/space")))
  }

  test("semanticdbTargetPaths handles space-separated -P:semanticdb:targetroot") {
    val paths = ScalacOptionsUtils.semanticdbTargetPaths(
      List("-P:semanticdb:targetroot", "/tmp/custom-s2")
    )
    assertEquals(paths, List(os.Path("/tmp/custom-s2")))
  }

  test("semanticdbTargetPaths ignores space-separated when next token is another flag") {
    val paths = ScalacOptionsUtils.semanticdbTargetPaths(
      List("-Xsemanticdb", "-semanticdb-target", "-deprecation")
    )
    assertEquals(paths, Nil)
  }

  // -- hasBestEffortFlag --

  test("hasBestEffortFlag returns true when -Ybest-effort present") {
    assert(ScalacOptionsUtils.hasBestEffortFlag(List("-Xsemanticdb", "-Ybest-effort")))
  }

  test("hasBestEffortFlag returns false when -Ybest-effort absent") {
    assert(!ScalacOptionsUtils.hasBestEffortFlag(List("-Xsemanticdb", "-deprecation")))
  }

  test("hasBestEffortFlag returns false for empty list") {
    assert(!ScalacOptionsUtils.hasBestEffortFlag(Nil))
  }
}
