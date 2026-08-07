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
      val locs = idx.gotoDefinitions(mainFile, l, c)

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
}
