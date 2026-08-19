package ba.sake.basamake.index.indexing

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
    os.pwd / "test" / "resources" / "examples"

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

/** Fresh per-suite dep cache under ./tmp/ — tests must never write into the real
  * XDG cache. Each suite gets its OWN root (a lazy val — created on first use),
  * so parallel suites cannot race: there is no shared mutable state at all.
  * Suites pass it explicitly to `IndexedSymbolTable(cacheRoot = testCacheRoot)`
  * and `SourceJarIndexer.index/extractEntry(..., testCacheRoot)`. */
trait TestCacheRoot { self: munit.FunSuite =>

  protected lazy val testCacheRoot: os.Path =
    os.pwd / "tmp" / s"deps-cache-${sanitize(getClass.getSimpleName)}-${System.currentTimeMillis()}"

  override def afterAll(): Unit = {
    if (os.exists(testCacheRoot)) os.remove.all(testCacheRoot)
  }

  private def sanitize(name: String): String =
    name.replaceAll("[^a-zA-Z0-9_-]", "-").take(60)
}
