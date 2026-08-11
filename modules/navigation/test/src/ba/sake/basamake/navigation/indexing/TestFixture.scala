package ba.sake.basamake.navigation.indexing

import scala.compiletime.uninitialized

/** Test helper: copies a named fixture from test/resources/examples/<name> into
  * a fresh ./tmp/<testName>-<timestamp>/ directory. Caller cleans up with
  * os.remove.all() in a finally block.
  *
  * Usage:
  *   val root = TestFixture.copy("nopackages", "nopkg-add")
  *   try { /* test using root-relative paths */ }
  *   finally os.remove.all(root)
  */
object TestFixture {

  private val examplesDir: os.Path =
    os.pwd / "modules" / "main" / "test" / "resources" / "examples"

  /** Copy fixture directory into a timestamped tmp dir. Returns the tmp root path. */
  def copy(fixtureName: String, testName: String): os.Path = {
    val src = examplesDir / fixtureName
    require(os.isDir(src), s"Test fixture not found: $src")
    val dst = os.pwd / "tmp" / s"${sanitize(testName)}-${System.currentTimeMillis()}"
    os.makeDir.all(dst)
    os.copy(src, dst, mergeFolders = true)
    dst
  }

  private def sanitize(name: String): String =
    name.replaceAll("[^a-zA-Z0-9_-]", "-").take(60)
}

/** Points `SourceJarIndexer.cacheRoot` at a fresh `./tmp/deps-cache-*` dir for the
  * duration of the suite and removes it afterwards — tests must never write into
  * the real `~/.basamake/deps` cache.
  *
  * Several suites use this trait, and deder may run multiple suites in one JVM.
  * `SourceJarIndexer.cacheRoot` is a shared mutable global, so acquire/release is
  * synchronized and reference-counted: all active suites in a JVM share ONE tmp
  * root (each suite uses distinct fingerprints, so there are no key collisions),
  * and the real cache root is restored only when the last suite finishes. This
  * makes the mutation race-free even if suites ever run concurrently. */
object TestCacheRootState {

  private var activeSuites = 0
  private var originalCacheRoot: os.Path = uninitialized
  private var sharedTestCacheRoot: os.Path = uninitialized

  /** Enter a suite that needs an isolated dep cache. Returns the shared tmp root. */
  def acquire(): os.Path = synchronized {
    if (activeSuites == 0) {
      originalCacheRoot = SourceJarIndexer.cacheRoot
      sharedTestCacheRoot = os.pwd / "tmp" / s"deps-cache-test-${System.currentTimeMillis()}"
      SourceJarIndexer.cacheRoot = sharedTestCacheRoot
    }
    activeSuites += 1
    sharedTestCacheRoot
  }

  /** Leave a suite. Restores the real cache root + cleans the tmp dir on the last exit. */
  def release(): Unit = synchronized {
    activeSuites -= 1
    if (activeSuites == 0) {
      SourceJarIndexer.cacheRoot = originalCacheRoot
      os.remove.all(sharedTestCacheRoot)
    }
  }
}

trait TestCacheRoot { self: munit.FunSuite =>

  private var testCacheRoot: os.Path = uninitialized

  override def beforeAll(): Unit = {
    testCacheRoot = TestCacheRootState.acquire()
  }

  override def afterAll(): Unit = {
    TestCacheRootState.release()
  }
}
