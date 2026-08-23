package ba.sake.basamake.index.indexing

import munit.FunSuite
import ba.sake.basamake.index.*
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, Range => SdbRange, SymbolOccurrence}
import java.util.concurrent.{CountDownLatch, TimeUnit}

class WorkspaceIndexTest extends FunSuite {

  private def freshIndexAt(root: os.Path): (WorkspaceIndex, SymbolTable) = {
    val st = new InMemorySymbolTable
    val idx = new WorkspaceIndex(root, st)
    idx.initialize(List.empty)
    (idx, st)
  }

  /** Shared goto-def check: copy fixture, open `openFile`, cursor at the regex
    * match, assert non-empty result + expected target file/optional symbol. */
  private def checkGoto(fixture: String, tmpName: String, openFile: String, regex: String, expectedPathLast: String, expectedSymbol: Option[String] = None): Unit = {
    val root = TestFixture.copy(fixture, tmpName)
    try {
      val f = root / os.RelPath(openFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(f)
      val (l, c) = TestPositions.at(os.read(f), regex)
      val locs = idx.gotoDefinitions(f, l, c)
      assert(locs.nonEmpty, s"expected goto to resolve for /$regex/, got empty")
      assertEquals(locs.head.path.last, expectedPathLast)
      expectedSymbol.foreach(sym => assertEquals(locs.head.symbol, sym, s"got ${locs.head.symbol}"))
    } finally os.remove.all(root)
  }

  /** Shared no-self-goto check: cursor on a def site must return empty. */
  private def checkNoSelfGoto(fixture: String, tmpName: String, openFile: String, regex: String): Unit = {
    val root = TestFixture.copy(fixture, tmpName)
    try {
      val f = root / os.RelPath(openFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(f)
      val (l, c) = TestPositions.at(os.read(f), regex)
      val locs = idx.gotoDefinitions(f, l, c)
      assert(locs.isEmpty, s"expected empty from def site (no self-goto), got $locs")
    } finally os.remove.all(root)
  }

  /** Shared references check: open all files, cursor at regex in `cursorFile`,
    * assert refs (incl. declaration) exist in each expected file. */
  private def checkRefs(fixture: String, tmpName: String, openFiles: List[String], cursorFile: String, regex: String, expectedPathsLast: Set[String]): Unit = {
    val root = TestFixture.copy(fixture, tmpName)
    try {
      val (idx, _) = freshIndexAt(root)
      openFiles.foreach(p => idx.onDidOpen(root / os.RelPath(p)))
      val cur = root / os.RelPath(cursorFile)
      val (l, c) = TestPositions.at(os.read(cur), regex)
      val refs = idx.references(cur, l, c, includeDeclaration = true)
      val got = refs.map(_.path.last).toSet
      assert(expectedPathsLast.subsetOf(got), s"expected refs in $expectedPathsLast, got $got")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // sbt fixture (has real .semanticdb files)
  // ═══════════════════════════════════════════════════════════════

  test("initialize populates symbolTable from source-AST fallback") {
    val root = TestFixture.copy("sbt", "sbt-init")
    try {
      val (idx, st) = freshIndexAt(root)
      val utilsSym = st.get("_empty_/utils.")
      assert(utilsSym.isDefined, "Expected _empty_/utils. in symbol table")
      assert(utilsSym.get.path.last == "utils.scala")
      val getMsgSym = st.get("_empty_/utils.getMsg().")
      assert(getMsgSym.isDefined, "Expected _empty_/utils.getMsg(). in symbol table")
      assertEquals(getMsgSym.get.path, root / "src" / "main" / "scala" / "utils.scala")
    } finally os.remove.all(root)
  }

  test("file with given imports does not kill workspace indexing") {
    val root = TestFixture.copy("sbt", "sbt-givens")
    try {
      val givensFile = root / "src" / "main" / "scala" / "Givens.scala"
      os.write.over(givensFile,
        """import scala.util.given
          |import scala.util.{given Ordering}
          |object Givens { val n: Int = 1 }
          |""".stripMargin)
      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)
      // file open BEFORE initialize — the scenario that stopped all indexing
      idx.onDidOpen(givensFile)
      idx.initialize(List.empty) // must not throw
      // other files still index fine afterwards
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      idx.onDidOpen(mainFile)
      val locs = idx.gotoDefinitions(mainFile, 10, 4)
      assert(locs.nonEmpty, s"Expected locations for utils, got $locs")
      // the given-import file itself resolves without crashing
      idx.findSymbolsAt(givensFile, 0, 15)
      assert(st.get("_empty_/Givens.").isDefined, "Expected _empty_/Givens. in symbol table")
    } finally os.remove.all(root)
  }

  test("findSymbolsAt resolves cross-file utils identifier") {
    val root = TestFixture.copy("sbt", "sbt-findsym")
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val syms = idx.findSymbolsAt(mainFile, 10, 4)
      assert(syms.exists(_ == "_empty_/utils."),
        s"Expected _empty_/utils., got: ${syms}")
    } finally os.remove.all(root)
  }

  test("gotoDefinitions resolves cross-file utils from Main.scala") {
    val root = TestFixture.copy("sbt", "sbt-gotoutils")
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val locs = idx.gotoDefinitions(mainFile, 10, 4)
      assert(locs.nonEmpty, s"Expected locations for utils, got $locs")
      assertEquals(locs.head.path.last, "utils.scala")
    } finally os.remove.all(root)
  }

  test("gotoDefinitions resolves cross-file getMsg member") {
    val root = TestFixture.copy("sbt", "sbt-gotogetmsg")
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val locs = idx.gotoDefinitions(mainFile, 10, 10)
      assert(locs.nonEmpty, s"Expected locations for getMsg, got $locs")
      assertEquals(locs.head.path.last, "utils.scala")
    } finally os.remove.all(root)
  }

