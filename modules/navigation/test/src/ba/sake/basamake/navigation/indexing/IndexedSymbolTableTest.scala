package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

class IndexedSymbolTableTest extends FunSuite, TestCacheRoot {

  private def cacheDir(fp: String) = SourceJarIndexer.cacheRoot / os.RelPath(fp)

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

    // wait for BOTH jars to be indexed + registered (a transient single-jar route
    // would make the "first" sample differ from the settled first-wins result)
    val deadline = System.currentTimeMillis() + 20000
    var stable = false
    var first: Option[os.Path] = None
    while (!stable && System.currentTimeMillis() < deadline) {
      val cur = deps.get("com/example/Foo#").map(_.path)
      if (cur.isDefined && first == cur) stable = true
      else { first = cur; Thread.sleep(100) }
    }
    assert(stable, s"route must settle on a deterministic result, last=$first")

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

  // ── lazy, target-scoped indexing ─────────────────────────────

  test("registerTarget registers cached jars but does NOT index uncached ones") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    // 1. index the jar with one instance
    val warmer = new IndexedSymbolTable
    warmer.ensureIndexed(List(jar))
    assert(eventually(warmer.get("com/example/Foo#").isDefined))

    // 2. fresh instance: registerTarget must be routing-only (no indexing)
    val deps = new IndexedSymbolTable
    deps.registerTarget("target-1", List(jar))
    assert(deps.get("com/example/Foo#").isDefined, "cached jars must resolve right after registerTarget")

    // 3. uncached jar: registerTarget must NOT create an index
    val coldJar = buildJar(tempDir, "cold-sources.jar")
    val coldFp = Fingerprint.fromJarPath(coldJar)
    cleanCache(coldFp)
    deps.registerTarget("target-2", List(coldJar))
    assert(!os.exists(cacheDir(coldFp)), "registerTarget must not index uncached jars")

