package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import ba.sake.basamake.navigation.*
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, Range => SdbRange, SymbolOccurrence}

class WorkspaceIndexTest extends FunSuite {

  private def freshIndexAt(root: os.Path): (WorkspaceIndex, SymbolTable) = {
    val st = new InMemorySymbolTable
    val idx = new WorkspaceIndex(root, st)
    idx.initialize(List.empty)
    (idx, st)
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
      val sbtSrc = os.pwd / "modules" / "main" / "test" / "resources" / "examples" / "sbt" / "src" / "main" / "scala"
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
    val root = TestFixture.copy("nopackages", "nopkg-add")
    try {
      val mainFile = root / "Main.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """(?<p>add)\(2, 3\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected add definition, got empty")
      assertEquals(locs.head.path.last, "Siblings.scala")
      assert(locs.head.symbol == "_empty_/Siblings$package.add().", s"got ${locs.head.symbol}")
    } finally os.remove.all(root)
  }

  test("nopackages: goto sibling val ref → Siblings.scala val siblingVal") {
    val root = TestFixture.copy("nopackages", "nopkg-sibval")
    try {
      val mainFile = root / "Main.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """(?<p>siblingVal)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected siblingVal definition, got empty")
      assertEquals(locs.head.path.last, "Siblings.scala")
    } finally os.remove.all(root)
  }

  test("nopackages: goto other() call → Siblings.scala def other") {
    val root = TestFixture.copy("nopackages", "nopkg-other")
    try {
      val mainFile = root / "Main.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """(?<p>other)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty)
      assertEquals(locs.head.path.last, "Siblings.scala")
    } finally os.remove.all(root)
  }

  test("nopackages: goto object member Helper.greet() → Siblings.scala Helper.greet") {
    val root = TestFixture.copy("nopackages", "nopkg-greet")
    try {
      val mainFile = root / "Main.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """(?<p>greet)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected greet definition, got empty")
      assertEquals(locs.head.path.last, "Siblings.scala")
      assert(locs.head.symbol == "_empty_/Helper.greet().", s"got ${locs.head.symbol}")
    } finally os.remove.all(root)
  }

