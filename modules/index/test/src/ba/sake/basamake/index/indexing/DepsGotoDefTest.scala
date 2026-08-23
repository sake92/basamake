package ba.sake.basamake.index.indexing

import munit.FunSuite
import ba.sake.basamake.index.*
import DepTestUtils.*
import scala.meta.internal.semanticdb.{Range => SdbRange, SymbolOccurrence}

/** End-to-end: goto-def from a semanticdb-paired workspace file into a dep
  * source jar, through WorkspaceIndex → depsTable candidate-scoped lookup
  * → LMDB point query → lazy file extraction. */
class DepsGotoDefTest extends FunSuite, TestCacheRoot {

  test("gotoDefinitions resolves a jar type into the extracted source") {
    val workspace = os.temp.dir(prefix = "deps-gotodef-ws-")
    val mainFile = workspace / "Main.scala"
    val mainText =
      """import com.example.Foo
        |
        |object Main {
        |  def main: Unit = {
        |    val f: Foo = new Foo()
        |  }
        |}
        |""".stripMargin
    os.write.over(mainFile, mainText)

    val jarDir = os.temp.dir(prefix = "deps-gotodef-jar-")
    val jarPath = writeJar(jarDir, "example-lib-sources.jar", List(
      "com/example/Foo.java" -> "package com.example;\npublic class Foo { public void bar() {} }\n"
    ))

    try {
      pairMainWithSemanticdb(workspace, List(
        // the `Foo` in `new Foo()` (line 4, cols 21-24)
        SymbolOccurrence(symbol = "com/example/Foo#", range = Some(SdbRange(4, 21, 4, 24)), role = SymbolOccurrence.Role.REFERENCE)
      ))

      val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      depsTable.registerTarget(List(jarPath)) // registers the source for lazy extraction
      assert(eventually(depsTable.get("com/example/Foo#", List(jarPath)).isDefined),
        "warm-up: the background index must resolve")

      val idx = new WorkspaceIndex(workspace, new InMemorySymbolTable, Some(depsTable))
      idx.initialize(List(SemanticdbDirs(workspace, workspace / ".semanticdb")))
      idx.onDidOpen(mainFile)

      val (l, c) = TestPositions.at(mainText, """(?<p>Foo)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c, depCandidates = List(jarPath))

      assert(locs.nonEmpty, s"expected jar def for Foo, got empty")
      val loc = locs.head
      assertEquals(loc.symbol, "com/example/Foo#")
      assert(loc.path.startsWith(testCacheRoot), s"expected path under test cache root, got ${loc.path}")
      assertEquals(loc.path.last, "Foo.java")
      assert(os.exists(loc.path), s"extracted source must exist on disk: ${loc.path}")
      assert(os.read(loc.path).contains("class Foo"), "extracted content should match the jar entry")
    } finally {
      os.remove.all(workspace)
      os.remove.all(jarDir)
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(jarPath)))
    }
  }

  test("gotoDefinitions resolves a jar type from NESTED package statements") {
    val workspace = os.temp.dir(prefix = "deps-gotodef-ws2-")
    val mainFile = workspace / "Main.scala"
    val mainText =
      """import com.example.deep.Foo
        |
        |object Main {
        |  def main: Unit = {
        |    val f: Foo = new Foo()
        |  }
        |}
        |""".stripMargin
    os.write.over(mainFile, mainText)

    val jarDir = os.temp.dir(prefix = "deps-gotodef-jar2-")
    val jarPath = writeJar(jarDir, "example-lib-sources.jar", List(
      // scala-library style: `package com.example` + `package deep` as SEPARATE statements.
      // Regression: the extractor used to drop the outer prefix (`deep/Foo#` instead of
      // `com/example/deep/Foo#`), breaking the LMDB key lookup.
      "com/example/deep/Foo.scala" -> "package com.example\npackage deep\nclass Foo\n"
    ))

    try {
      pairMainWithSemanticdb(workspace, List(
        SymbolOccurrence(symbol = "com/example/deep/Foo#", range = Some(SdbRange(4, 21, 4, 24)), role = SymbolOccurrence.Role.REFERENCE)
      ))

      val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      depsTable.registerTarget(List(jarPath))
      assert(eventually(depsTable.get("com/example/deep/Foo#", List(jarPath)).isDefined),
        "warm-up: nested package statements must produce the full symbol")

      val idx = new WorkspaceIndex(workspace, new InMemorySymbolTable, Some(depsTable))
      idx.initialize(List(SemanticdbDirs(workspace, workspace / ".semanticdb")))
      idx.onDidOpen(mainFile)

      val (l, c) = TestPositions.at(mainText, """(?<p>Foo)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c, depCandidates = List(jarPath))

      assert(locs.nonEmpty, s"expected jar def for Foo, got empty")
      val loc = locs.head
      assertEquals(loc.symbol, "com/example/deep/Foo#")
      assert(loc.path.startsWith(testCacheRoot), s"expected path under test cache root, got ${loc.path}")
      assertEquals(loc.path.last, "Foo.scala")
      assert(os.exists(loc.path), s"extracted source must exist on disk: ${loc.path}")
    } finally {
      os.remove.all(workspace)
      os.remove.all(jarDir)
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(jarPath)))
    }
  }

  /** Regression for the "Console.println → scala-library 2.12" bug: a lookup from
    * INSIDE an extracted dep file must stay in the file's OWN jar when two jars
    * share the package. The dep-file candidates come from `candidatesForPath`
    * (jar ownership), and the candidate-scoped lookup must then hit that jar. */
  test("candidate lookup from a dep file resolves to the file's own jar on collisions") {
    val jarDirA = os.temp.dir(prefix = "deps-gotodef-jarA-")
    val jarDirB = os.temp.dir(prefix = "deps-gotodef-jarB-")
    val jarA = writeJar(jarDirA, "a-sources.jar", List(
      "com/example/Foo.java" -> "package com.example;\npublic class Foo { public void a() {} }\n"
    ))
    val jarB = writeJar(jarDirB, "b-sources.jar", List(
      "com/example/Foo.java" -> "package com.example;\npublic class Foo { public void b() {} }\n"
    ))
    val fingerprintA = Fingerprint.fromJarPath(jarA)
    val fingerprintB = Fingerprint.fromJarPath(jarB)

    try {
      val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      depsTable.registerTarget(List(jarA, jarB))
      assert(eventually(depsTable.get("com/example/Foo#", List(jarA)).isDefined), "warm jarA")
      assert(eventually(depsTable.get("com/example/Foo#", List(jarB)).isDefined), "warm jarB")

      // the dep file: jar B's extracted Foo.java, opened like the editor opens it.
      // Content references Foo from a SECOND class.
      val depFile = testCacheRoot / os.RelPath(fingerprintB) / "src" / "com" / "example" / "Foo.java"
      os.write.over(depFile, "package com.example;\npublic class Bar { public Foo foo; }\n", createFolders = true)

      val cands = depsTable.candidatesForPath(depFile)
      assert(cands.contains(jarB), s"expected owning jar $jarB, got $cands")

      val inB = depsTable.get("com/example/Foo#", cands).map(_.path).get
      assert(inB.startsWith(testCacheRoot / os.RelPath(fingerprintB)), s"expected def from jarB, got $inB")
      assert(!inB.startsWith(testCacheRoot / os.RelPath(fingerprintA)), "must not resolve from the OTHER jar")
    } finally {
      os.remove.all(jarDirA)
      os.remove.all(jarDirB)
      os.remove.all(testCacheRoot / os.RelPath(fingerprintA))
      os.remove.all(testCacheRoot / os.RelPath(fingerprintB))
    }
  }
}
