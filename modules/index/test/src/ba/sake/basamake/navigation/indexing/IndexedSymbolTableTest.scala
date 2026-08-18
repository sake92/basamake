package ba.sake.basamake.index.indexing

import munit.FunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

class IndexedSymbolTableTest extends FunSuite, TestCacheRoot {

  private def cacheDir(fingerprint: String) = SourceJarIndexer.cacheRoot / os.RelPath(fingerprint)

  private def cleanCache(fingerprint: String): Unit = {
    if (os.exists(cacheDir(fingerprint))) os.remove.all(cacheDir(fingerprint))
  }

  private def eventually(cond: => Boolean, timeoutMs: Long = 20000): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(50)
    cond
  }

  /** Sources jar + its classes sibling (coursier-style), so package metadata
    * can be derived from the classes jar. Class content is arbitrary bytes —
    * only the zip directory listing matters. */
  private def writeJarPair(dir: os.Path, name: String, pkg: String, methodName: String = "bar"): os.Path = {
    val sourcesJar = dir / name
    val pkgPath = pkg.replace('.', '/')
    val sources = new ZipOutputStream(new FileOutputStream(sourcesJar.toIO))
    try {
      sources.putNextEntry(new ZipEntry(s"$pkgPath/Foo.java"))
      sources.write(s"package $pkg;\npublic class Foo { public void $methodName() {} }\n".getBytes("UTF-8"))
      sources.closeEntry()
    } finally sources.close()
    val classesJar = dir / (name.stripSuffix("-sources.jar") + ".jar")
    val classes = new ZipOutputStream(new FileOutputStream(classesJar.toIO))
    try {
      classes.putNextEntry(new ZipEntry(s"$pkgPath/Foo.class")); classes.write(Array[Byte](1, 2)); classes.closeEntry()
    } finally classes.close()
    sourcesJar
  }

  /** A bigger jar pair (`entries` classes) — used to hold a background-index
    * permit long enough for deterministic timing tests. */
  private def writeBigJar(dir: os.Path, name: String, pkg: String, entries: Int): os.Path = {
    val sourcesJar = dir / name
    val pkgPath = pkg.replace('.', '/')
    val sources = new ZipOutputStream(new FileOutputStream(sourcesJar.toIO))
    try {
      (0 until entries).foreach { i =>
        sources.putNextEntry(new ZipEntry(s"$pkgPath/Foo$i.java"))
        sources.write(s"package $pkg;\npublic class Foo$i { public void bar() {} }\n".getBytes("UTF-8"))
        sources.closeEntry()
      }
    } finally sources.close()
    val classesJar = dir / (name.stripSuffix("-sources.jar") + ".jar")
    val classes = new ZipOutputStream(new FileOutputStream(classesJar.toIO))
    try {
      (0 until entries).foreach { i =>
        classes.putNextEntry(new ZipEntry(s"$pkgPath/Foo$i.class")); classes.write(Array[Byte](1, 2)); classes.closeEntry()
      }
    } finally classes.close()
    sourcesJar
  }

  test("candidate lookup fast-misses an uncached jar and resolves after the background index") {
    val tempDir = os.temp.dir()
    val jar = writeJarPair(tempDir, "test-sources.jar", "com.example")
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)

    val deps = new IndexedSymbolTable
    // cold cache — the lookup must NOT index inline (that was the "goto-def
    // into deps blocks" bug): it misses fast and indexes in the background
    assert(deps.get("com/example/Foo#", List(jar)).isEmpty, "cold lookup must be a fast miss, not a block")
    assert(eventually(deps.get("com/example/Foo#", List(jar)).isDefined),
      "must resolve once the background index is ready")
    assert(os.isDir(cacheDir(fingerprint) / "index.lmdb"), "the background index must exist on disk")
  }

  test("fast miss is deterministic while the global index permit is held") {
    val tempDir = os.temp.dir()
    val busyJar = writeBigJar(tempDir, "busy-sources.jar", "com.busy", entries = 400)
    val targetJar = writeJarPair(tempDir, "target-sources.jar", "com.target")
    val busyFp = Fingerprint.fromJarPath(busyJar)
    val targetFp = Fingerprint.fromJarPath(targetJar)
    cleanCache(busyFp)
    cleanCache(targetFp)

    val deps = new IndexedSymbolTable
    deps.maxConcurrentIndexes = 1 // only one background index may run at a time
    deps.registerTarget(List(busyJar, targetJar))

    // trigger the slow busy index first — it now holds the only permit
    assert(deps.get("com/busy/Foo0#", List(busyJar)).isEmpty, "busy jar lookup must fast-miss")
    // the target jar's background index parks on the permit; the lookup must
    // return None rather than block on indexing
    assert(deps.get("com/target/Foo#", List(targetJar)).isEmpty, "lookup must never block on indexing")
    assert(eventually(deps.get("com/target/Foo#", List(targetJar)).isDefined),
      "target must resolve once the permit frees")
    assert(eventually(deps.get("com/busy/Foo0#", List(busyJar)).isDefined), "busy jar must resolve too")
  }

  test("concurrent lookups for the same jar trigger exactly one background index") {
    val tempDir = os.temp.dir()
    val jar = writeJarPair(tempDir, "test-sources.jar", "com.example")
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)

    val deps = new IndexedSymbolTable
    deps.registerTarget(List(jar))
    val threads = (0 until 8).map { _ =>
      Thread.ofVirtual().start(() => deps.get("com/example/Foo#", List(jar)))
    }
    threads.foreach(_.join(10_000))
    assertEquals(deps.backgroundIndexStarts.get(), 1,
      s"expected exactly 1 background index for 8 concurrent lookups, got ${deps.backgroundIndexStarts.get()}")
    assert(eventually(deps.get("com/example/Foo#", List(jar)).isDefined), "must resolve after the single index")
  }

  test("global cap: at most maxConcurrentIndexes indexes run at once") {
    val tempDir = os.temp.dir()
    val jars = (0 until 3).map(i => writeBigJar(tempDir, s"cap-$i-sources.jar", s"com.cap$i", entries = 100)).toList
    val fps = jars.map(Fingerprint.fromJarPath)
    fps.foreach(cleanCache)

    val deps = new IndexedSymbolTable
    deps.maxConcurrentIndexes = 1
    deps.registerTarget(jars)
    jars.zipWithIndex.foreach { case (jar, i) => deps.get(s"com/cap$i/Foo0#", List(jar)) }

    // wait until all 3 finish, tracking the peak concurrent index count
    var peak = 0
    val deadline = System.currentTimeMillis() + 30000
    while (fps.exists(f => !os.isDir(cacheDir(f) / "index.lmdb")) && System.currentTimeMillis() < deadline) {
      peak = math.max(peak, deps.activeIndexCount.get())
      Thread.sleep(20)
    }
    assert(fps.forall(f => os.isDir(cacheDir(f) / "index.lmdb")), "all 3 jars must be indexed")
    assertEquals(peak, 1, s"cap=1 must serialize indexes; peak concurrency was $peak")
  }

  test("a failed background index is retried by a later lookup") {
    val tempDir = os.temp.dir()
    val jar = tempDir / "broken-sources.jar"
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)
    os.write.over(jar, "this is not a zip file") // SourceJarIndexer.index() will fail

    val deps = new IndexedSymbolTable
    deps.registerTarget(List(jar))
    assertEquals(deps.get("com/example/Foo#", List(jar)), None, "corrupt source jar must miss")
    // wait for the failed background index to COMPLETE (it unmarks the
    // fingerprint) — waiting only for the start counter races the virtual
    // thread's first execution under load, and a late-starting "failed" index
    // would then index the REPAIRED jar and resolve without a fresh attempt
    val deadline = System.currentTimeMillis() + 5000
    while ((deps.backgroundIndexStarts.get() < 1 || deps.activeIndexCount.get() > 0) && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assert(deps.backgroundIndexStarts.get() >= 1, "the failed index must have been attempted")
    assert(deps.activeIndexCount.get() == 0, "the failed index must have finished")

    // repair the jar and look up again — the retry must succeed
    writeJarPair(tempDir, "broken-sources.jar", "com.example")
    assert(deps.get("com/example/Foo#", List(jar)).isEmpty, "still cold — fast miss again")
    assert(eventually(deps.get("com/example/Foo#", List(jar)).isDefined), "a later lookup must retry and resolve")
    assert(deps.backgroundIndexStarts.get() >= 2, "the repaired jar must trigger a fresh index attempt")
  }

  test("does not consult indexes for unmatched packages") {
    val tempDir = os.temp.dir()
    val jar = writeJarPair(tempDir, "test-sources.jar", "com.example")
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)

    val deps = new IndexedSymbolTable
    deps.registerTarget(List(jar))
    assert(eventually(os.exists(cacheDir(fingerprint) / "metadata.json")), "metadata should be sprinkled by registerTarget")

    assertEquals(deps.get("org/other/Bar#", List(jar)), None)
    assert(!os.isDir(cacheDir(fingerprint) / "index.lmdb"), "unmatched package must not index the jar")
    assertEquals(deps.get("com/example/Foo#zzz.", List(jar)), None)
    assert(eventually(os.isDir(cacheDir(fingerprint) / "index.lmdb")),
      "jar must be indexed in background even though the lookup missed")
  }

  test("default-package symbols are not resolvable") {
    val deps = new IndexedSymbolTable
    assertEquals(deps.get("Foo#"), None)
    assertEquals(deps.get("Foo#", Nil), None)
  }

  test("lazy extraction: hit extracts the file") {
    val tempDir = os.temp.dir()
    val jar = writeJarPair(tempDir, "test-sources.jar", "com.example")
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)

    val deps = new IndexedSymbolTable
    deps.registerTarget(List(jar)) // registers the source for lazy extraction
    assert(eventually(deps.get("com/example/Foo#", List(jar)).isDefined), "must resolve after the background index")

    // the hit must have extracted the file the def lives in (entries are stored
    // under the package path: <cacheRoot>/<fingerprint>/src/com/example/Foo.java)
    val srcFile = cacheDir(fingerprint) / "src" / "com" / "example" / "Foo.java"
    assert(os.exists(srcFile), "extracted source should exist on disk")
    assert(os.read(srcFile).contains("class Foo"), "extracted content should match the jar entry")
  }

  test("registerTarget sprinkles metadata but indexes nothing") {
    val tempDir = os.temp.dir()
    val jar = writeJarPair(tempDir, "test-sources.jar", "com.example")
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)

    val deps = new IndexedSymbolTable
    deps.registerTarget(List(jar))
    assert(eventually(os.exists(cacheDir(fingerprint) / "metadata.json")), "registerTarget must sprinkle metadata.json")

    val meta = CacheMetadata.load(cacheDir(fingerprint)).get
    assertEquals(meta.indexed, false, "metadata must be package-only, not a full index")
    assertEquals(meta.packages, List("com.example"))

    assert(!os.isDir(cacheDir(fingerprint) / "index.lmdb"), "registerTarget must NOT create an index")

    assert(eventually(deps.get("com/example/Foo#", List(jar)).isDefined),
      "a lookup must resolve after the background index")
    assert(CacheMetadata.load(cacheDir(fingerprint)).map(_.indexed).contains(true),
      "the full index must upgrade the metadata to indexed=true")
  }

  test("registerTarget is idempotent") {
    val tempDir = os.temp.dir()
    val jar = writeJarPair(tempDir, "test-sources.jar", "com.example")
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)

    val deps = new IndexedSymbolTable
    deps.registerTarget(List(jar))
    deps.registerTarget(List(jar))
    assert(eventually(os.exists(cacheDir(fingerprint) / "metadata.json")))

    // exactly one metadata.json and nothing else — in particular no index.lmdb
    assertEquals(os.list(cacheDir(fingerprint)).map(_.last).toSet, Set("metadata.json"),
      "registerTarget twice must not double-sprinkle or index")
  }

  test("jar without a classes sibling is unfilterable — first lookup indexes inline") {
    val tempDir = os.temp.dir()
    val jar = tempDir / "noclasses-sources.jar"
    val pkgPath = "com/example"
    val zip = new ZipOutputStream(new FileOutputStream(jar.toIO))
    try {
      zip.putNextEntry(new ZipEntry(s"$pkgPath/Foo.java"))
      zip.write("package com.example;\npublic class Foo { public void bar() {} }\n".getBytes("UTF-8"))
      zip.closeEntry()
    } finally zip.close()
    // NO classes sibling next to the sources jar
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)

    val deps = new IndexedSymbolTable
    deps.registerTarget(List(jar))

    // poll a short deadline: no classes jar → no metadata can ever be derived
    val deadline = System.currentTimeMillis() + 500
    while (System.currentTimeMillis() < deadline) Thread.sleep(50)
    assert(!os.exists(cacheDir(fingerprint) / "metadata.json"), "no classes sibling → no package-only metadata")

    assert(eventually(deps.get("com/example/Foo#", List(jar)).isDefined),
      "unfilterable jar must be indexed in background on first lookup")
    assert(CacheMetadata.load(cacheDir(fingerprint)).map(_.indexed).contains(true),
      "the full index must write accurate metadata afterwards")
  }

  test("candidate lookup picks the target's jar on same-package collisions") {
    val tempDirA = os.temp.dir()
    val tempDirB = os.temp.dir()
    val jarA = writeJarPair(tempDirA, "a-sources.jar", "com.example", methodName = "fromA")
    val jarB = writeJarPair(tempDirB, "b-sources.jar", "com.example", methodName = "fromB")
    val fingerprintA = Fingerprint.fromJarPath(jarA)
    val fingerprintB = Fingerprint.fromJarPath(jarB)
    cleanCache(fingerprintA)
    cleanCache(fingerprintB)

    val deps = new IndexedSymbolTable
    assert(eventually(deps.get("com/example/Foo#fromA().", List(jarA)).isDefined), "jarA must resolve")
    assert(eventually(deps.get("com/example/Foo#fromB().", List(jarB)).isDefined), "jarB must resolve")
    val inA = deps.get("com/example/Foo#fromA().", List(jarA)).map(_.path).get
    val inB = deps.get("com/example/Foo#fromB().", List(jarB)).map(_.path).get
    assert(inA.startsWith(cacheDir(fingerprintA)), s"expected def from jarA, got $inA")
    assert(inB.startsWith(cacheDir(fingerprintB)), s"expected def from jarB, got $inB")
    assert(inA != inB)
  }

  test("corrupt index: lookup returns None and leaves the cache untouched") {
    val tempDir = os.temp.dir()
    val jar = writeJarPair(tempDir, "test-sources.jar", "com.example")
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)

    val deps = new IndexedSymbolTable
    assert(eventually(deps.get("com/example/Foo#", List(jar)).isDefined), "must index before corrupting")
    val indexPath = cacheDir(fingerprint) / "index.lmdb"
    os.remove.all(indexPath)
    os.write.over(indexPath, "garbage not an lmdb") // corrupt it

    // no recovery: lookup returns None, nothing is wiped or reindexed
    assertEquals(deps.get("com/example/Foo#", List(jar)), None, "corrupt index must not resolve")
    assert(!os.isDir(indexPath), "the corrupt file must NOT be replaced by a rebuild")
    assert(os.exists(cacheDir(fingerprint) / CacheMetadata.FileName), "metadata.json must remain untouched")
    assertEquals(deps.get("com/example/Foo#", List(jar)), None, "no self-healing: second lookup also None")
  }

  test("missing candidate jar is skipped") {
    val missing = os.temp.dir() / "nope-sources.jar"
    assert(!os.exists(missing))

    val deps = new IndexedSymbolTable
    assertEquals(deps.get("com/example/Foo#", List(missing)), None, "a missing jar must be skipped, not crash")
  }

  // ── dep-file candidates ──────────────────────────────────────

  test("candidatesForPath returns the owning jar for dep source files") {
    val tempDir = os.temp.dir()
    val jar = writeJarPair(tempDir, "test-sources.jar", "com.example")
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)

    val deps = new IndexedSymbolTable
    deps.registerTarget(List(jar))
    assert(eventually(deps.get("com/example/Foo#", List(jar)).isDefined))

    // the resolved def path IS a dep source file — its owning jar must come back
    val depFile = deps.get("com/example/Foo#", List(jar)).map(_.path).get
    val cands = deps.candidatesForPath(depFile)
    assert(cands.contains(jar), s"expected owning jar $jar, got $cands")

    // paths outside the cache (workspace files) → no candidates
    assertEquals(deps.candidatesForPath(os.temp.dir() / "Main.scala"), Nil)
    // JDK paths are deliberately excluded: the REAL JDK fingerprint must return Nil
    val javaHome = os.Path(System.getProperty("java.home"))
    if (os.exists(javaHome / "lib" / "src.zip")) {
      val jdkFp = Fingerprint.fromJdk(javaHome, System.getProperty("java.version"))
      assertEquals(deps.candidatesForPath(SourceJarIndexer.cacheRoot / os.RelPath(jdkFp) / "src" / "java/lang/Object.java"), Nil)
    }
    // a jdk-prefixed path that is NOT the real JDK fingerprint also returns Nil
    assertEquals(deps.candidatesForPath(SourceJarIndexer.cacheRoot / "jdk-21_x" / "src" / "java/lang/Object.java"), Nil)
    // cache paths without the fingerprint/src/ layout → no candidates
    assertEquals(deps.candidatesForPath(SourceJarIndexer.cacheRoot / "metadata.json"), Nil)
  }

  test("candidatesForPath recovers the source from metadata for unregistered jars") {
    val tempDir = os.temp.dir()
    val jar = writeJarPair(tempDir, "test-sources.jar", "com.example")
    val fingerprint = Fingerprint.fromJarPath(jar)
    cleanCache(fingerprint)

    val warmer = new IndexedSymbolTable
    warmer.registerTarget(List(jar))
    assert(eventually(warmer.get("com/example/Foo#", List(jar)).isDefined), "warm index must exist")
    val depFile = warmer.get("com/example/Foo#", List(jar)).map(_.path).get
    assert(os.exists(depFile), "extracted source should exist on disk")

    // a FRESH instance never registered the jar — the source must come from metadata.json
    val deps = new IndexedSymbolTable
    assert(deps.candidatesForPath(depFile).contains(jar),
      s"expected owning jar from metadata, got ${deps.candidatesForPath(depFile)}")
  }
}
