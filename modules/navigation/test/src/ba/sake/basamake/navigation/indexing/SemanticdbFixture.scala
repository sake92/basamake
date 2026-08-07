package ba.sake.basamake.navigation.indexing

/** Compiles a fixture copy with scala-cli (semanticdb enabled) so tests can use
  * REAL semanticdb output without committing binary .semanticdb files.
  *
  * Usage: copy a fixture to `<repo>/tmp/<test>-<ts>/`, then call
  * `SemanticdbFixture.compile(root)` — output lands at
  * `root/target/scala-3.8.4/meta/META-INF/semanticdb/<uri>.semanticdb`
  * (`--semanticdb-targetroot` pins it there, matching the tests' expectations).
  */
object SemanticdbFixture {

  /** Runs `scala-cli compile --server=false --semanticdb ...` inside `root`.
    * Returns the SemanticdbDirs for the generated output. Throws a descriptive
    * exception on missing scala-cli, compile failure, or no semanticdb output.
    */
  def compile(root: os.Path): SemanticdbDirs = {
    val scalaCli = Seq("scala-cli", "scala")
      .find(cmd => os.proc(cmd, "version").call(check = false).exitCode == 0)
      .getOrElse(throw new IllegalStateException(
        "scala-cli not found on PATH — required to generate semanticdb test fixtures"))

    val semTarget = root / "target" / "scala-3.8.4" / "meta"
    os.makeDir.all(semTarget)

    val res = os.proc(
      scalaCli, "compile", "--server=false",
      "--semanticdb",
      "--semanticdb-targetroot", semTarget.toString,
      "--dependency", "com.lihaoyi::upickle:4.0.0", // fixture Main.scala imports upickle
      "."
    ).call(cwd = root, check = false, stdout = os.Pipe, stderr = os.Pipe, timeout = 180_000L)

    if res.exitCode != 0 then
      throw new IllegalStateException(
        s"scala-cli compile failed in $root:\n${res.out.text()}\n${res.err.text()}")

    val semFiles = os.walk(semTarget).filter(_.ext == "semanticdb")
    require(semFiles.nonEmpty,
      s"scala-cli compile produced no .semanticdb files under $semTarget — check --semanticdb flag support")

    SemanticdbDirs(root, semTarget)
  }
}
