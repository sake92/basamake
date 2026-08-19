package ba.sake.basamake.lsp

/** Copies a committed fixture project into ./tmp/<test>-<ts>/, generates a real
  * .bsp config via `scala-cli setup-ide` (Basamake discovers it with its own
  * BspDiscovery) AND writes a .basamake/config.json that disables JDK eager
  * indexing and points the dep cache INSIDE the tmp copy (depsCacheRoot is
  * workspace-relative — everything is removed by os.remove.all(root)).
  * Caller cleans up with os.remove.all(root). */
object BspProjectFixture {

  def prepare(fixtureName: String, testName: String): os.Path = {
    val src = os.pwd / "test" / "resources" / "projects" / fixtureName
    require(os.isDir(src), s"Fixture project not found: $src")
    val dst = os.pwd / "tmp" / s"${sanitize(testName)}-${System.currentTimeMillis()}"
    os.makeDir.all(dst)
    os.copy(src, dst, mergeFolders = true)

    // Test configuration via the SAME public mechanism a user would use — no
    // global cacheRoot mutation, no test-only server flags.
    os.write.over(dst / ".basamake" / "config.json",
      """{"enableJdkIndexing": false, "depsCacheRoot": "deps-cache"}""",
      createFolders = true)

    val scalaCli = Seq("scala-cli", "scala")
      .find(cmd => {
        try os.proc(cmd, "version").call(check = false).exitCode == 0
        catch { case _: Exception => false }
      })
      .getOrElse(throw new IllegalStateException(
        "scala-cli not on PATH — required to generate BSP test fixtures. " +
          "Install scala-cli (https://scala-cli.virtuslab.org) or add it to PATH (CI already does)."))

    val res = os.proc(scalaCli, "setup-ide", ".")
      .call(cwd = dst, check = false, stdout = os.Pipe, stderr = os.Pipe, timeout = 120_000L)
    require(res.exitCode == 0, s"scala-cli setup-ide failed in $dst:\n${res.out.text()}\n${res.err.text()}")
    require(
      os.isDir(dst / ".bsp") && os.list(dst / ".bsp").exists(_.last == "scala-cli.json"),
      s".bsp/scala-cli.json not generated in $dst"
    )
    dst
  }

  private def sanitize(name: String): String =
    name.replaceAll("[^a-zA-Z0-9_-]", "-").take(60)
}
