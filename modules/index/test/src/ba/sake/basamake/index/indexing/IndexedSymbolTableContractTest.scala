package ba.sake.basamake.index.indexing

import munit.FunSuite
import DepTestUtils.*

/** Contract tests for the deterministic dependency lookup pipeline: candidates
  * scope every lookup, the package filter (metadata.json) decides which jars
  * are touched at all, and the first hit wins — there is NO global route and NO
  * fallback search. */
class IndexedSymbolTableContractTest extends FunSuite, TestCacheRoot {

  private def cacheDir(fingerprint: String) = testCacheRoot / os.RelPath(fingerprint)

  private def cleanCache(fingerprint: String): Unit = {
    if (os.exists(cacheDir(fingerprint))) os.remove.all(cacheDir(fingerprint))
  }

  test("a symbol living only in an unrelated jar resolves to None") {
    val tempDir = os.temp.dir()
    val jarA = writeJarPair(tempDir, "a-sources.jar", "com.foo", methodName = "bar")
    val jarB = writeJarPair(tempDir, "b-sources.jar", "com.foo", methodName = "targetOnly")
    val fingerprintA = Fingerprint.fromJarPath(jarA)
    val fingerprintB = Fingerprint.fromJarPath(jarB)
    cleanCache(fingerprintA)
    cleanCache(fingerprintB)

    val deps = new IndexedSymbolTable(cacheRoot = testCacheRoot)
    // precondition: jarB DOES hold the symbol (warmed via background indexing)
    assert(eventually(deps.get("com/foo/Foo#targetOnly().", List(jarB)).isDefined), "jarB must hold the symbol")

    // target dep jarA does not contain it → None (jarB is out of scope)
    assertEquals(deps.get("com/foo/Foo#targetOnly().", List(jarA)), None)
    // route-regression guards: no global route, no fallback search
    assertEquals(deps.get("com/foo/Foo#targetOnly().", Nil), None, "no global route")
  }

  test("package filter skips jars whose metadata lacks the package") {
    val tempDir = os.temp.dir()
    val jarA = writeJarPair(tempDir, "a-sources.jar", "com.foo")
    val jarB = writeJarPair(tempDir, "b-sources.jar", "org.bar")
    val fingerprintA = Fingerprint.fromJarPath(jarA)
    val fingerprintB = Fingerprint.fromJarPath(jarB)
    cleanCache(fingerprintA)
    cleanCache(fingerprintB)

    val deps = new IndexedSymbolTable(cacheRoot = testCacheRoot)
    deps.registerTarget(List(jarA, jarB))
    assert(eventually(
      os.exists(cacheDir(fingerprintA) / "metadata.json") && os.exists(cacheDir(fingerprintB) / "metadata.json")),
      "both metadata.json files must be sprinkled")

    assert(eventually(deps.get("com/foo/Foo#", List(jarA, jarB)).isDefined),
      "must resolve from the package-matching jar A")
    assert(!os.isDir(cacheDir(fingerprintB) / "index.lmdb"), "jar B was indexed despite its metadata lacking the package")

    // behavioral proof B is never opened: a garbage index.lmdb FILE must survive untouched
    os.write.over(cacheDir(fingerprintB) / "index.lmdb", "garbage not an lmdb")
    assert(deps.get("com/foo/Foo#", List(jarA, jarB)).map(_.path).exists(_.startsWith(cacheDir(fingerprintA))),
      "second lookup must still resolve from A")
    assert(!os.isDir(cacheDir(fingerprintB) / "index.lmdb"), "jar B's index was opened and rebuilt — filter broken")
  }

  test("a missing symbol returns None — no fallback") {
    val tempDir = os.temp.dir()
    val jarA = writeJarPair(tempDir, "a-sources.jar", "com.foo")
    val fingerprintA = Fingerprint.fromJarPath(jarA)
    cleanCache(fingerprintA)

    val deps = new IndexedSymbolTable(cacheRoot = testCacheRoot)
    assert(eventually(deps.get("com/foo/Foo#", List(jarA)).isDefined), "jarA must index and resolve")

    assertEquals(deps.get("com/foo/DoesNotExist#", List(jarA)), None, "a miss must return None, no fallback")
  }

