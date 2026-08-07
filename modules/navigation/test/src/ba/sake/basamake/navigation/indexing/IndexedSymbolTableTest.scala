package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

class IndexedSymbolTableTest extends FunSuite, TestCacheRoot {

  private def cacheDir(fp: String) = SourceJarIndexer.cacheRoot / fp

  private def cleanCache(fp: String): Unit = {
    if (os.exists(cacheDir(fp))) os.remove.all(cacheDir(fp))
  }

  private def eventually(cond: => Boolean, timeoutMs: Long = 20000): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(50)
    cond
  }

  /** jar with package com.example: class Foo, object Baz */
  private def buildJar(tempDir: os.Path, name: String): os.Path = {
    val jarPath = tempDir / name
    val javaFile = """package com.example;
public class Foo {
    public void bar() {}
}
"""
    val scalaFile = """package com.example
object Baz {
  def qux(): Unit = ()
}
"""
    val zip = new ZipOutputStream(new FileOutputStream(jarPath.toIO))
    try {
      zip.putNextEntry(new ZipEntry("Foo.java")); zip.write(javaFile.getBytes("UTF-8")); zip.closeEntry()
      zip.putNextEntry(new ZipEntry("Baz.scala")); zip.write(scalaFile.getBytes("UTF-8")); zip.closeEntry()
    } finally zip.close()
    jarPath
  }

  test("routes and resolves symbols from an indexed jar") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jar))

    assert(eventually(deps.get("com/example/Foo#").isDefined), "Foo should resolve after background index")
    assert(deps.get("com/example/Baz.").isDefined, "Baz should resolve")
    assert(deps.get("com/example/Foo#bar().").isDefined, "method defs should resolve")
  }

  test("does not consult indexes for unmatched packages") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jar))
    assert(eventually(deps.get("com/example/Foo#").isDefined))

    assertEquals(deps.get("org/other/Bar#"), None)
    assertEquals(deps.get("com/example/Foo#zzz."), None)
  }

  test("default-package symbols are not routed") {
    val deps = new IndexedSymbolTable
    assertEquals(deps.get("Foo#"), None)
  }

  test("lazy lookups: env queried on first hit, file extracted on demand") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jar))
    assert(eventually(deps.get("com/example/Foo#").isDefined))

    // get() must have extracted the file the def lives in
    val srcFile = cacheDir(fp) / "src" / "Foo.java"
    assert(os.exists(srcFile), "extracted source should exist on disk")
    // dep byPath is intentionally empty — references only matter for user code
    assertEquals(deps.byPath(srcFile), Set.empty[ba.sake.basamake.navigation.SymbolDefinition],
      "dep byPath must be empty (workspace table covers user-code references)")
  }

  test("keys is empty and mutations are no-ops") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jar))
    assert(eventually(deps.get("com/example/Foo#").isDefined))

    assertEquals(deps.keys, Set.empty[String])
    deps.add(ba.sake.basamake.navigation.SymbolDefinition(
      "com/example/X#", "X", true, scala.meta.internal.semanticdb.Range(0, 0, 0, 0), os.pwd / "x.scala"))
    deps.removeByPath(os.pwd / "x.scala")
    assertEquals(deps.get("com/example/X#"), None)
  }

  test("ensureIndexed is idempotent for the same jar") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jar))
    deps.ensureIndexed(List(jar))
    deps.ensureIndexed(List(jar))
    assert(eventually(deps.get("com/example/Foo#").isDefined), "symbol should resolve")
  }

  test("duplicate symbols across jars resolve deterministically") {
    val tempDirA = os.temp.dir()
    val tempDirB = os.temp.dir()
    val jarA = tempDirA / "a-sources.jar"
    val jarB = tempDirB / "b-sources.jar"
    val fooA = "package com.example;\npublic class Foo { public void a() {} }\n"
    val fooB = "package com.example;\npublic class Foo { public void b() {} }\n"
    def writeJar(path: os.Path, content: String): Unit = {
      val zip = new ZipOutputStream(new FileOutputStream(path.toIO))
      try { zip.putNextEntry(new ZipEntry("Foo.java")); zip.write(content.getBytes("UTF-8")); zip.closeEntry() }
      finally zip.close()
    }
    writeJar(jarA, fooA)
    writeJar(jarB, fooB)
    cleanCache(Fingerprint.fromJarPath(jarA))
    cleanCache(Fingerprint.fromJarPath(jarB))

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jarA, jarB))
    assert(eventually(deps.get("com/example/Foo#").isDefined))

    val first = deps.get("com/example/Foo#").map(_.path)
    (1 to 5).foreach { _ =>
      assertEquals(deps.get("com/example/Foo#").map(_.path), first, "first-wins must be deterministic")
    }
  }

  test("corrupt index recovers via background reindex") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jar))
    assert(eventually(deps.get("com/example/Foo#").isDefined))

    // corrupt the LMDB: replace the index dir with a garbage file
    val indexPath = cacheDir(fp) / "index.lmdb"
    os.remove.all(indexPath)
    os.write.over(indexPath, "garbage not an lmdb")

    // fresh instance to force re-registration
    val deps2 = new IndexedSymbolTable
    deps2.ensureIndexed(List(jar)) // re-register routing
    assertEquals(deps2.get("com/example/Foo#"), None, "corrupt index must not crash the lookup")
    assert(eventually(deps2.get("com/example/Foo#").isDefined), "background reindex should repair the cache")
    assert(os.exists(indexPath / "data.mdb"), "LMDB should be rebuilt as a directory")
  }

  test("corrupt index during a live lookup wipes and reindexes") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jar))
    assert(eventually(deps.get("com/example/Foo#").isDefined))

    // corrupt the LMDB AFTER routing is registered — the query itself must recover
    val indexPath = cacheDir(fp) / "index.lmdb"
    os.remove.all(indexPath)
    os.write.over(indexPath, "garbage not an lmdb")

    assertEquals(deps.get("com/example/Foo#"), None, "corrupt index must not crash the lookup")
    assert(eventually(deps.get("com/example/Foo#").isDefined), "lookup failure should trigger wipe + reindex")
    assert(os.exists(indexPath / "data.mdb"), "LMDB should be rebuilt as a directory")
  }
}
