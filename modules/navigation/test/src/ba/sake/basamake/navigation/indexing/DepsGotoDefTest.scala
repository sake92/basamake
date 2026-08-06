package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import ba.sake.basamake.navigation.*
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

/** Integration: goto-def from a workspace file into a dependency source jar
  * (through CompositeSymbolTable → IndexedSymbolTable → ~/.basamake cache). */
class DepsGotoDefTest extends FunSuite {

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
      assert(loc.path.toString.contains(".basamake/deps/"), s"expected path under ~/.basamake/deps, got ${loc.path}")
      assert(loc.path.last == "Foo.java", s"expected Foo.java, got ${loc.path.last}")
      assert(os.exists(loc.path), s"extracted source must exist on disk: ${loc.path}")

      // the extracted file content must be the jar source
      assert(os.read(loc.path).contains("class Foo"), "extracted content should match the jar entry")
    } finally {
      os.remove.all(workspace)
      os.remove.all(jarDir)
      os.remove.all(SourceJarIndexer.cacheRoot / Fingerprint.fromJarPath(jarPath))
    }
  }
}