    // 4. ...but ensureIndexedFor indexes them
    deps.ensureIndexedFor("target-2")
    assert(eventually(os.exists(cacheDir(coldFp) / "index.lmdb")), "ensureIndexedFor should index the target's jars")
  }

  test("candidate lookup picks the target's jar on same-package collisions") {
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

    val inA = deps.get("com/example/Foo#", List(jarA)).map(_.path).get
    val inB = deps.get("com/example/Foo#", List(jarB)).map(_.path).get
    assert(inA.startsWith(cacheDir(Fingerprint.fromJarPath(jarA))), s"expected def from jarA, got $inA")
    assert(inB.startsWith(cacheDir(Fingerprint.fromJarPath(jarB))), s"expected def from jarB, got $inB")
    assert(inA != inB)
  }

  test("candidate lookup skips uncached jars, queues their index, retry works") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val deps = new IndexedSymbolTable
    // no ensureIndexed/registerTarget at all — the lookup itself must queue the index
    assertEquals(deps.get("com/example/Foo#", List(jar)), None, "uncached jar must not resolve yet")
    assert(eventually(deps.get("com/example/Foo#", List(jar)).isDefined),
      "the lookup should have queued a background index; a retry must resolve")
  }

  test("registered jars resolve WITHOUT metadata.json — packages served from memory") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jar))
    assert(eventually(deps.get("com/example/Foo#").isDefined))

    // wipe metadata.json AFTER registration: metadata.json is immutable once the
    // index is created, so lookups must keep working from the in-memory
    // packagesByFp cache — a file re-read would make these fail (load → None)
    os.remove(cacheDir(fp) / CacheMetadata.FileName)
    assert(!os.exists(cacheDir(fp) / CacheMetadata.FileName), "metadata.json should be gone")

    assertEquals(deps.get("com/example/Foo#", List(jar)).map(_.symbol), Some("com/example/Foo#"),
      "candidate lookup must use cached packages, not re-read metadata.json")
    assert(deps.get("com/example/Baz.").isDefined,
      "global route lookup must also keep working from the in-memory route")
  }

  // ── dep-file candidates + route fallback ordering ─────────────

  test("route fallback prefers the NEWER version on same-package collisions") {
    val tempDirA = os.temp.dir()
    val tempDirB = os.temp.dir()
    // versioned jar names → versioned fingerprints (mylib_1.0.0_<hash> vs mylib_2.0.0_<hash>)
    val jarOld = buildJar(tempDirA, "mylib-1.0.0-sources.jar")
    val jarNew = buildJar(tempDirB, "mylib-2.0.0-sources.jar")
    val fpOld = Fingerprint.fromJarPath(jarOld)
    val fpNew = Fingerprint.fromJarPath(jarNew)
    cleanCache(fpOld)
    cleanCache(fpNew)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jarOld, jarNew))

    // wait for BOTH indexes to be fully written (register() runs right after in
    // the background thread). The first route hit must see both jars — a
    // transient single-jar route would record a recency bias that beats version
    assert(eventually(os.isDir(cacheDir(fpOld) / "index.lmdb")), "old jar should finish indexing")
    assert(eventually(os.isDir(cacheDir(fpNew) / "index.lmdb")), "new jar should finish indexing")
    var viaRoute: Option[os.Path] = None
    val deadline = System.currentTimeMillis() + 20000
    while (viaRoute.isEmpty && System.currentTimeMillis() < deadline) {
      Thread.sleep(100) // let register() (map puts right after index()) complete
      viaRoute = deps.get("com/example/Foo#").map(_.path)
    }
    assert(viaRoute.isDefined, "route should resolve")
    // regression: sorted-first-wins used to pick the OLDEST jar (2.12 vs 3.8.4)
    assert(viaRoute.get.startsWith(cacheDir(fpNew)), s"expected def from newer jar, got ${viaRoute.get}")
    assert(deps.get("com/example/Baz.").map(_.path).get.startsWith(cacheDir(fpNew)),
      "global route must prefer the newer jar for term symbols too")
  }

  test("route fallback prefers recently hit jars over newer versions") {
    val tempDirA = os.temp.dir()
    val tempDirB = os.temp.dir()
    val jarOld = buildJar(tempDirA, "mylib-1.0.0-sources.jar")
    val jarNew = buildJar(tempDirB, "mylib-2.0.0-sources.jar")
    val fpOld = Fingerprint.fromJarPath(jarOld)
    val fpNew = Fingerprint.fromJarPath(jarNew)
    cleanCache(fpOld)
    cleanCache(fpNew)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jarOld, jarNew))
    assert(eventually(deps.get("com/example/Foo#", List(jarNew)).isDefined))
    // hit the OLD jar last — it becomes the most recently used
    assert(eventually(deps.get("com/example/Foo#", List(jarOld)).isDefined))

    val viaRoute = deps.get("com/example/Foo#").map(_.path).get
    assert(viaRoute.startsWith(cacheDir(fpOld)),
      s"recency must beat version — expected the just-hit old jar, got $viaRoute")
  }

  test("candidatesForPath returns the owning jar for dep source files") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jar))
    assert(eventually(deps.get("com/example/Foo#").isDefined))

    // the resolved def path IS a dep source file — its owning jar must come back
    val depFile = deps.get("com/example/Foo#").map(_.path).get
    val cands = deps.candidatesForPath(depFile)
    assert(cands.contains(jar), s"expected owning jar $jar, got $cands")

    // paths outside the cache (workspace files) → no candidates
    assertEquals(deps.candidatesForPath(os.temp.dir() / "Main.scala"), Nil)
    // JDK paths are deliberately excluded (route resolves them correctly)
    assertEquals(deps.candidatesForPath(SourceJarIndexer.cacheRoot / "jdk-21_x" / "src" / "java/lang/Object.java"), Nil)
    // cache paths without the fp/src/ layout → no candidates
    assertEquals(deps.candidatesForPath(SourceJarIndexer.cacheRoot / "metadata.json"), Nil)
  }

  test("candidatesForPath recovers the source from metadata for unregistered jars") {
    val tempDir = os.temp.dir()
    val jar = buildJar(tempDir, "test-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    cleanCache(fp)

    val warmer = new IndexedSymbolTable
    warmer.ensureIndexed(List(jar))
    val deadline = System.currentTimeMillis() + 20000
    var depFile: Option[os.Path] = None
    while (depFile.isEmpty && System.currentTimeMillis() < deadline) depFile = warmer.get("com/example/Foo#").map(_.path)
    assert(depFile.isDefined, "jar should index")
    val extracted = depFile.get
    assert(os.exists(extracted), "extracted source should exist on disk")

    // a FRESH instance never registered the jar — the source must come from metadata.json
    val deps = new IndexedSymbolTable
    assert(deps.candidatesForPath(extracted).contains(jar),
      s"expected owning jar from metadata, got ${deps.candidatesForPath(extracted)}")
  }

  test("candidate lookup from a dep file resolves to the file's own jar on collisions") {
    val tempDirA = os.temp.dir()
    val tempDirB = os.temp.dir()
    val jarA = buildJar(tempDirA, "a-sources.jar")
    val jarB = buildJar(tempDirB, "b-sources.jar")
    val fpA = Fingerprint.fromJarPath(jarA)
    val fpB = Fingerprint.fromJarPath(jarB)
    cleanCache(fpA)
    cleanCache(fpB)

    val deps = new IndexedSymbolTable
    deps.ensureIndexed(List(jarA, jarB))
    assert(eventually(deps.get("com/example/Foo#").isDefined))

    // simulate a file inside jar B's extracted sources, resolved with its own candidates
    val depFileInB = cacheDir(fpB) / "src" / "Foo.java"
    val cands = deps.candidatesForPath(depFileInB)
    assert(cands.contains(jarB), s"expected owning jar $jarB, got $cands")
    val inB = deps.get("com/example/Foo#", cands).map(_.path).get
    assert(inB.startsWith(cacheDir(fpB)), s"expected def from jarB, got $inB")
    assert(inB.startsWith(cacheDir(fpA)) == false, "must not resolve from the OTHER jar")
  }
}