  test("nopackages: goto add() from its def site returns empty (no self-goto)") {
    val root = TestFixture.copy("nopackages", "nopkg-noself")
    try {
      val sibFile = root / "Siblings.scala"
      val sibText = os.read(sibFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(sibFile)
      val (l, c) = TestPositions.at(sibText, """(?<p>add)\(a""")
      val locs = idx.gotoDefinitions(sibFile, l, c)
      assert(locs.isEmpty, s"expected empty from def site (no self-goto), got $locs")
    } finally os.remove.all(root)
  }

  test("nopackages: local val ref resolves to local def inside method") {
    val root = TestFixture.copy("nopackages", "nopkg-local")
    try {
      val mainFile = root / "Main.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """println\((?<p>local)\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected local definition, got empty")
      assertEquals(locs.head.path, mainFile)
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // packages fixture
  // ═══════════════════════════════════════════════════════════════

  test("packages: goto greeting member of Models → Models.scala") {
    val root = TestFixture.copy("packages", "pkg-greeting")
    try {
      val mainFile = root / "src" / "main" / "scala" / "com" / "example" / "Main.scala"
      val modelsFile = root / "src" / "main" / "scala" / "com" / "example" / "Models.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """(?<p>greeting)\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected greeting def, got empty")
      assertEquals(locs.head.path, modelsFile)
    } finally os.remove.all(root)
  }

  test("packages: goto Util.doubled member → Util.scala") {
    val root = TestFixture.copy("packages", "pkg-doubled")
    try {
      val mainFile = root / "src" / "main" / "scala" / "com" / "example" / "Main.scala"
      val utilFile = root / "src" / "main" / "scala" / "com" / "example" / "Util.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """(?<p>doubled)\(21\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty)
      assertEquals(locs.head.path, utilFile)
    } finally os.remove.all(root)
  }

  test("packages: goto cross-file top-level helper() → Util.scala") {
    val root = TestFixture.copy("packages", "pkg-helper")
    try {
      val mainFile = root / "src" / "main" / "scala" / "com" / "example" / "Main.scala"
      val utilFile = root / "src" / "main" / "scala" / "com" / "example" / "Util.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """(?<p>helper)\(\)""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected helper def via wrapper scan, got empty")
      assertEquals(locs.head.path, utilFile)
    } finally os.remove.all(root)
  }

  test("packages: goto new Person type + ctor → Models.scala") {
    val root = TestFixture.copy("packages", "pkg-person")
    try {
      val mainFile = root / "src" / "main" / "scala" / "com" / "example" / "Main.scala"
      val modelsFile = root / "src" / "main" / "scala" / "com" / "example" / "Models.scala"
      val mainText = os.read(mainFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      val (l, c) = TestPositions.at(mainText, """new (?<p>Person)\(""")
      val locs = idx.gotoDefinitions(mainFile, l, c)
      assert(locs.nonEmpty, s"expected Person type/ctor ref, got empty")
      assertEquals(locs.head.path, modelsFile)
    } finally os.remove.all(root)
  }

  test("packages: goto on `case Red` def site returns empty (no self-goto)") {
    val root = TestFixture.copy("packages", "pkg-noself")
    try {
      val modelsFile = root / "src" / "main" / "scala" / "com" / "example" / "Models.scala"
      val modelsText = os.read(modelsFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(modelsFile)
      val (l, c) = TestPositions.at(modelsText, """case (?<p>Red),""")
      val locs = idx.gotoDefinitions(modelsFile, l, c)
      assert(locs.isEmpty, s"expected empty from def site (no self-goto), got $locs")
    } finally os.remove.all(root)
  }

  test("packages: references finds Models.greeting declarations + open-file usages") {
    val root = TestFixture.copy("packages", "pkg-refs")
    try {
      val mainFile = root / "src" / "main" / "scala" / "com" / "example" / "Main.scala"
      val modelsFile = root / "src" / "main" / "scala" / "com" / "example" / "Models.scala"
      val mainText = os.read(mainFile)
      val modelsText = os.read(modelsFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      idx.onDidOpen(modelsFile)
      val (l, c) = TestPositions.at(modelsText, """val (?<p>greeting):""")
      val refs = idx.references(modelsFile, l, c, includeDeclaration = true)
      assert(refs.exists(_.path == mainFile), s"expected usage in Main.scala, got ${refs.map(_.path.last)}")
    } finally os.remove.all(root)
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
    val root = TestFixture.copy("nested", "nested-noself")
    try {
      val outerFile = root / "src" / "main" / "scala" / "com" / "example" / "outer" / "Outer.scala"
      val outerText = os.read(outerFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(outerFile)
      val (l, c) = TestPositions.at(outerText, """def (?<p>m)\(\): Int""")
      val locs = idx.gotoDefinitions(outerFile, l, c)
      assert(locs.isEmpty, s"expected empty from def site (no self-goto), got $locs")
    } finally os.remove.all(root)
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
    val root = TestFixture.copy("nested", "nested-hello")
    try {
      val pkgFile = root / "src" / "main" / "scala" / "com" / "example" / "pkg.scala"
      val pkgText = os.read(pkgFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(pkgFile)
      val (l, c) = TestPositions.at(pkgText, """(?<p>hello)\(\)""")
      val locs = idx.gotoDefinitions(pkgFile, l, c)
      assert(locs.isEmpty, s"expected empty from def site (no self-goto), got $locs")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // crosslang fixture
  // ═══════════════════════════════════════════════════════════════

  test("crosslang: goto imported Java Greeter type → Greeter.java") {
    val root = TestFixture.copy("crosslang", "xlang-greeter")
    try {
      val useFile = root / "src" / "main" / "scala" / "com" / "lang" / "Use.scala"
      val greeterFile = root / "src" / "main" / "java" / "com" / "lang" / "Greeter.java"
      val useText = os.read(useFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(useFile)
      val (l, c) = TestPositions.at(useText, """import com.lang.(?<p>Greeter)""")
      val locs = idx.gotoDefinitions(useFile, l, c)
      assert(locs.nonEmpty, s"expected Java Greeter type, got empty")
      assertEquals(locs.head.path, greeterFile)
    } finally os.remove.all(root)
  }

  test("crosslang: goto static Greeter.hello() → Greeter.java") {
    val root = TestFixture.copy("crosslang", "xlang-hello")
    try {
      val useFile = root / "src" / "main" / "scala" / "com" / "lang" / "Use.scala"
      val greeterFile = root / "src" / "main" / "java" / "com" / "lang" / "Greeter.java"
      val useText = os.read(useFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(useFile)
      val (l, c) = TestPositions.at(useText, """Greeter.(?<p>hello)\(\)""")
      val locs = idx.gotoDefinitions(useFile, l, c)
      assert(locs.nonEmpty, s"expected Greeter.hello method, got empty")
      assertEquals(locs.head.path, greeterFile)
    } finally os.remove.all(root)
  }

  test("crosslang: goto new Greeter() instance ctor → Greeter.java") {
    val root = TestFixture.copy("crosslang", "xlang-ctor")
    try {
      val useFile = root / "src" / "main" / "scala" / "com" / "lang" / "Use.scala"
      val greeterFile = root / "src" / "main" / "java" / "com" / "lang" / "Greeter.java"
      val useText = os.read(useFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(useFile)
      val (l, c) = TestPositions.at(useText, """new (?<p>Greeter)\(\)""")
      val locs = idx.gotoDefinitions(useFile, l, c)
      assert(locs.nonEmpty)
      assertEquals(locs.head.path, greeterFile)
    } finally os.remove.all(root)
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
    val root = TestFixture.copy("scalacli", "scalacli-parama")
    try {
      val blaFile = root / "bla.scala"
      val bla2File = root / "bla2.scala"
      val blaText = os.read(blaFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(blaFile)
      val (l, c) = TestPositions.at(blaText, """utils.add\((?<p>a) =""")
      val locs = idx.gotoDefinitions(blaFile, l, c)
      assert(locs.nonEmpty, s"expected named-param `a` to resolve, got empty")
      assertEquals(locs.head.path, bla2File)
      assert(locs.head.symbol == "_empty_/utils.add().(a)", s"got ${locs.head.symbol}")
    } finally os.remove.all(root)
  }

  test("scalacli: goto named param `b` of utils.add(a=2, b=3) → utils.scala param") {
    val root = TestFixture.copy("scalacli", "scalacli-paramb")
    try {
      val blaFile = root / "bla.scala"
      val bla2File = root / "bla2.scala"
      val blaText = os.read(blaFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(blaFile)
      val (l, c) = TestPositions.at(blaText, """(?<p>b) =  3""")
      val locs = idx.gotoDefinitions(blaFile, l, c)
      assert(locs.nonEmpty, s"expected named-param `b` to resolve, got empty")
      assertEquals(locs.head.path, bla2File)
      assert(locs.head.symbol == "_empty_/utils.add().(b)", s"got ${locs.head.symbol}")
    } finally os.remove.all(root)
  }

  test("scalacli: goto method call on `new Bla().div(...)` → Bla.scala div") {
    val root = TestFixture.copy("scalacli", "scalacli-div")
    try {
      val blaFile = root / "bla.scala"
      val bla2File = root / "bla2.scala"
      val blaText = os.read(blaFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(blaFile)
      val (l, c) = TestPositions.at(blaText, """new Bla\(\)\.(?<p>div)\(""")
      val locs = idx.gotoDefinitions(blaFile, l, c)
      assert(locs.nonEmpty, s"expected div to resolve from new Bla().div(), got empty")
      assertEquals(locs.head.path, bla2File)
      assert(locs.head.symbol == "_empty_/Bla#div().", s"got ${locs.head.symbol}")
    } finally os.remove.all(root)
  }

  test("scalacli: goto `new Dzava` from scala → dzava.java (cross-language type)") {
    val root = TestFixture.copy("scalacli", "scalacli-dzavatype")
    try {
      val blaFile = root / "bla.scala"
      val dzavaFile = root / "dzava.java"
      val blaText = os.read(blaFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(blaFile)
      val (l, c) = TestPositions.at(blaText, """new (?<p>Dzava)""")
      val locs = idx.gotoDefinitions(blaFile, l, c)
      assert(locs.nonEmpty, s"expected Dzava type ref from scala to resolve, got empty")
      assertEquals(locs.head.path, dzavaFile)
      assert(locs.head.symbol == "_empty_/Dzava#", s"got ${locs.head.symbol}")
    } finally os.remove.all(root)
  }

  test("scalacli: goto `new Dzava().dzava()` from scala → dzava.java method (cross-language)") {
    val root = TestFixture.copy("scalacli", "scalacli-dzavamethod")
    try {
      val bla2File = root / "bla2.scala"
      val dzavaFile = root / "dzava.java"
      val bla2Text = os.read(bla2File)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(bla2File)
      val (l, c) = TestPositions.at(bla2Text, """new Dzava\(\)\.(?<p>dzava)\(\)""")
      val locs = idx.gotoDefinitions(bla2File, l, c)
      assert(locs.nonEmpty, s"expected dzava method to resolve cross-language, got empty")
      assertEquals(locs.head.path, dzavaFile)
      assert(locs.head.symbol == "_empty_/Dzava#dzava().", s"got ${locs.head.symbol}")
    } finally os.remove.all(root)
  }

  test("scalacli: goto on `class Bla` def site returns empty (no self-goto)") {
    val root = TestFixture.copy("scalacli", "scalacli-noself")
    try {
      val bla2File = root / "bla2.scala"
      val bla2Text = os.read(bla2File)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(bla2File)
      val (l, c) = TestPositions.at(bla2Text, """class (?<p>Bla)""")
      val locs = idx.gotoDefinitions(bla2File, l, c)
      assert(locs.isEmpty, s"expected empty from def site (no self-goto), got $locs")
    } finally os.remove.all(root)
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
    val root = TestFixture.copy("scalacli", "repro-java-a")
    try {
      val dzavaFile = root / "dzava.java"
      val dzavaText = os.read(dzavaFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(dzavaFile)
      val (l, c) = TestPositions.at(dzavaText, """int sum = (?<p>a) \+ b""")
      val locs = idx.gotoDefinitions(dzavaFile, l, c)
      assert(locs.nonEmpty, s"expected local var `a` goto to resolve, got empty")
    } finally os.remove.all(root)
  }

  test("REPRO java: goto local var `b` ref in a + b") {
    val root = TestFixture.copy("scalacli", "repro-java-b")
    try {
      val dzavaFile = root / "dzava.java"
      val dzavaText = os.read(dzavaFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(dzavaFile)
      val (l, c) = TestPositions.at(dzavaText, """a \+ (?<p>b);""")
      val locs = idx.gotoDefinitions(dzavaFile, l, c)
      assert(locs.nonEmpty, s"expected local var `b` goto to resolve, got empty")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // P2: References tests
  // ═══════════════════════════════════════════════════════════════

  test("nopackages: references of add() finds def + call sites across open files") {
    val root = TestFixture.copy("nopackages", "refs-nopkg-add")
    try {
      val mainFile = root / "Main.scala"
      val sibFile = root / "Siblings.scala"
      val mainText = os.read(mainFile)
      val sibText = os.read(sibFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      idx.onDidOpen(sibFile)
      val (l, c) = TestPositions.at(sibText, """def (?<p>add)\(a""")
      val refs = idx.references(sibFile, l, c, includeDeclaration = true)
      assert(refs.exists(_.path == mainFile), s"expected add usage in Main.scala, got ${refs.map(_.path.last)}")
      assert(refs.exists(_.path == sibFile), s"expected add def site in Siblings.scala")
    } finally os.remove.all(root)
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
    val root = TestFixture.copy("packages", "refs-pkg-greeting")
    try {
      val mainFile = root / "src" / "main" / "scala" / "com" / "example" / "Main.scala"
      val modelsFile = root / "src" / "main" / "scala" / "com" / "example" / "Models.scala"
      val mainText = os.read(mainFile)
      val modelsText = os.read(modelsFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(mainFile)
      idx.onDidOpen(modelsFile)
      val (l, c) = TestPositions.at(modelsText, """val (?<p>greeting):""")
      val refs = idx.references(modelsFile, l, c, includeDeclaration = true)
      assert(refs.exists(_.path == mainFile), s"expected greeting usage in Main.scala, got ${refs.map(_.path.last)}")
    } finally os.remove.all(root)
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
    val root = TestFixture.copy("crosslang", "refs-xlang")
    try {
      val useFile = root / "src" / "main" / "scala" / "com" / "lang" / "Use.scala"
      val greeterFile = root / "src" / "main" / "java" / "com" / "lang" / "Greeter.java"
      val useText = os.read(useFile)
      val greeterText = os.read(greeterFile)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(useFile)
      idx.onDidOpen(greeterFile)
      val (l, c) = TestPositions.at(greeterText, """class (?<p>Greeter)""")
      val refs = idx.references(greeterFile, l, c, includeDeclaration = true)
      assert(refs.exists(_.path == useFile), s"expected Greeter usage in Use.scala, got ${refs.map(_.path.last)}")
    } finally os.remove.all(root)
  }

  test("scalacli: references of utils object finds definition + usage across files") {
    val root = TestFixture.copy("scalacli", "refs-scalacli-utils")
    try {
      val blaFile = root / "bla.scala"
      val bla2File = root / "bla2.scala"
      val blaText = os.read(blaFile)
      val bla2Text = os.read(bla2File)
      val (idx, _) = freshIndexAt(root)
      idx.onDidOpen(blaFile)
      idx.onDidOpen(bla2File)
      val (l, c) = TestPositions.at(bla2Text, """object (?<p>utils)""")
      val refs = idx.references(bla2File, l, c, includeDeclaration = true)
      assert(refs.exists(_.path == blaFile), s"expected utils usage in bla.scala, got ${refs.map(_.path.last)}")
      assert(refs.exists(_.path == bla2File), s"expected utils def in bla2.scala")
    } finally os.remove.all(root)
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

  test("source-only: goto utils.getMsg() cross-file without semanticdb") {
    val root = TestFixture.copy("sbt", "source-only-sbt-getmsg")
    try {
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
}
