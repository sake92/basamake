package ba.sake.basamake.lsp.index

import munit.FunSuite
import ba.sake.basamake.navigation.*

class WorkspaceIndexTest extends FunSuite {

  val sbtDir = os.pwd / "modules" / "main" / "test" / "resources" / "examples" / "sbt"

  private def idx = {
    val index = WorkspaceIndex()
    val scalaFiles = os.walk(sbtDir).filter(p => os.isFile(p) && p.ext == "scala")
    for path <- scalaFiles do
      val parser = ScalaSourceParser(path)
      index.indexFile(path, parser.parse())
    index
  }

  // The test Main.scala content (0-indexed lines):
  //  0: import upickle.default._
  //  1: (blank)
  //  2: @main def hello(): Unit =
  //  3:   val c = Array(1, 2, 3)
  //  4:   val d = c
  //  5:   println("Hello world!")
  //  6:   println(msg)        ← msg reference at (6,10)-(6,13)
  //  7:   write(Seq(1, 2, 3))
  //  8:  (blank)
  //  9: def msg =             ← msg definition at (9,4)-(9,7)
  // 10:   utils.getMsg()
  // utils.scala:
  //  1: (blank)
  //  2: object utils {
  //  3:     def getMsg() = "bla"

  test("findSymbolsAt: clicking 'msg' in println(msg) finds toplevel def") {
    val index = idx
    val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
    // println(msg) at line 6 (0-indexed), char 10 = 'm' in 'msg'
    val syms = index.findSymbolsAt(mainFile, 6, 10)
    assert(syms.exists(_.value == "_empty_/Main$package.msg()."),
      s"Expected _empty_/Main$$package.msg(), got: ${syms.map(_.value)}")
  }

  test("gotoDefinitions: 'msg' returns location from Main.scala") {
    val index = idx
    val locs = index.gotoDefinitions(Symbol("_empty_/Main$package.msg()."))
    assert(locs.nonEmpty, "Expected location for msg()")
    val loc = locs.head
    assertEquals(loc.path.last, "Main.scala")
    assertEquals(loc.range.startLine, 9)  // "def msg"
    assertEquals(loc.range.startCharacter, 4)
  }

  test("findSymbolsAt: clicking 'c' in val c = ... finds local def") {
    val index = idx
    val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
    // val c = Array(...) at line 3, char 6 = 'c'
    val syms = index.findSymbolsAt(mainFile, 3, 6)
    assert(syms.exists(s => SymbolUtils.isLocalSymbol(s.value)),
      s"Expected local symbol for 'c', got: ${syms.map(_.value)}")
  }

  test("findLocalDefinition: finds location of local val 'c'") {
    val index = idx
    val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
    val syms = index.findSymbolsAt(mainFile, 3, 6)
    val localSym = syms.find(s => SymbolUtils.isLocalSymbol(s.value)).get
    val loc = index.findLocalDefinition(mainFile, localSym)
    assert(loc.isDefined, s"Expected local def location for $localSym")
    assertEquals(loc.get.range.startLine, 3)
  }

  test("findLocalDefinition: finds location of local val 'c' via ref") {
    val index = idx
    val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
    // val d = c at line 4, char 10 = 'c' usage
    val syms = index.findSymbolsAt(mainFile, 4, 10)
    assert(syms.exists(s => SymbolUtils.isLocalSymbol(s.value)),
      s"Expected local ref to 'c', got: ${syms.map(_.value)}")
    val localSym = syms.find(s => SymbolUtils.isLocalSymbol(s.value)).get
    val loc = index.findLocalDefinition(mainFile, localSym)
    assert(loc.isDefined, "Local def should be resolvable from ref")
  }

  test("findSymbolsAt: clicking 'getMsg' in utils.getMsg() — cross-file qualifier, no refs") {
    val index = idx
    val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
    // utils.getMsg() at line 10, char 8 = 'g' in 'getMsg'
    // Known limitation: Term.Select emits candidates only when qualifier resolves.
    // 'utils' resolves empty (cross-file) → no candidates emitted.
    val syms = index.findSymbolsAt(mainFile, 10, 10)
    assert(syms.isEmpty || syms.forall(s => !s.value.contains("utils")),
      s"Cross-file Term.Select should not produce refs, got: ${syms.map(_.value)}")
  }

  test("gotoDefinitions: 'utils.getMsg' def descriptor finds location from utils.scala") {
    val index = idx
    val locs = index.gotoDefinitions(Symbol("_empty_/utils.getMsg()."))
    assert(locs.nonEmpty, "Expected location for utils.getMsg()")
    val loc = locs.head
    assertEquals(loc.path.last, "utils.scala")
  }

  test("gotoDefinitions: 'utils.getMsg' val descriptor falls back to def descriptor") {
    val index = idx
    // alternateDescriptor flips . → (). — the def EXISTS so val request resolves to def
    val locs = index.gotoDefinitions(Symbol("_empty_/utils.getMsg."))
    assert(locs.nonEmpty,
      s"Val candidate should fall back to def via alternateDescriptor, got empty")
    assertEquals(locs.head.path.last, "utils.scala")
  }

  test("definitions: index filters locals, only globals in global map") {
    val index = idx
    val allKeys = index.definitions.keys.map(_.value).toSet
    assert(allKeys.exists(_.startsWith("_empty_/")), "Expected global symbols")
    assert(!allKeys.exists(SymbolUtils.isLocalSymbol),
      "Local symbols should NOT be in global definitions map")
  }
}