  test("same package in two candidate jars: symbol only in jarB resolves from jarB") {
    val tempDir = os.temp.dir()
    val jarA = writeJarPair(tempDir, "a-sources.jar", "com.foo", methodName = "fromA")
    val jarB = writeJarPair(tempDir, "b-sources.jar", "com.foo", methodName = "fromB")
    val fingerprintA = Fingerprint.fromJarPath(jarA)
    val fingerprintB = Fingerprint.fromJarPath(jarB)
    cleanCache(fingerprintA)
    cleanCache(fingerprintB)

    val deps = new IndexedSymbolTable(cacheRoot = testCacheRoot)
    assert(eventually(deps.get("com/foo/Foo#fromB().", List(jarA, jarB)).isDefined), "fromB must resolve")
    val inB = deps.get("com/foo/Foo#fromB().", List(jarA, jarB)).map(_.path).get
    assert(inB.startsWith(cacheDir(fingerprintB)), s"expected def from jarB, got $inB")
    assert(eventually(deps.get("com/foo/Foo#fromA().", List(jarA, jarB)).isDefined), "fromA must resolve")
    val inA = deps.get("com/foo/Foo#fromA().", List(jarA, jarB)).map(_.path).get
    assert(inA.startsWith(cacheDir(fingerprintA)), s"expected def from jarA, got $inA")
  }

  test("registering a target never indexes its jars; a lookup indexes only what it needs") {
    val tempDir = os.temp.dir()
    // 2 package-matching jars: one holds Foo#, the other only shares the package
    // (a lookup for Foo# must miss in the first and hit in the second — proving
    // every package-matching jar gets indexed, not just the one that hits)
    val matchingMiss = writeJarPair(tempDir, "baz-sources.jar", "com.foo", className = "Baz")
    val matchingHit = writeJarPair(tempDir, "foo-sources.jar", "com.foo")
    val irrelevant = (0 until 8).map(i => writeJarPair(tempDir, s"other-$i-sources.jar", s"org.pkg$i")).toList
    val jars = irrelevant ++ List(matchingMiss, matchingHit)

    val deps = new IndexedSymbolTable(cacheRoot = testCacheRoot)
    deps.registerTarget(jars)
    assert(eventually(jars.forall(j => os.exists(cacheDir(Fingerprint.fromJarPath(j)) / "metadata.json"))),
      "registerTarget must sprinkle metadata for every jar")

    assert(jars.forall(j => !os.isDir(cacheDir(Fingerprint.fromJarPath(j)) / "index.lmdb")),
      "registerTarget must NOT index any jar")

    assert(eventually(deps.get("com/foo/Foo#", jars).isDefined),
      "must resolve from matchingHit after background indexing")
    val hit = deps.get("com/foo/Foo#", jars).map(_.path).get
    assert(hit.startsWith(cacheDir(Fingerprint.fromJarPath(matchingHit))), s"expected def from matchingHit, got $hit")

    // both package-matching jars get indexed in the background; the irrelevant
    // 8 are NEVER indexed (package filter) — poll until the second matching
    // jar's background index has finished, then the set is stable
    assert(eventually {
      val indexed = jars.map(Fingerprint.fromJarPath).filter(fingerprint => os.isDir(cacheDir(fingerprint) / "index.lmdb")).toSet
      indexed == Set(Fingerprint.fromJarPath(matchingMiss), Fingerprint.fromJarPath(matchingHit))
    }, "exactly the 2 package-matching jars must be indexed")
  }

  test("100 candidate jars: only the 2 package-matching jars are ever indexed or queried") {
    val tempDir = os.temp.dir()
    // jars(0) and jars(1) are the 2 package-matching jars; the rest are irrelevant
    val jars = (0 until 100).map { i =>
      if (i < 2) writeJarPair(tempDir, s"matching-$i-sources.jar", "com.foo")
      else writeJarPair(tempDir, s"lib-$i-sources.jar", s"org.pkg$i")
    }.toList
    val fingerprints = jars.map(Fingerprint.fromJarPath)
    fingerprints.foreach(cleanCache)

    val deps = new IndexedSymbolTable(cacheRoot = testCacheRoot)
    deps.registerTarget(jars)
    assert(eventually(jars.forall(j => os.exists(cacheDir(Fingerprint.fromJarPath(j)) / "metadata.json"))),
      "registerTarget must sprinkle metadata for all 100 jars")

    // pre-index the 2 matching jars (background)
    assert(eventually(deps.get("com/foo/Foo#", List(jars(0), jars(1))).isDefined), "the 2 matching jars must resolve")

    // the 98 irrelevant jars come FIRST in candidate order — the package filter
    // must skip them without ever touching their indexes
    val lookupOrder = jars.drop(2) ++ jars.take(2)
    assert(eventually(deps.get("com/foo/Foo#", lookupOrder).isDefined), "lookup must skip irrelevant jars via package filter")
    assert(fingerprints.drop(2).forall(fingerprint => !os.isDir(cacheDir(fingerprint) / "index.lmdb")),
      "irrelevant jars were indexed — package filter broken")
  }
}
