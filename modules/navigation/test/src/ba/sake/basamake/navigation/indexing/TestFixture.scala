package ba.sake.basamake.navigation.indexing

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
