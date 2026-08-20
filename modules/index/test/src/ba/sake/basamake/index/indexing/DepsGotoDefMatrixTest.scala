package ba.sake.basamake.index.indexing

import munit.FunSuite
import ba.sake.basamake.index.*
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

/** Exhaustive dep goto-def matrix over REAL cats/sttp/commons-net source jars.
  * Two modes: source-parse (no semanticdb dir — the resolver does the work) and
  * semanticdb-paired (hand-crafted compiler symbols, added in Task 9).
  * Fixture files live in test/resources/examples/dep-matrix/ and are copied to
  * ./tmp/<test>-<ts>/ (never mutated in place). */
class DepsGotoDefMatrixTest extends FunSuite, TestCacheRoot {

  // the matrix warms multiple real jars (background indexes) and probes them —
  // well beyond munit's default 30s under parallel-suite load
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "s")

  private def fixtureJar(name: String): os.Path =
    os.pwd / "test" / "resources" / "jars" / name

  private val catsCore    = fixtureJar("cats-core_3-2.13.0-sources.jar")
  private val catsKernel  = fixtureJar("cats-kernel_3-2.13.0-sources.jar")
  private val catsEffect  = fixtureJar("cats-effect_3-3.7.0-sources.jar")
  private val catsEffectKernel = fixtureJar("cats-effect-kernel_3-3.7.0-sources.jar")
  private val commonsNet  = fixtureJar("commons-net-3.9.0-sources.jar")

  /** All jars registered as ONE BSP target, like a real single-target project.
    * cats-effect-kernel included: cats-effect 3.7.0 re-exports `cats.effect.Ref`
    * as an alias but the real trait lives in the kernel artifact — a real
    * project classpath always carries it. The sttp jars are NOT registered
    * here: the only sttp probe (basicRequest) pins a documented source-parse
    * gap (asserts empty) and the sttp jars' on-demand indexing would only add
    * CPU load to the parallel suite runs. */
  private val allJars = List(catsCore, catsKernel, catsEffect, catsEffectKernel, commonsNet)

  private def eventually(cond: => Boolean, timeoutMs: Long = 90000): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(100)
    cond
  }

  /** Poll `gotoDefinitions` until nonEmpty or 60s — background jar indexes warm
    * asynchronously; returns the last result. */
  private def eventuallyResult(
      idx: WorkspaceIndex,
      file: os.Path,
      l: Int,
      c: Int,
      candidates: List[os.Path]
  ): Vector[SymbolDefinition] = {
    val deadline = System.currentTimeMillis() + 60000
    var last = idx.gotoDefinitions(file, l, c, depCandidates = candidates)
    while (last.isEmpty && System.currentTimeMillis() < deadline) {
      Thread.sleep(100)
      last = idx.gotoDefinitions(file, l, c, depCandidates = candidates)
    }
    last
  }

  /** Multi-entry sources jar + sibling classes jar (one dummy .class per source
    * entry, so metadata.json package filtering works). Copied from SourceNavigationTest. */
  private def writeJar(dir: os.Path, name: String, entries: List[(String, String)]): os.Path = {
    val sourcesJar = dir / name
    val sources = new ZipOutputStream(new FileOutputStream(sourcesJar.toIO))
    try {
      entries.foreach { case (entry, content) =>
        sources.putNextEntry(new ZipEntry(entry)); sources.write(content.getBytes("UTF-8")); sources.closeEntry()
      }
    } finally sources.close()
    val classesJar = dir / (name.stripSuffix("-sources.jar") + ".jar")
    val classes = new ZipOutputStream(new FileOutputStream(classesJar.toIO))
    try {
      entries.foreach { case (entry, _) =>
        val base = entry.split('/').last.stripSuffix(".scala").stripSuffix(".java")
        val pkgPath = entry.split('/').toList.dropRight(1).mkString("/")
        classes.putNextEntry(new ZipEntry(s"$pkgPath/$base.class")); classes.write(Array[Byte](1, 2)); classes.closeEntry()
      }
    } finally classes.close()
    sourcesJar
  }

  /** Setup for one fixture file: copy workspace, register target, initialize,
    * open the file. Returns (idx, depsTable, file). */
  private def setup(fileName: String): (WorkspaceIndex, IndexedSymbolTable, os.Path) = {
    val ws = TestFixture.copy("dep-matrix", s"matrix-${fileName.stripSuffix(".scala").stripSuffix(".java")}")
    val file = ws / fileName
    val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
    depsTable.registerTarget(allJars)
    warmAll(depsTable)
    val idx = new WorkspaceIndex(ws, new InMemorySymbolTable, Some(depsTable))
    idx.initialize(Nil) // no semanticdb roots → source-parse mode
    idx.onDidOpen(file)
    (idx, depsTable, file)
  }

  private def goto(file: os.Path, idx: WorkspaceIndex, text: String, probe: String): Vector[SymbolDefinition] = {
    val (l, c) = TestPositions.at(text, probe)
    idx.gotoDefinitions(file, l, c, depCandidates = allJars)
  }

  /** Warm the background jar indexes so the probe loop sees ready tables
    * (a cold jar misses fast by design — see IndexedSymbolTable docs). */
  private def warm(deps: IndexedSymbolTable, probes: List[(String, os.Path)]): Unit = {
    probes.foreach { case (sym, jar) =>
      assert(eventually(deps.get(sym, List(jar)).isDefined), s"warm-up must index $sym from $jar")
    }
  }

  /** Warm EVERY registered fixture jar: miss-lookups (wildcard candidates, the
    * basicRequest gap) touch all unfilterable jars and background-index them —
    * the suite must not end with an index still in flight (afterAll would race
    * it). */
  private def warmAll(deps: IndexedSymbolTable): Unit = warm(deps, List(
    "cats/Monad#" -> catsCore,
    "cats/kernel/Eq#" -> catsKernel,
    "cats/effect/IO#" -> catsEffect,
    "cats/effect/kernel/Ref#" -> catsEffectKernel,
    "org/apache/commons/net/ftp/FTPClient#" -> commonsNet
  ))

  // ── Main.scala matrix ──────────────────────────────────────

  test("matrix Main.scala: all import-line and body probes resolve (source-parse)") {
    val (idx, deps, file) = setup("Main.scala")
    // warm every jar the probes touch (background indexes; cold = fast miss)
    warm(deps, List(
      "cats/Monad#" -> catsCore,
      "cats/data/EitherT#" -> catsCore,
      "cats/effect/IO#" -> catsEffect,
      "cats/effect/kernel/Ref#" -> catsEffectKernel,
      "org/apache/commons/net/ftp/FTPClient#" -> commonsNet
    ))
    val text = os.read(file)
    // import lines resolve immediately (dual candidate emission, existing behavior)
    val probes = List(
      "import cats.(?<p>Monad)"          -> Set("cats/Monad#"),
      "import cats.data.(?<p>EitherT)"   -> Set("cats/data/EitherT#"),
      "import cats.effect.\\{(?<p>IO), IOApp\\}" -> Set("cats/effect/IO#", "cats/effect/IO."),
      "IO.\\{(?<p>pure) => ioPure\\}"    -> Set("cats/effect/IO.pure().", "cats/effect/IO.pure(+1)."),
      "import cats.effect.kernel.(?<p>Ref)" -> Set("cats/effect/kernel/Ref#"),
      "import org.apache.commons.net.ftp.(?<p>FTPClient)" -> Set("org/apache/commons/net/ftp/FTPClient#")
    )
    // body probes (the NEW capability — fail until Task 5 lands)
    val bodyProbes = List(
      "val m: (?<p>Monad)\\[Option\\]"                        -> Set("cats/Monad#"),
      "= (?<p>Monad)\\[Option\\]"                             -> Set("cats/Monad."),
      "val et: (?<p>EitherT)\\[Option, String, Int\\]"      -> Set("cats/data/EitherT#"),
      "EitherT\\.(?<p>leftT)\\(\"x\"\\)"                    -> Set("cats/data/EitherT.leftT().", "cats/data/EitherT.leftT(+1)."),
      "val io: (?<p>IO)\\[Unit\\]"                          -> Set("cats/effect/IO#", "cats/effect/IO."),
      "= (?<p>IO)\\.unit"                                   -> Set("cats/effect/IO.", "cats/effect/IO#"),
      "io\\.(?<p>flatMap)\\(_ => io\\)"                     -> Set("cats/effect/IO#flatMap().", "cats/effect/IO#flatMap(+1)."),
      "def go\\[F\\[_\\]: (?<p>Monad)\\]\\(fa: F\\[Int\\]\\)" -> Set("cats/Monad#"),
      "fa\\.(?<p>map)\\(_ \\+ 1\\)"                         -> Set("cats/Monad#map().", "cats/Monad#map(+1)."),
      "val ftp: (?<p>FTPClient) = new FTPClient\\(\\)"      -> Set("org/apache/commons/net/ftp/FTPClient#"),
      "new (?<p>FTPClient)\\(\\)"                           -> Set("org/apache/commons/net/ftp/FTPClient#"),
      "val r: (?<p>Ref)\\[IO, Int\\]"                       -> Set("cats/effect/kernel/Ref#"),
      "(?<p>Ref)\\.unsafe\\[IO, Int\\]\\(0\\)"              -> Set("cats/effect/kernel/Ref.")
    )
    (probes ++ bodyProbes).foreach { case (probe, want) =>
      val locs = goto(file, idx, text, probe)
      assert(locs.nonEmpty, s"probe [$probe] must resolve, got empty (want any of $want)")
      if (want.nonEmpty) assert(locs.map(_.symbol).toSet.intersect(want).nonEmpty,
        s"probe [$probe] resolved to ${locs.map(_.symbol)} — expected any of $want")
    }
    // wildcard import: no name to resolve — cursor on `_` stays empty (documented)
    val (lW, cW) = TestPositions.at(text, "cats.implicits.(?<p>_)")
    assertEquals(idx.gotoDefinitions(file, lW, cW, depCandidates = allJars), Vector.empty,
      "wildcard importee has no symbol to resolve")
  }

  /** basicRequest is inherited from trait SttpApi via `package object client3
    * extends SttpApi` — a compiler-level gap in source-parse mode (the resolver
    * cannot know the package object's parent). Known limitation, documented in
    * .agents/AGENTS.md; pinned here as an explicit expected-gap probe so a
    * future fix (package-object parent walk) has a regression test ready. */
  test("matrix Main.scala: basicRequest stays an expected source-parse gap") {
    val (idx, _, file) = setup("Main.scala")
    val text = os.read(file)
    val (l, c) = TestPositions.at(text, "import sttp.client3.(?<p>basicRequest)")
    assertEquals(idx.gotoDefinitions(file, l, c, depCandidates = allJars), Vector.empty,
      "basicRequest (val inherited through a package object) is a documented source-parse gap")
  }

  // ── Inheritance.scala matrix (extends/with for ALL def kinds) ──

  test("matrix Inheritance.scala: extends/with parents resolve for all def kinds (source-parse)") {
    val (idx, deps, file) = setup("Inheritance.scala")
    warm(deps, List(
      "cats/Monad#" -> catsCore,
      "cats/Alternative#" -> catsCore,
      "cats/effect/IOApp#" -> catsEffect,
      "cats/effect/IO#" -> catsEffect
    ))
    val text = os.read(file)
    val probes = List(
      "trait Combined\\[F\\[_\\]\\] extends (?<p>Monad)\\[F\\]"      -> Set("cats/Monad#"),
      "extends Monad\\[F\\] with (?<p>Alternative)\\[F\\]"            -> Set("cats/Alternative#"),
      "class Runner extends (?<p>IOApp) with Combined\\[IO\\]"        -> Set("cats/effect/IOApp#", "cats/effect/IOApp."),
      "extends IOApp with (?<p>Combined)\\[IO\\]"                     -> Set("demo/Combined#"),
      "object Inner extends (?<p>IOApp)"                              -> Set("cats/effect/IOApp#", "cats/effect/IOApp."),
      "final class FullQualified extends cats.effect.(?<p>IOApp)"     -> Set("cats/effect/IOApp#", "cats/effect/IOApp."),
      "given monadList: (?<p>Monad)\\[List\\] with"                   -> Set("cats/Monad#"),
      "def run\\(args: List\\[String\\]\\): (?<p>IO)\\[ExitCode\\]"   -> Set("cats/effect/IO#", "cats/effect/IO.")
    )
    probes.foreach { case (probe, want) =>
      val locs = goto(file, idx, text, probe)
      assert(locs.nonEmpty, s"probe [$probe] must resolve, got empty (want any of $want)")
      assert(locs.map(_.symbol).toSet.intersect(want).nonEmpty,
        s"probe [$probe] resolved to ${locs.map(_.symbol)} — expected any of $want")
    }
    // JDK parent (`enum Status extends java.lang.Enum[Status]`): the occurrence
    // is emitted at parse time, but the JDK src.zip index is not built in tests
    // (takes minutes) — assert the parse-level occurrence instead.
    val (lEnum, cEnum) = TestPositions.at(text, "extends java.lang.(?<p>Enum)\\[Status\\]")
    assert(idx.findSymbolsAt(file, lEnum, cEnum).contains("java/lang/Enum#"),
      "enum parent must emit the JDK Enum type ref at parse time")
  }

  // ── semanticdb gap-merge ─────────────────────────────────────

  test("semanticdb gap: a compiler-dropped body ref falls back to source-parse") {
    // The compiler emitted refs for the import line but NOT for the body usage
    // (the empty-symbol cross-document SUID case). Gap-merge must fill it.
    val ws = os.temp.dir(prefix = "matrix-gap-ws-")
    val file = ws / "Main.scala"
    val text = "import cats.Monad\nobject M { val m: Monad[Option] = Monad[Option] }\n"
    os.write.over(file, text)
    val semDir = ws / ".semanticdb"
    os.makeDir.all(semDir)
    val doc = scala.meta.internal.semanticdb.TextDocument(
      schema = scala.meta.internal.semanticdb.Schema.SEMANTICDB4,
      uri = "Main.scala", text = text, language = scala.meta.internal.semanticdb.Language.SCALA,
      symbols = Nil,
      occurrences = List(
        scala.meta.internal.semanticdb.SymbolOccurrence(
          symbol = "cats/Monad#",
          range = Some(scala.meta.internal.semanticdb.Range(0, 7, 0, 12)),
          role = scala.meta.internal.semanticdb.SymbolOccurrence.Role.REFERENCE)
        // NOTE: no occurrence at the body `Monad` — the gap
      )
    )
    os.write(semDir / "Main.scala.semanticdb",
      scala.meta.internal.semanticdb.TextDocuments(List(doc)).toByteArray)
    try {
      val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      depsTable.registerTarget(allJars)
      warmAll(depsTable)
      val idx = new WorkspaceIndex(ws, new InMemorySymbolTable, Some(depsTable))
      idx.initialize(List(SemanticdbDirs(ws, semDir)))
      idx.onDidOpen(file)
      val (l, c) = TestPositions.at(text, "Monad\\[Option\\] = (?<p>Monad)\\[Option\\]")
      assert(eventually(idx.gotoDefinitions(file, l, c, depCandidates = allJars).nonEmpty),
        "gap-merge must fill compiler-dropped body refs from source-parse")
    } finally {
      os.remove.all(ws)
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(catsCore)))
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(catsKernel)))
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(catsEffect)))
    }
  }

  // ── top-level implicit-class conversion-method owner ─────────

  test("top-level implicit class in a dep resolves via the conversion-method importee symbol") {
    val jarDir = os.temp.dir(prefix = "matrix-implicit-")
    val ws = os.temp.dir(prefix = "matrix-implicit-ws-")
    // top-level implicit class in the dep — its importee symbol is the
    // conversion METHOD owned by the top-level `X$package.` wrapper
    // (verified against a real Scala 3.7.4 semanticdb dump: the compiler emits
    // `demo/Dump$package.TopConv().` — wrapper-owned)
    val jar = writeJar(jarDir, "implicit-sources.jar", List(
      "demo/TopConv.scala" -> "package demo\nimplicit class TopConv(val i: Int) { def topTwice: Int = i * 2 }\n"
    ))
    val file = ws / "Main.scala"
    val text = "import demo.TopConv\nobject M { val x: Int = 1.topTwice }\n"
    os.write.over(file, text)
    try {
      val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      depsTable.registerTarget(List(jar))
      // the WRAPPER-OWNED conversion method must be INDEXED (compiler-truth key)
      assert(eventually(depsTable.get("demo/TopConv$package.TopConv().", List(jar)).isDefined),
        "the wrapper-owned conversion method must be indexed")

      // semanticdb-paired importee (the compiler's symbol) resolves into the dep
      val semDir = ws / ".semanticdb"
      os.makeDir.all(semDir)
      val doc = scala.meta.internal.semanticdb.TextDocument(
        schema = scala.meta.internal.semanticdb.Schema.SEMANTICDB4,
        uri = "Main.scala", text = text, language = scala.meta.internal.semanticdb.Language.SCALA,
        symbols = Nil,
        occurrences = List(
          scala.meta.internal.semanticdb.SymbolOccurrence(
            symbol = "demo/TopConv$package.TopConv().",
            range = Some(scala.meta.internal.semanticdb.Range(0, 7, 0, 14)),
            role = scala.meta.internal.semanticdb.SymbolOccurrence.Role.REFERENCE)
        )
      )
      os.write(semDir / "Main.scala.semanticdb",
        scala.meta.internal.semanticdb.TextDocuments(List(doc)).toByteArray)
      val idx = new WorkspaceIndex(ws, new InMemorySymbolTable, Some(depsTable))
      idx.initialize(List(SemanticdbDirs(ws, semDir)))
      idx.onDidOpen(file)
      val (l, c) = TestPositions.at(text, "import demo.(?<p>TopConv)")
      val syms = eventuallyResult(idx, file, l, c, List(jar))
      assert(syms.map(_.symbol).contains("demo/TopConv$package.TopConv()."),
        s"wrapper-owned conversion method must resolve from the compiler symbol, got $syms")
    } finally {
      os.remove.all(jarDir); os.remove.all(ws)
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(jar)))
    }
  }

  // ── Java extends/implements refs ─────────────────────────────

  test("Java extends/implements resolve (workspace → dep + JDK, source-parse)") {
    val (idx, deps, file) = setup("JavaInheritance.java")
    val text = os.read(file)
    // (a) extends a dep type — full goto-def into commons-net
    val (lExt, cExt) = TestPositions.at(text, "extends (?<p>FTPClient)")
    val extLocs = idx.gotoDefinitions(file, lExt, cExt, depCandidates = allJars)
    assert(extLocs.nonEmpty, s"Java extends probe must resolve, got empty")
    assert(extLocs.map(_.symbol).contains("org/apache/commons/net/ftp/FTPClient#"),
      s"extends resolved to ${extLocs.map(_.symbol)}")
    // (b)/(c) JDK types: the occurrence is emitted at parse time, but the JDK
    // src.zip index is not built in tests (takes minutes) — parse-level assert
    val (lImpl, cImpl) = TestPositions.at(text, "implements (?<p>List)<String>")
    assert(idx.findSymbolsAt(file, lImpl, cImpl).contains("java/util/List#"),
      "implements must emit the JDK List type ref at parse time")
    val (lArr, cArr) = TestPositions.at(text, "ArrayList<String> items = new (?<p>ArrayList)<>")
    assert(idx.findSymbolsAt(file, lArr, cArr).contains("java/util/ArrayList#"),
      "field type must emit the JDK ArrayList type ref at parse time")
  }

  test("Java dep-file extends resolves inside the dep (same-jar + cross-jar)") {
    // commons-net's FTPClient extends FTP (same jar)
    val ws = os.temp.dir(prefix = "matrix-java-dep-")
    try {
      val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      depsTable.registerTarget("t", List(commonsNet))
      assert(eventually(depsTable.get("org/apache/commons/net/ftp/FTPClient#", List(commonsNet)).isDefined),
        "warm commons-net")
      val ftpFile = depsTable.get("org/apache/commons/net/ftp/FTPClient#", List(commonsNet)).get.path
      val idx = new WorkspaceIndex(ws, new InMemorySymbolTable, Some(depsTable))
      idx.onDidOpen(ftpFile)
      val text = os.read(ftpFile)
      val (l, c) = TestPositions.at(text, "extends (?<p>FTP)")
      val locs = idx.gotoDefinitions(ftpFile, l, c, depCandidates = depsTable.candidatesForPath(ftpFile))
      assert(locs.nonEmpty, "dep-file extends must resolve")
      assert(locs.map(_.symbol).contains("org/apache/commons/net/ftp/FTP#"))
    } finally {
      os.remove.all(ws)
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(commonsNet)))
    }
  }

  // ── semanticdb-mode matrix cases ─────────────────────────────

  test("semanticdb mode: importees + extends parents resolve into cats (compiler symbols)") {
    val ws = os.temp.dir(prefix = "matrix-sdb-ws-")
    val file = ws / "Main.scala"
    val text = "import cats.effect.IO\nclass App extends IOApp { def run(args: List[String]): IO[ExitCode] = IO.pure(ExitCode.Success) }\n"
    os.write.over(file, text)
    val semDir = ws / ".semanticdb"
    os.makeDir.all(semDir)
    val doc = scala.meta.internal.semanticdb.TextDocument(
      schema = scala.meta.internal.semanticdb.Schema.SEMANTICDB4, uri = "Main.scala",
      text = text, language = scala.meta.internal.semanticdb.Language.SCALA, symbols = Nil,
      occurrences = List(
        // import line: trait importee = synthetic companion TERM (verified shape)
        scala.meta.internal.semanticdb.SymbolOccurrence(
          symbol = "cats/effect/IO.", range = Some(scala.meta.internal.semanticdb.Range(0, 19, 0, 21)),
          role = scala.meta.internal.semanticdb.SymbolOccurrence.Role.REFERENCE),
        // extends parent: synthetic companion TERM + type
        scala.meta.internal.semanticdb.SymbolOccurrence(
          symbol = "cats/effect/IOApp.", range = Some(scala.meta.internal.semanticdb.Range(1, 17, 1, 22)),
          role = scala.meta.internal.semanticdb.SymbolOccurrence.Role.REFERENCE),
        scala.meta.internal.semanticdb.SymbolOccurrence(
          symbol = "cats/effect/IOApp#", range = Some(scala.meta.internal.semanticdb.Range(1, 17, 1, 22)),
          role = scala.meta.internal.semanticdb.SymbolOccurrence.Role.REFERENCE)
      )
    )
    os.write(semDir / "Main.scala.semanticdb", scala.meta.internal.semanticdb.TextDocuments(List(doc)).toByteArray)
    try {
      val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      depsTable.registerTarget(allJars)
      warmAll(depsTable)
      val idx = new WorkspaceIndex(ws, new InMemorySymbolTable, Some(depsTable))
      idx.initialize(List(SemanticdbDirs(ws, semDir)))
      idx.onDidOpen(file)
      val (lImp, cImp) = TestPositions.at(text, "import cats.effect.(?<p>IO)")
      assert(eventuallyResult(idx, file, lImp, cImp, allJars).nonEmpty,
        "semanticdb importee must resolve into cats-effect")
      val (lExt, cExt) = TestPositions.at(text, "extends (?<p>IOApp)")
      assert(eventuallyResult(idx, file, lExt, cExt, allJars).nonEmpty,
        "semanticdb extends parent must resolve into cats-effect")
    } finally {
      os.remove.all(ws)
      allJars.foreach(j => os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(j))))
    }
  }

  // ── dep-file navigation (the user's cats/sttp scenario) ──────

  test("dep-file navigation: same-jar extends + cross-jar import inside cats sources") {
    val ws = os.temp.dir(prefix = "matrix-depnav-ws-")
    try {
      val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      depsTable.registerTarget("t", allJars)
      // cats.Monad extends FlatMap with Applicative — all in cats-core
      assert(eventually(depsTable.get("cats/Monad#", List(catsCore)).isDefined), "warm cats-core")
      val monadFile = depsTable.get("cats/Monad#", List(catsCore)).get.path
      val idx = new WorkspaceIndex(ws, new InMemorySymbolTable, Some(depsTable))
      idx.onDidOpen(monadFile)
      val text = os.read(monadFile)
      val cands = depsTable.candidatesForPath(monadFile)
      assert(cands.contains(catsKernel), "target-scoped candidates must include cats-kernel")
      // same-jar parents
      val (lFlat, cFlat) = TestPositions.at(text, "extends (?<p>FlatMap)\\[F\\]")
      val flatLocs = idx.gotoDefinitions(monadFile, lFlat, cFlat, depCandidates = cands)
      assert(flatLocs.map(_.symbol).contains("cats/FlatMap#"), s"got ${flatLocs.map(_.symbol)}")
      val (lApp, cApp) = TestPositions.at(text, "with (?<p>Applicative)\\[F\\]")
      val appLocs = idx.gotoDefinitions(monadFile, lApp, cApp, depCandidates = cands)
      assert(appLocs.map(_.symbol).contains("cats/Applicative#"), s"got ${appLocs.map(_.symbol)}")
    } finally {
      os.remove.all(ws)
      allJars.foreach(j => os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(j))))
    }
  }

  test("dep-file navigation: cross-jar import (cats-core → cats-kernel) resolves") {
    val ws = os.temp.dir(prefix = "matrix-depnav2-ws-")
    try {
      val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      depsTable.registerTarget("t", allJars)
      // cats.Foldable imports cats.kernel.CommutativeMonoid — a cross-artifact ref
      assert(eventually(depsTable.get("cats/Foldable#", List(catsCore)).isDefined), "warm cats-core")
      assert(eventually(depsTable.get("cats/kernel/CommutativeMonoid#", List(catsKernel)).isDefined),
        "warm cats-kernel")
      val foldableFile = depsTable.get("cats/Foldable#", List(catsCore)).get.path
      val idx = new WorkspaceIndex(ws, new InMemorySymbolTable, Some(depsTable))
      idx.onDidOpen(foldableFile)
      val text = os.read(foldableFile)
      val cands = depsTable.candidatesForPath(foldableFile)
      assert(cands.contains(catsKernel), "target-scoped candidates must include cats-kernel")

      // import line
      val (lImp, cImp) = TestPositions.at(text, "import cats.kernel.(?<p>CommutativeMonoid)")
      val impLocs = idx.gotoDefinitions(foldableFile, lImp, cImp, depCandidates = cands)
      assert(impLocs.map(_.symbol).contains("cats/kernel/CommutativeMonoid#"),
        s"cross-jar import ref must resolve, got ${impLocs.map(_.symbol)}")

      // context-bound body usage
      val (lBody, cBody) = TestPositions.at(text, "def unorderedFold\\[A: (?<p>CommutativeMonoid)\\]")
      val bodyLocs = idx.gotoDefinitions(foldableFile, lBody, cBody, depCandidates = cands)
      assert(bodyLocs.map(_.symbol).contains("cats/kernel/CommutativeMonoid#"),
        s"cross-jar body ref must resolve, got ${bodyLocs.map(_.symbol)}")
    } finally {
      os.remove.all(ws)
      allJars.foreach(j => os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(j))))
    }
  }
}
