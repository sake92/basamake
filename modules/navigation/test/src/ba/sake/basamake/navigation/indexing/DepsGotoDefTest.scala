package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import ba.sake.basamake.navigation.*
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

/** Integration: goto-def from a workspace file into a dependency source jar
  * (through CompositeSymbolTable → IndexedSymbolTable → ~/.basamake cache). */
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
    val jarPath = jarDir / "example-lib-sources.jar"
    val fooSource = "package com.example;\npublic class Foo { public void bar() {} }\n"
    val zip = new ZipOutputStream(new FileOutputStream(jarPath.toIO))
    try {
      zip.putNextEntry(new ZipEntry("com/example/Foo.java"))
      zip.write(fooSource.getBytes("UTF-8"))
      zip.closeEntry()
    } finally zip.close()

    try {
      val workspaceTable = new InMemorySymbolTable
      val depsTable = new IndexedSymbolTable
      depsTable.ensureIndexed(List(jarPath))
      val composite = new CompositeSymbolTable(workspaceTable, depsTable)
      val idx = new WorkspaceIndex(workspace, composite)
      idx.initialize(List.empty)

      // wait for background indexing + routing to be ready
      val deadline = System.currentTimeMillis() + 20000
      while (depsTable.get("com/example/Foo#").isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)

      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """(?<p>Foo)\(\)""")
      // pass the jar as a dep candidate — the target-scoped lookup path
      val locs = idx.gotoDefinitions(mainFile, l, c, depCandidates = List(jarPath))

      assert(locs.nonEmpty, s"expected jar def for Foo, got empty (depsReady=${depsTable.get("com/example/Foo#").isDefined})")
      val loc = locs.head
      assertEquals(loc.symbol, "com/example/Foo#")
      assert(loc.path.startsWith(SourceJarIndexer.cacheRoot), s"expected path under test cache root, got ${loc.path}")
      assert(loc.path.last == "Foo.java", s"expected Foo.java, got ${loc.path.last}")
      assert(os.exists(loc.path), s"extracted source must exist on disk: ${loc.path}")

      // the extracted file content must be the jar source
      assert(os.read(loc.path).contains("class Foo"), "extracted content should match the jar entry")
    } finally {
      os.remove.all(workspace)
      os.remove.all(jarDir)
      os.remove.all(SourceJarIndexer.cacheRoot / os.RelPath(Fingerprint.fromJarPath(jarPath)))
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
    val jarPath = jarDir / "example-lib-sources.jar"
    // scala-library style: `package com.example` + `package deep` as SEPARATE statements.
    // Regression: the extractor used to drop the outer prefix (`deep/Foo#` instead of
    // `com/example/deep/Foo#`), breaking routing AND the LMDB key lookup.
    val fooSource = "package com.example\npackage deep\nclass Foo\n"
    val zip = new ZipOutputStream(new FileOutputStream(jarPath.toIO))
    try {
      zip.putNextEntry(new ZipEntry("com/example/deep/Foo.scala"))
      zip.write(fooSource.getBytes("UTF-8"))
      zip.closeEntry()
    } finally zip.close()

    try {
      val workspaceTable = new InMemorySymbolTable
      val depsTable = new IndexedSymbolTable
      depsTable.ensureIndexed(List(jarPath))
      val composite = new CompositeSymbolTable(workspaceTable, depsTable)
      val idx = new WorkspaceIndex(workspace, composite)
      idx.initialize(List.empty)

      // wait for background indexing + routing to be ready
      val deadline = System.currentTimeMillis() + 20000
      while (depsTable.get("com/example/deep/Foo#").isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)

      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """(?<p>Foo)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)

      assert(locs.nonEmpty, s"expected jar def for Foo, got empty (depsReady=${depsTable.get("com/example/deep/Foo#").isDefined})")
      val loc = locs.head
      assertEquals(loc.symbol, "com/example/deep/Foo#")
      assert(loc.path.startsWith(SourceJarIndexer.cacheRoot), s"expected path under test cache root, got ${loc.path}")
      assert(loc.path.last == "Foo.scala", s"expected Foo.scala, got ${loc.path.last}")
      assert(os.exists(loc.path), s"extracted source must exist on disk: ${loc.path}")
    } finally {
      os.remove.all(workspace)
      os.remove.all(jarDir)
      os.remove.all(SourceJarIndexer.cacheRoot / os.RelPath(Fingerprint.fromJarPath(jarPath)))
    }
  }

  /** Regression for the "Console.println → scala-library 2.12" bug: goto-def from
    * INSIDE an extracted dep file must stay in the file's OWN jar when two jars
    * share the package (the global route alone would pick sorted-first — the old
    * scala-library). The dep-file candidates come from `candidatesForPath`. */
  test("gotoDefinitions from a dep file stays in the owning jar on same-package collisions") {
    val workspace = os.temp.dir(prefix = "deps-gotodef-ws3-")

    def writeJar(dir: os.Path, name: String, fooMethod: String): os.Path = {
      val jarPath = dir / name
      val fooSource = s"package com.example;\npublic class Foo { public void $fooMethod() {} }\n"
      val zip = new ZipOutputStream(new FileOutputStream(jarPath.toIO))
      try {
        zip.putNextEntry(new ZipEntry("com/example/Foo.java"))
        zip.write(fooSource.getBytes("UTF-8"))
        zip.closeEntry()
      } finally zip.close()
      jarPath
    }

    val jarDirA = os.temp.dir(prefix = "deps-gotodef-jarA-")
    val jarDirB = os.temp.dir(prefix = "deps-gotodef-jarB-")
    val jarA = writeJar(jarDirA, "old-lib-1.0.0-sources.jar", "a")
    val jarB = writeJar(jarDirB, "new-lib-2.0.0-sources.jar", "b")
    val fpA = Fingerprint.fromJarPath(jarA)
    val fpB = Fingerprint.fromJarPath(jarB)

    try {
      val workspaceTable = new InMemorySymbolTable
      val depsTable = new IndexedSymbolTable
      depsTable.ensureIndexed(List(jarA, jarB))
      val composite = new CompositeSymbolTable(workspaceTable, depsTable)
      val idx = new WorkspaceIndex(workspace, composite)
      idx.initialize(List.empty)

      val deadline = System.currentTimeMillis() + 20000
      while (depsTable.get("com/example/Foo#").isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)

      // the dep file: jar B's extracted Foo.java, opened like the editor opens it.
      // Content references Foo from a SECOND class — the cursor sits on a real use.
      val depFile = SourceJarIndexer.cacheRoot / os.RelPath(fpB) / "src" / "com" / "example" / "Foo.java"
      val depText = "package com.example;\npublic class Bar { public Foo foo; }\n"
      os.write.over(depFile, depText, createFolders = true)
      idx.onDidOpen(depFile)

      val (l, c) = TestPositions.at(depText, """(?<p>Foo)""")
      val locs = idx.gotoDefinitions(depFile, l, c, depCandidates = depsTable.candidatesForPath(depFile))

      assert(locs.nonEmpty, s"expected jar def for Foo, got empty")
      val loc = locs.head
      assertEquals(loc.symbol, "com/example/Foo#")
      assert(loc.path.startsWith(SourceJarIndexer.cacheRoot / os.RelPath(fpB)),
        s"expected def from the owning jar B, got ${loc.path}")
    } finally {
      os.remove.all(workspace)
      os.remove.all(jarDirA)
      os.remove.all(jarDirB)
      os.remove.all(SourceJarIndexer.cacheRoot / os.RelPath(fpA))
      os.remove.all(SourceJarIndexer.cacheRoot / os.RelPath(fpB))
    }
  }
}