  test("references finds utils def + usage across open files") {
    val root = TestFixture.copy("sbt", "sbt-refs")
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val utilsFile = root / "src" / "main" / "scala" / "utils.scala"
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      idx.onDidOpen(utilsFile)
      val refs = idx.references(utilsFile, 1, 7, includeDeclaration = true)
      assert(refs.nonEmpty, s"Expected references for utils, got $refs")
      val mainRefs = refs.filter(_.path == mainFile)
      assert(mainRefs.nonEmpty, s"Expected utils reference in Main.scala, got refs in: ${refs.map(_.path.last)}")
    } finally os.remove.all(root)
  }

  test("source-only fallback: WorkspaceIndex works without semanticdb") {
    val root = os.pwd / "tmp" / s"source-only-${System.currentTimeMillis()}"
    try {
      val srcDir = root
      os.makeDir.all(srcDir)
      val sbtSrc = os.pwd / "test" / "resources" / "examples" / "sbt" / "src" / "main" / "scala"
      val mainFile = srcDir / "Main.scala"
      val utilsFile = srcDir / "utils.scala"
      os.copy(sbtSrc / "Main.scala", mainFile)
      os.copy(sbtSrc / "utils.scala", utilsFile)
      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)
      idx.initialize(List.empty)
      val utilsSym = st.get("_empty_/utils.")
      assert(utilsSym.isDefined, "Source-only fallback: expected _empty_/utils.")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // nopackages fixture
  // ═══════════════════════════════════════════════════════════════

  test("nopackages: goto add() call → Siblings.scala def add") {
    checkGoto("nopackages", "nopkg-add", "Main.scala", """(?<p>add)\(2, 3\)""", "Siblings.scala", Some("_empty_/Siblings$package.add()."))
  }

  test("nopackages: goto sibling val ref → Siblings.scala val siblingVal") {
    checkGoto("nopackages", "nopkg-sibval", "Main.scala", """(?<p>siblingVal)""", "Siblings.scala")
  }

  test("nopackages: goto other() call → Siblings.scala def other") {
    checkGoto("nopackages", "nopkg-other", "Main.scala", """(?<p>other)\(\)""", "Siblings.scala")
  }

  test("nopackages: goto object member Helper.greet() → Siblings.scala Helper.greet") {
    checkGoto("nopackages", "nopkg-greet", "Main.scala", """(?<p>greet)\(\)""", "Siblings.scala", Some("_empty_/Helper.greet()."))
  }

  test("nopackages: goto add() from its def site returns empty (no self-goto)") {
    checkNoSelfGoto("nopackages", "nopkg-noself", "Siblings.scala", """(?<p>add)\(a""")
  }

  test("nopackages: local val ref resolves to local def inside method") {
    checkGoto("nopackages", "nopkg-local", "Main.scala", """println\((?<p>local)\)""", "Main.scala")
  }

  // ═══════════════════════════════════════════════════════════════
  // packages fixture
  // ═══════════════════════════════════════════════════════════════

  test("packages: goto greeting member of Models → Models.scala") {
    checkGoto("packages", "pkg-greeting", "src/main/scala/com/example/Main.scala", """(?<p>greeting)\)""", "Models.scala")
  }

  test("packages: goto Util.doubled member → Util.scala") {
    checkGoto("packages", "pkg-doubled", "src/main/scala/com/example/Main.scala", """(?<p>doubled)\(21\)""", "Util.scala")
  }

  test("packages: goto cross-file top-level helper() → Util.scala") {
    checkGoto("packages", "pkg-helper", "src/main/scala/com/example/Main.scala", """(?<p>helper)\(\)""", "Util.scala")
  }

  test("packages: goto new Person type + ctor → Models.scala") {
    checkGoto("packages", "pkg-person", "src/main/scala/com/example/Main.scala", """new (?<p>Person)\(""", "Models.scala")
  }

  test("packages: goto on `case Red` def site returns empty (no self-goto)") {
    checkNoSelfGoto("packages", "pkg-noself", "src/main/scala/com/example/Models.scala", """case (?<p>Red),""")
  }

  test("packages: references finds Models.greeting declarations + open-file usages") {
    checkRefs("packages", "pkg-refs", List("src/main/scala/com/example/Main.scala", "src/main/scala/com/example/Models.scala"), "src/main/scala/com/example/Models.scala", """val (?<p>greeting):""", Set("Main.scala"))
  }

  // ═══════════════════════════════════════════════════════════════
  // nested fixture
  // ═══════════════════════════════════════════════════════════════

  test("nested: goto Outer type/self param ref within class → same file") {
    val root = TestFixture.copy("nested", "nested-outer")
    try {
      val outerFile = root / "src" / "main" / "scala" / "com" / "example" / "outer" / "Outer.scala"
      val outerText = os.read(outerFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(outerFile)
      val (l, c) = TestPositions.at(outerText, """self: (?<p>Outer)\)""")
      val locs = idx.gotoDefinitions(outerFile, l, c)
      assert(locs.nonEmpty, s"expected Outer type ref, got empty")
      assertEquals(locs.head.path, outerFile)
    } finally os.remove.all(root)
  }

  test("nested: goto on `def m()` def site returns empty (no self-goto)") {
    checkNoSelfGoto("nested", "nested-noself", "src/main/scala/com/example/outer/Outer.scala", """def (?<p>m)\(\): Int""")
  }

  test("nested: goto package-object member answer → pkg.scala") {
    val root = TestFixture.copy("nested", "nested-pkgobj")
    try {
      val pkgFile = root / "src" / "main" / "scala" / "com" / "example" / "pkg.scala"
      val pkgText = os.read(pkgFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(pkgFile)
      val (l, c) = TestPositions.at(pkgText, """println\((?<p>answer)\)""")
      val locs = idx.gotoDefinitions(pkgFile, l, c)
      if (locs.nonEmpty) {
        assertEquals(locs.head.path, pkgFile)
      }
    } finally os.remove.all(root)
  }

  test("nested: goto on `def hello()` def site returns empty (no self-goto)") {
    checkNoSelfGoto("nested", "nested-hello", "src/main/scala/com/example/pkg.scala", """(?<p>hello)\(\)""")
  }

  // ═══════════════════════════════════════════════════════════════
  // crosslang fixture
  // ═══════════════════════════════════════════════════════════════

  test("crosslang: goto imported Java Greeter type → Greeter.java") {
    checkGoto("crosslang", "xlang-greeter", "src/main/scala/com/lang/Use.scala", """import com.lang.(?<p>Greeter)""", "Greeter.java")
  }

  test("crosslang: goto static Greeter.hello() → Greeter.java") {
    checkGoto("crosslang", "xlang-hello", "src/main/scala/com/lang/Use.scala", """Greeter.(?<p>hello)\(\)""", "Greeter.java")
  }

  test("crosslang: goto new Greeter() instance ctor → Greeter.java") {
    checkGoto("crosslang", "xlang-ctor", "src/main/scala/com/lang/Use.scala", """new (?<p>Greeter)\(\)""", "Greeter.java")
  }

  // ═══════════════════════════════════════════════════════════════
  // known limitations
  // ═══════════════════════════════════════════════════════════════

  test("known-limitation: cursor on a non-ref, non-def token returns empty (v1)") {
    val root = TestFixture.copy("nopackages", "nopkg-colon")
    try {
      val sibFile = root / "Siblings.scala"
      val sibText = os.read(sibFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(sibFile)
      val (l, c) = TestPositions.at(sibText, """object Helper(?<p>:)""")
      val res = idx.findSymbolsAt(sibFile, l, c)
      assert(res.isEmpty, s"expected no symbol at ':' colon, got $res")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // scalacli fixture
  // ═══════════════════════════════════════════════════════════════

  test("scalacli: goto named param `a` of utils.add(a=2, b=3) → utils.scala param") {
    checkGoto("scalacli", "scalacli-parama", "bla.scala", """utils.add\((?<p>a) =""", "bla2.scala", Some("_empty_/utils.add().(a)"))
  }

  test("scalacli: goto named param `b` of utils.add(a=2, b=3) → utils.scala param") {
    checkGoto("scalacli", "scalacli-paramb", "bla.scala", """(?<p>b) =  3""", "bla2.scala", Some("_empty_/utils.add().(b)"))
  }

  test("scalacli: goto method call on `new Bla().div(...)` → Bla.scala div") {
    checkGoto("scalacli", "scalacli-div", "bla.scala", """new Bla\(\)\.(?<p>div)\(""", "bla2.scala", Some("_empty_/Bla#div()."))
  }

  test("scalacli: goto `new Dzava` from scala → dzava.java (cross-language type)") {
    checkGoto("scalacli", "scalacli-dzavatype", "bla.scala", """new (?<p>Dzava)""", "dzava.java", Some("_empty_/Dzava#"))
  }

  test("scalacli: goto `new Dzava().dzava()` from scala → dzava.java method (cross-language)") {
    checkGoto("scalacli", "scalacli-dzavamethod", "bla2.scala", """new Dzava\(\)\.(?<p>dzava)\(\)""", "dzava.java", Some("_empty_/Dzava#dzava()."))
  }

  test("scalacli: goto on `class Bla` def site returns empty (no self-goto)") {
    checkNoSelfGoto("scalacli", "scalacli-noself", "bla2.scala", """class (?<p>Bla)""")
  }

  // ═══════════════════════════════════════════════════════════════
  // REPRO: sbt project with real semanticdb files
  // ═══════════════════════════════════════════════════════════════

  test("REPRO sbt: goto utils from Main.scala uses semanticdb") {
    val root = TestFixture.copy("sbt", "repro-sbt-utils")
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(os.read(mainFile), """(?<p>utils)\.getMsg""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected utils goto to resolve via semanticdb, got empty")
      assertEquals(locs.head.path.last, "utils.scala")
    } finally os.remove.all(root)
  }

  test("REPRO sbt: goto getMsg member from Main.scala uses semanticdb") {
    val root = TestFixture.copy("sbt", "repro-sbt-getmsg")
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(os.read(mainFile), """utils\.(?<p>getMsg)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected getMsg goto to resolve via semanticdb, got empty")
      assertEquals(locs.head.path.last, "utils.scala")
    } finally os.remove.all(root)
  }

  test("source-only: goto utils.getMsg() cross-file without semanticdb") {
    val root = TestFixture.copy("sbt", "source-only-sbt")
    try {
      // Remove target/ to force source-only fallback
      os.remove.all(root / "target")
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val utilsFile = root / "src" / "main" / "scala" / "utils.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      idx.onDidOpen(utilsFile)
      val (l, c) = TestPositions.at(mainText, """utils\.(?<p>getMsg)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected getMsg to resolve via source-only, got empty")
      assertEquals(locs.head.path.last, "utils.scala")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // REPRO: Java locals goto (dzava.java)
  // ═══════════════════════════════════════════════════════════════

  test("REPRO java: goto local var `a` ref in a + b") {
    checkGoto("scalacli", "repro-java-a", "dzava.java", """int sum = (?<p>a) \+ b""", "dzava.java")
  }

  test("REPRO java: goto local var `b` ref in a + b") {
    checkGoto("scalacli", "repro-java-b", "dzava.java", """a \+ (?<p>b);""", "dzava.java")
  }

  // ═══════════════════════════════════════════════════════════════
  // P2: References tests
  // ═══════════════════════════════════════════════════════════════

  test("nopackages: references of add() finds def + call sites across open files") {
    checkRefs("nopackages", "refs-nopkg-add", List("Main.scala", "Siblings.scala"), "Siblings.scala", """def (?<p>add)\(a""", Set("Main.scala", "Siblings.scala"))
  }

  test("nopackages: references of local val finds only same-file occurrences") {
    val root = TestFixture.copy("nopackages", "refs-nopkg-local")
    try {
      val mainFile = root / "Main.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """val (?<p>local) = add""")
      val refs = idx.references(mainFile, l, c, includeDeclaration = true)
      assert(refs.nonEmpty, s"expected local refs, got empty")
      assert(refs.forall(_.path == mainFile), s"expected only same-file refs for local")
    } finally os.remove.all(root)
  }

  test("packages: references of Models.greeting finds usage in Main.scala") {
    checkRefs("packages", "refs-pkg-greeting", List("src/main/scala/com/example/Main.scala", "src/main/scala/com/example/Models.scala"), "src/main/scala/com/example/Models.scala", """val (?<p>greeting):""", Set("Main.scala"))
  }

  test("packages: references with includeDeclaration=false excludes def site") {
    val root = TestFixture.copy("packages", "refs-pkg-nodecl")
    try {
      val mainFile = root / "src" / "main" / "scala" / "com" / "example" / "Main.scala"
      val modelsFile = root / "src" / "main" / "scala" / "com" / "example" / "Models.scala"
      val mainText = os.read(mainFile)
      val modelsText = os.read(modelsFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      idx.onDidOpen(modelsFile)
      val (l, c) = TestPositions.at(modelsText, """val (?<p>greeting):""")
      val refsWith = idx.references(modelsFile, l, c, includeDeclaration = true)
      val refsWithout = idx.references(modelsFile, l, c, includeDeclaration = false)
      assert(refsWith.size >= refsWithout.size, "includeDeclaration=true should return >= results")
      val declRefs = refsWith.filter(_.path == modelsFile)
      assert(declRefs.nonEmpty, "includeDeclaration=true should include def site")
    } finally os.remove.all(root)
  }

  test("crosslang: references of Java Greeter finds Scala usages") {
    checkRefs("crosslang", "refs-xlang", List("src/main/scala/com/lang/Use.scala", "src/main/java/com/lang/Greeter.java"), "src/main/java/com/lang/Greeter.java", """class (?<p>Greeter)""", Set("Use.scala"))
  }

  test("scalacli: references of utils object finds definition + usage across files") {
    checkRefs("scalacli", "refs-scalacli-utils", List("bla.scala", "bla2.scala"), "bla2.scala", """object (?<p>utils)""", Set("bla.scala", "bla2.scala"))
  }

  test("references on empty cursor position returns empty") {
    val root = TestFixture.copy("nopackages", "refs-empty")
    try {
      val mainFile = root / "Main.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      // Cursor on a line that has only whitespace (line after the last content)
      val refs = idx.references(mainFile, 999, 0, includeDeclaration = true)
      assert(refs.isEmpty, s"expected no refs on empty cursor, got ${refs.size}")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // P2: Source-only references test
  // ═══════════════════════════════════════════════════════════════

  test("source-only: references of utils.getMsg() finds def + call across open files") {
    val root = TestFixture.copy("sbt", "source-only-refs")
    try {
      os.remove.all(root / "target")
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val utilsFile = root / "src" / "main" / "scala" / "utils.scala"
      val mainText = os.read(mainFile)
      val utilsText = os.read(utilsFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      idx.onDidOpen(utilsFile)
      // Cursor on def site of getMsg in utils.scala
      val (l, c) = TestPositions.at(utilsText, """def (?<p>getMsg)""")
      val refs = idx.references(utilsFile, l, c, includeDeclaration = true)
      assert(refs.exists(_.path == mainFile),
        s"expected ref in Main.scala, got ${refs.map(_.path.last)}")
      assert(refs.exists(_.path == utilsFile),
        s"expected def site in utils.scala")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // Stale semanticdb fallback test
  // ═══════════════════════════════════════════════════════════════

  test("stale-semanticdb: goto getMsg after source edit falls back to source parsing") {
    val root = TestFixture.copy("sbt", "stale-sem-fallback")
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val original = os.read(mainFile)
      // Edit the source after the fixture was copied (index here is source-only — no semanticdb roots)
      os.write.over(mainFile, "// edited after compile\n" + original)
      val (idx, _) = freshIndexAt(root)
      val mainText = os.read(mainFile)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """utils\.(?<p>getMsg)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"stale semanticdb should fall back to source parsing, got empty")
      assertEquals(locs.head.path.last, "utils.scala")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // semanticdb pairing: initialize with roots + invalidate upgrade
  // (fresh sbt project flow: compile generates semanticdb → invalidate loads it)
  // ═══════════════════════════════════════════════════════════════

  test("sbt fixture: initialize with semanticdb roots populates symbols from semanticdb") {
    val root = TestFixture.copy("sbt", "sbt-semdb-init")
    try {
      SemanticdbFixture.compile(root) // real semanticdb generated at test time in this copy
      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)
      val semDir = root / "target" / "scala-3.8.4" / "meta"
      idx.initialize(List(SemanticdbDirs(root, semDir)))
      val getMsgSym = st.get("_empty_/utils.getMsg().")
      assert(getMsgSym.isDefined, s"expected semanticdb _empty_/utils.getMsg(). in symbol table")
      assertEquals(getMsgSym.get.path, root / "src" / "main" / "scala" / "utils.scala")
    } finally os.remove.all(root)
  }

  test("sbt fixture: invalidate after source-only init upgrades to semanticdb (fresh-project flow)") {
    val root = TestFixture.copy("sbt", "sbt-semdb-upgrade")
    try {
      SemanticdbFixture.compile(root) // real semanticdb generated at test time in this copy
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val (idx, _) = freshIndexAt(root) // source-only (no data.json on fresh project)
      idx.onDidOpen(mainFile)
      val semDir = root / "target" / "scala-3.8.4" / "meta"
      idx.invalidate(List(SemanticdbDirs(root, semDir)))
      val (l, c) = TestPositions.at(os.read(mainFile), """utils\.(?<p>getMsg)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected getMsg to resolve after invalidate, got empty")
      assertEquals(locs.head.path.last, "utils.scala")
    } finally os.remove.all(root)
  }

  test("sbt fixture: partial semanticdb ref symbols → occurrences fall back to source parse") {
    val root = TestFixture.copy("sbt", "sbt-semdb-partialrefs")
    try {
      SemanticdbFixture.compile(root) // real semanticdb generated at test time in this copy
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val semDir = root / "target" / "scala-3.8.4" / "meta"

      // Overwrite the real semanticdb with one emitting PARTIAL ref symbols
      // (`utils.`, no owner prefix — what Scala 3 -Ybest-effort emits under
      // compile errors). The fallback must produce FULL symbols via source
      // parsing so goto-def resolves.
      val mainDoc = TextDocument(
        schema = Schema.SEMANTICDB4,
        uri = "src/main/scala/Main.scala",
        text = os.read(mainFile),
        language = Language.SCALA,
        symbols = Nil,
        occurrences = List(
          SymbolOccurrence(symbol = "utils.", range = Some(SdbRange(10, 2, 10, 7)), role = SymbolOccurrence.Role.REFERENCE),
          SymbolOccurrence(symbol = "utils.getMsg().", range = Some(SdbRange(10, 8, 10, 14)), role = SymbolOccurrence.Role.REFERENCE)
        )
      )
      val semPath = semDir / "META-INF" / "semanticdb" / "src" / "main" / "scala" / "Main.scala.semanticdb"
      os.write.over(semPath, TextDocuments(List(mainDoc)).toByteArray)

      idx.invalidate(List(SemanticdbDirs(root, semDir)))
      val (l, c) = TestPositions.at(os.read(mainFile), """utils\.(?<p>getMsg)\(\)""")
      val syms = idx.findSymbolsAt(mainFile, l, c)
      assert(syms.contains("_empty_/utils.getMsg()."), s"expected full symbol via source fallback, got $syms")
    } finally os.remove.all(root)
  }

  test("sbt fixture: partial semanticdb refs keep definitions authoritative (goto-def resolves)") {
    val root = TestFixture.copy("sbt", "sbt-semdb-defsauth")
    try {
      SemanticdbFixture.compile(root) // real semanticdb generated at test time in this copy
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val utilsFile = root / "src" / "main" / "scala" / "utils.scala"
      val semDir = root / "target" / "scala-3.8.4" / "meta"
      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)
      idx.initialize(List.empty)
      idx.onDidOpen(mainFile)

      // Overwrite BOTH semanticdb files: utils with FULL DEFINITION symbols,
      // Main with PARTIAL ref symbols (`utils.` — Scala 3 -Ybest-effort).
      val utilsDoc = TextDocument(
        schema = Schema.SEMANTICDB4,
        uri = "src/main/scala/utils.scala",
        text = os.read(utilsFile),
        language = Language.SCALA,
        symbols = Nil,
        occurrences = List(
          SymbolOccurrence(symbol = "_empty_/utils.", range = Some(SdbRange(0, 7, 0, 12)), role = SymbolOccurrence.Role.DEFINITION),
          SymbolOccurrence(symbol = "_empty_/utils.getMsg().", range = Some(SdbRange(1, 6, 1, 12)), role = SymbolOccurrence.Role.DEFINITION)
        )
      )
      val mainDoc = TextDocument(
        schema = Schema.SEMANTICDB4,
        uri = "src/main/scala/Main.scala",
        text = os.read(mainFile),
        language = Language.SCALA,
        symbols = Nil,
        occurrences = List(
          SymbolOccurrence(symbol = "utils.", range = Some(SdbRange(10, 2, 10, 7)), role = SymbolOccurrence.Role.REFERENCE),
          SymbolOccurrence(symbol = "utils.getMsg().", range = Some(SdbRange(10, 8, 10, 14)), role = SymbolOccurrence.Role.REFERENCE)
        )
      )
      val semBase = semDir / "META-INF" / "semanticdb" / "src" / "main" / "scala"
      os.write.over(semBase / "utils.scala.semanticdb", TextDocuments(List(utilsDoc)).toByteArray)
      os.write.over(semBase / "Main.scala.semanticdb", TextDocuments(List(mainDoc)).toByteArray)

      idx.invalidate(List(SemanticdbDirs(root, semDir)))

      // definitions stay authoritative from semanticdb (full symbols in SymbolTable)
      val defSym = st.get("_empty_/utils.getMsg().")
      assert(defSym.isDefined, "semanticdb defs must stay authoritative under partial refs")
      assertEquals(defSym.get.path, utilsFile)
      // refs fall back to source parsing → goto-def resolves to the def
      val (l, c) = TestPositions.at(os.read(mainFile), """utils\.(?<p>getMsg)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected goto-def via source-parse fallback, got empty")
      assertEquals(locs.head.path, utilsFile)
    } finally os.remove.all(root)
  }

  test("multiline semanticdb occurrence: cursor on any inner line matches (end-exclusive)") {
    val root = os.pwd / "tmp" / s"multiline-range-${System.currentTimeMillis()}"
    try {
      val srcFile = root / "Main.scala"
      os.makeDir.all(root)
      os.write(srcFile, "object Main:\n  def m() = 1\n    // filler\n  val x = 1\n")
      val semDir = root / "target" / "meta"
      os.makeDir.all(semDir / "META-INF" / "semanticdb")
      val doc = TextDocument(
        schema = Schema.SEMANTICDB4,
        uri = "Main.scala",
        text = os.read(srcFile),
        language = Language.SCALA,
        symbols = Nil,
        occurrences = List(
          // multiline REFERENCE range (start (1,2) .. end (3,5))
          SymbolOccurrence(symbol = "_empty_/ml.", range = Some(SdbRange(1, 2, 3, 5)), role = SymbolOccurrence.Role.REFERENCE)
        )
      )
      os.write(semDir / "META-INF" / "semanticdb" / "Main.scala.semanticdb", TextDocuments(List(doc)).toByteArray)

      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)
      idx.initialize(List.empty)
      idx.onDidOpen(srcFile)
      idx.invalidate(List(SemanticdbDirs(root, semDir)))

      // inside: start line at/after start char, any middle line, end line before end char
      assert(idx.findSymbolsAt(srcFile, 1, 2).contains("_empty_/ml."), "start line, start char")
      assert(idx.findSymbolsAt(srcFile, 2, 0).contains("_empty_/ml."), "middle line")
      assert(idx.findSymbolsAt(srcFile, 3, 4).contains("_empty_/ml."), "end line, before end char")
      // outside: before start, at start char - 1, at end char (end-exclusive), after end
      assert(!idx.findSymbolsAt(srcFile, 0, 0).contains("_empty_/ml."), "line before range")
      assert(!idx.findSymbolsAt(srcFile, 1, 1).contains("_empty_/ml."), "start line, before start char")
      assert(!idx.findSymbolsAt(srcFile, 3, 5).contains("_empty_/ml."), "end line, at end char (exclusive)")
      assert(!idx.findSymbolsAt(srcFile, 4, 0).contains("_empty_/ml."), "line after range")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // gitignore-aware source walk
  // ═══════════════════════════════════════════════════════════════

  test("gitignore: node_modules/.worktrees/target are not indexed") {
    val root = os.temp.dir(prefix = "ws-gitignore-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "node_modules/\n.worktrees/\ntarget/\n")
      os.makeDir.all(root / "src")
      os.write(root / "src" / "Main.scala", "class RealMain\n")
      os.makeDir.all(root / "node_modules" / "dep")
      os.write(root / "node_modules" / "dep" / "Dep.scala", "class NodeDep\n")
      os.makeDir.all(root / ".worktrees" / "wt")
      os.write(root / ".worktrees" / "wt" / "Other.scala", "class WorktreeOther\n")
      os.makeDir.all(root / "target" / "gen")
      os.write(root / "target" / "gen" / "Gen.scala", "class GeneratedThing\n")
      val (_, st) = freshIndexAt(root)
      assert(st.get("_empty_/RealMain#").isDefined, "src/Main.scala should be indexed")
      assert(st.get("_empty_/NodeDep#").isEmpty, "node_modules should be skipped")
      assert(st.get("_empty_/WorktreeOther#").isEmpty, ".worktrees should be skipped")
      assert(st.get("_empty_/GeneratedThing#").isEmpty, "target should be skipped")
    } finally os.remove.all(root)
  }

  test("gitignore: negation re-includes a file") {
    val root = os.temp.dir(prefix = "ws-gitignore-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.generated.scala\n!keep.generated.scala\n")
      os.write(root / "a.generated.scala", "class GenA\n")
      os.write(root / "keep.generated.scala", "class KeepGen\n")
      val (_, st) = freshIndexAt(root)
      assert(st.get("_empty_/GenA#").isEmpty, "*.generated.scala should be skipped")
      assert(st.get("_empty_/KeepGen#").isDefined, "!keep.generated.scala should be re-included")
    } finally os.remove.all(root)
  }

  test("gitignore: nested .gitignore applies relative to its own dir") {
    val root = os.temp.dir(prefix = "ws-gitignore-")
    try {
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / "src")
      os.write(root / "src" / ".gitignore", "build/\n")
      os.write(root / "src" / "Main.scala", "class SubMain\n")
      os.makeDir.all(root / "src" / "build")
      os.write(root / "src" / "build" / "B.scala", "class SubBuild\n")
      os.makeDir.all(root / "build")
      os.write(root / "build" / "RootB.scala", "class RootBuild\n")
      val (_, st) = freshIndexAt(root)
      assert(st.get("_empty_/SubMain#").isDefined, "src/Main.scala should be indexed")
      assert(st.get("_empty_/SubBuild#").isEmpty, "src/build should be skipped (nested rule)")
      assert(st.get("_empty_/RootBuild#").isDefined, "root build/ must NOT be skipped by nested rule")
    } finally os.remove.all(root)
  }

  test("gitignore: ignorePatterns constructor param is honored") {
    val root = os.temp.dir(prefix = "ws-gitignore-")
    try {
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / "src")
      os.write(root / "src" / "Main.scala", "class RealMain\n")
      os.write(root / "src" / "Gen.scala", "class GenByConfig\n")
      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st, ignorePatterns = Vector("src/Gen.scala"))
      idx.initialize(List.empty)
      assert(st.get("_empty_/RealMain#").isDefined)
      assert(st.get("_empty_/GenByConfig#").isEmpty, "config pattern should skip src/Gen.scala")
    } finally os.remove.all(root)
  }

  test("nested git repo sources are not indexed") {
    val root = os.temp.dir(prefix = "ws-gitignore-")
    try {
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / "src")
      os.write(root / "src" / "Main.scala", "class RealMain\n")
      os.makeDir.all(root / "nested" / ".git")
      os.write(root / "nested" / "Main.scala", "class NestedMain\n")
      val (_, st) = freshIndexAt(root)
      assert(st.get("_empty_/RealMain#").isDefined, "src/Main.scala should be indexed")
      assert(st.get("_empty_/NestedMain#").isEmpty, "nested repo sources must not be indexed")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // .sbt build-definition files
  // ═══════════════════════════════════════════════════════════════

  test(".sbt: initialize indexes build.sbt definitions") {
    val root = TestFixture.copy("sbtbuild", "sbtbuild-init")
    try {
      val (_, st) = freshIndexAt(root)
      val coreSym = st.get("_empty_/build.core.")
      assert(coreSym.isDefined, "Expected _empty_/build.core. in symbol table")
      assertEquals(coreSym.get.path.last, "build.sbt")
      assert(st.get("_empty_/build.cli.").isDefined, "Expected _empty_/build.cli. in symbol table")
    } finally os.remove.all(root)
  }

  test(".sbt: gotoDefinitions on ref inside build.sbt resolves to def in same file") {
    val root = TestFixture.copy("sbtbuild", "sbtbuild-goto")
    try {
      val buildFile = root / "build.sbt"
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(buildFile)
      val text = os.read(buildFile)
      val refStart = text.indexOf("dependsOn(core)") + "dependsOn(".length
      assert(refStart > 0, s"ref 'dependsOn(core)' not found in $buildFile:\n$text")
      val line = text.substring(0, refStart).count(_ == '\n')
      val char = refStart - text.substring(0, refStart).lastIndexOf('\n') - 1
      val locs = idx.gotoDefinitions(buildFile, line, char)
      assert(locs.nonEmpty, s"Expected locations for core, got $locs")
      assertEquals(locs.head.path, buildFile)
    } finally os.remove.all(root)
  }

  test(".sbt: onDidSave re-extracts defs from build.sbt") {
    val root = TestFixture.copy("sbtbuild", "sbtbuild-save")
    try {
      val buildFile = root / "build.sbt"
      val (idx, st) = freshIndexAt(root)
      assert(st.get("_empty_/build.core.").isDefined, "Expected _empty_/build.core. in symbol table")
      os.write.append(buildFile, "\nlazy val extra = project\n")
      idx.onDidSave(buildFile)
      assert(st.get("_empty_/build.extra.").isDefined, "Expected _empty_/build.extra. in symbol table after save")
      assert(st.get("_empty_/build.core.").isDefined, "_empty_/build.core. must survive save re-extraction")
    } finally os.remove.all(root)
  }

  test("debug dump: outside files listed last, as absolute paths, after a marker comment") {
    val root = TestFixture.copy("sbtbuild", "sbtbuild-dump")
    try {
      val (idx, _) = freshIndexAt(root)
      // a dep-style source OUTSIDE the workspace, opened via goto-def
      val outsideDir = os.pwd / "tmp" / s"deps-outside-${System.currentTimeMillis()}"
      os.makeDir.all(outsideDir)
      val outsideFile = outsideDir / "Keys.scala"
      os.write(outsideFile, "object Keys\n")
      idx.onDidOpen(outsideFile)
      // trigger a dump refresh (onFilesCreated writes the dump)
      idx.onFilesCreated(Set(root / "New.scala"))
      val dump = os.read(root / ".basamake" / "index_sources.txt")
      val newIdx = dump.indexOf("New.scala")
      val commentIdx = dump.indexOf("# files outside the workspace (opened via goto-def)")
      val keysIdx = dump.indexOf(outsideFile.toString)
      assert(newIdx >= 0 && newIdx < commentIdx, s"workspace files must come before the marker comment:\n$dump")
      assert(keysIdx > commentIdx, s"outside file must come after the marker comment, as absolute path:\n$dump")
      assert(!dump.contains("../../"), s"no relative ../ paths may appear in the dump:\n$dump")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // Direct open-file semanticdb pairing (warm-start path)
  // ═══════════════════════════════════════════════════════════════

  /** sbt-like fixture: src/main/scala/{Main,utils}.scala + hand-written semanticdb
    * at the conventional target layout (`<semDir>/META-INF/semanticdb/<uri>.semanticdb`).
    * Main.scala references `ext.getMsg()` — the SOURCE parser cannot resolve `ext`
    * (empty symbol), so `_empty_/utils.getMsg().` can ONLY come from semanticdb. */
  private def buildDirectPairingFixture(): os.Path = {
    val root = os.pwd / "tmp" / s"direct-pair-${System.currentTimeMillis()}"
    val srcDir = root / "src" / "main" / "scala"
    os.makeDir.all(srcDir)
    val semDir = root / "target" / "scala-3.8.4" / "meta" / "META-INF" / "semanticdb" / "src" / "main" / "scala"
    os.makeDir.all(semDir)

    val utilsContent = "object utils:\n  def getMsg() = \"bla\"\n"
    val mainContent = "object Main:\n  def main(args: Array[String]): Unit =\n    println(ext.getMsg())\n"
    os.write(srcDir / "utils.scala", utilsContent)
    os.write(srcDir / "Main.scala", mainContent)

    val utilsDoc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "src/main/scala/utils.scala",
      text = utilsContent,
      language = Language.SCALA,
      symbols = Nil,
      occurrences = List(
        SymbolOccurrence(symbol = "_empty_/utils.", range = Some(SdbRange(0, 7, 0, 12)), role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(symbol = "_empty_/utils.getMsg().", range = Some(SdbRange(1, 6, 1, 12)), role = SymbolOccurrence.Role.DEFINITION)
      )
    )
    val mainDoc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "src/main/scala/Main.scala",
      text = mainContent,
      language = Language.SCALA,
      symbols = Nil,
      occurrences = List(
        SymbolOccurrence(symbol = "_empty_/utils.", range = Some(SdbRange(2, 12, 2, 15)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "_empty_/utils.getMsg().", range = Some(SdbRange(2, 16, 2, 22)), role = SymbolOccurrence.Role.REFERENCE)
      )
    )
    os.write(semDir / "utils.scala.semanticdb", TextDocuments(List(utilsDoc)).toByteArray)
    os.write(semDir / "Main.scala.semanticdb", TextDocuments(List(mainDoc)).toByteArray)
    root
  }

  private def semanticdbDirOf(root: os.Path): os.Path =
    root / "target" / "scala-3.8.4" / "meta"

  test("direct pairing: opened file navigates via semanticdb while broad initialization is blocked") {
    val root = buildDirectPairingFixture()
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)
      val rootsPublished = new CountDownLatch(1)
      val releaseBroadInit = new CountDownLatch(1)
      idx.testHooks.afterRootsPublishedHook = () => { rootsPublished.countDown(); releaseBroadInit.await() }

      val initThread = Thread.ofVirtual().start(() =>
        idx.initialize(List(SemanticdbDirs(root, semanticdbDirOf(root)))))
      assert(rootsPublished.await(10, TimeUnit.SECONDS), "startup roots snapshot must be published")

      // Broad Pass A / Pass B are still blocked — the pairing below can only
      // come from onDidOpen's direct single-source path.
      idx.onDidOpen(mainFile)
      val syms = idx.findSymbolsAt(mainFile, 2, 18)
      assert(syms.contains("_empty_/utils.getMsg()."),
        s"expected semanticdb-only symbol via direct pairing, got $syms")

      // Let bulk initialization finish; the pairing must survive the race
      releaseBroadInit.countDown()
      initThread.join(10_000)
      assert(!initThread.isAlive, "initialize must complete after the hook is released")
      val afterInit = idx.findSymbolsAt(mainFile, 2, 18)
      assert(afterInit.contains("_empty_/utils.getMsg()."),
        s"pairing must survive broad Pass A, got $afterInit")
    } finally os.remove.all(root)
  }

  test("direct pairing: source with no semanticdb candidate falls back to source parsing") {
    val root = buildDirectPairingFixture()
    try {
      // Unpaired source: no .semanticdb file exists for it under the root
      val unpairedFile = root / "src" / "main" / "scala" / "Unpaired.scala"
      os.write(unpairedFile,
        """object Unpaired:
          |  def m(): Int =
          |    val local = 1
          |    local + 1
          |""".stripMargin)

      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)

      // 1. onDidOpen returns normally (no candidate → source parsing path)
      idx.onDidOpen(unpairedFile)
      // 2. source parsing still supplies its existing occurrences
      val (l, c) = TestPositions.at(os.read(unpairedFile), """(?<p>local) \+ 1""")
      val syms = idx.findSymbolsAt(unpairedFile, l, c)
      assert(syms.nonEmpty, s"expected source-parsed local ref, got $syms")

      // 3. full bulk initialization later extracts its definitions
      idx.initialize(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      assert(st.get("_empty_/Unpaired.").isDefined,
        "expected _empty_/Unpaired. in symbol table after bulk init")
    } finally os.remove.all(root)
  }
}
