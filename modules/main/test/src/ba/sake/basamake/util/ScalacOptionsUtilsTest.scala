package ba.sake.basamake.util

import munit.FunSuite

class ScalacOptionsUtilsTest extends FunSuite {

  // ── sourceRootDir ─────────────────────────────────────────────

  test("sourceRootDir: scala3 space form (-sourceroot <dir>)") {
    assertEquals(
      ScalacOptionsUtils.sourceRootDir(List("-sourceroot", "/abs/src")),
      Some(os.Path("/abs/src"))
    )
  }

  test("sourceRootDir: scala3 colon form (-sourceroot:<dir>)") {
    assertEquals(
      ScalacOptionsUtils.sourceRootDir(List("-sourceroot:/abs/src")),
      Some(os.Path("/abs/src"))
    )
  }

  test("sourceRootDir: scala2 semanticdb form (-P:semanticdb:sourceroot:<dir>)") {
    assertEquals(
      ScalacOptionsUtils.sourceRootDir(List("-P:semanticdb:sourceroot:/abs/src")),
      Some(os.Path("/abs/src"))
    )
  }

  test("sourceRootDir: absent → None (no os.pwd fallback)") {
    // sbt-semanticdb passes only -Xsemanticdb + -semanticdb-target — no -sourceroot
    assertEquals(
      ScalacOptionsUtils.sourceRootDir(List("-Xsemanticdb", "-semanticdb-target", "/out")),
      None
    )
  }

  test("sourceRootDir: unrelated flag with similar prefix is ignored") {
    assertEquals(ScalacOptionsUtils.sourceRootDir(List("-sourcerootx", "/x")), None)
  }

  test("sourceRootDir: first matching flag wins over later ones") {
    assertEquals(
      ScalacOptionsUtils.sourceRootDir(List("-sourceroot", "/first", "-sourceroot:/second")),
      Some(os.Path("/first"))
    )
  }

  // ── semanticdbTargetPath ──────────────────────────────────────

  test("semanticdbTargetPath: scala3 space form") {
    assertEquals(
      ScalacOptionsUtils.semanticdbTargetPath(List("-semanticdb-target", "/sem/out")),
      Some(os.Path("/sem/out"))
    )
  }

  test("semanticdbTargetPath: scala3 colon form") {
    assertEquals(
      ScalacOptionsUtils.semanticdbTargetPath(List("-semanticdb-target:/sem/out")),
      Some(os.Path("/sem/out"))
    )
  }

  test("semanticdbTargetPath: scala2 form (-P:semanticdb:targetroot:<dir>)") {
    assertEquals(
      ScalacOptionsUtils.semanticdbTargetPath(List("-P:semanticdb:targetroot:/sem/out")),
      Some(os.Path("/sem/out"))
    )
  }

  test("semanticdbTargetPath: absent → None") {
    assertEquals(ScalacOptionsUtils.semanticdbTargetPath(List("-Xsemanticdb")), None)
  }

  // ── other helpers ─────────────────────────────────────────────

  test("hasSemanticdbFlags: detects any semanticdb flag") {
    assert(ScalacOptionsUtils.hasSemanticdbFlags(List("-Xsemanticdb")))
    assert(ScalacOptionsUtils.hasSemanticdbFlags(List("-P:semanticdb:targetroot:/x")))
    assert(ScalacOptionsUtils.hasSemanticdbFlags(List("-Xplugin:semanticdb")))
    assert(!ScalacOptionsUtils.hasSemanticdbFlags(List("-deprecation")))
  }

  test("hasBestEffortFlag: detects -Ybest-effort") {
    assert(ScalacOptionsUtils.hasBestEffortFlag(List("-Ybest-effort")))
    assert(!ScalacOptionsUtils.hasBestEffortFlag(List("-Xbest-effort")))
  }
}
