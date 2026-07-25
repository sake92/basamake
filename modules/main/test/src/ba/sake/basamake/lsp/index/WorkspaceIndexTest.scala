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

  // Main.scala (0-indexed lines):
  //  0: import upickle.default._
  //  2: @main def hello(): Unit =
  //  3:   val c = Array(1, 2, 3)
  //  4:   val d = c
  //  5:   println("Hello world!")
  //  6:   println(msg)
  //  7:   write(Seq(1, 2, 3))
  //  9: def msg =
  // 10:   utils.getMsg()

  test("gotoDefinitions: 'msg' from fileDefs resolves to Main.scala location") {
    val index = idx
    val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
    // println(msg) at line 6, char 10 — same-file ref emits exact symbol
    val syms = index.findSymbolsAt(mainFile, 6, 10)
    assert(syms.exists(_.value == "_empty_/Main$package.msg()."),
      s"Expected _empty_/Main$$package.msg(), got: ${syms.map(_.value)}")
    val locs = index.gotoDefinitions(Symbol("_empty_/Main$package.msg()."))
    assert(locs.nonEmpty, "Expected location for msg()")
    assertEquals(locs.head.path.last, "Main.scala")
    assertEquals(locs.head.range.startLine, 9)
  }

  test("gotoDefinitions: local val 'c' works via findLocalDefinition") {
    val index = idx
    val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
    // val c at line 3, char 6
    val syms = index.findSymbolsAt(mainFile, 3, 6)
    assert(syms.exists(s => SymbolUtils.isLocalSymbol(s.value)),
      s"Expected local symbol for 'c', got: ${syms.map(_.value)}")
  }

  test("gotoDefinitions: local val 'c' usage resolves via ref") {
    val index = idx
    val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
    // val d = c at line 4, char 10 — 'c' usage
    val syms = index.findSymbolsAt(mainFile, 4, 10)
    val localSym = syms.find(s => SymbolUtils.isLocalSymbol(s.value)).get
    val loc = index.findLocalDefinition(mainFile, localSym)
    assert(loc.isDefined, "Local def should be resolvable from ref")
  }

  test("gotoDefinitions: cross-file 'utils' resolves via _empty_/ guess") {
    val index = idx
    val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
    // utils.getMsg() at line 10, char 4 — 'utils' term guess hits _empty_/utils.
    val syms = index.findSymbolsAt(mainFile, 10, 4)
    assert(syms.exists(_.value == "_empty_/utils."),
      s"Expected _empty_/utils., got: ${syms.map(_.value)}")
    val locs = index.gotoDefinitions(Symbol("_empty_/utils."))
    assert(locs.nonEmpty, "Expected location for utils")
    assertEquals(locs.head.path.last, "utils.scala")
  }

  test("gotoDefinitions: cross-file 'getMsg' resolves via qualifier guess") {
    val index = idx
    val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
    // utils.getMsg() at line 10, char 10 — member guess hits _empty_/utils.getMsg()
    val syms = index.findSymbolsAt(mainFile, 10, 10)
    assert(syms.exists(_.value == "_empty_/utils.getMsg()."),
      s"Expected _empty_/utils.getMsg(), got: ${syms.map(_.value)}")
    val locs = index.gotoDefinitions(Symbol("_empty_/utils.getMsg()."))
    assert(locs.nonEmpty, "Expected location for utils.getMsg()")
    assertEquals(locs.head.path.last, "utils.scala")
  }

  test("definitions: index filters locals from global map") {
    val index = idx
    val allKeys = index.definitions.keys.map(_.value).toSet
    assert(allKeys.exists(_.startsWith("_empty_/")), "Expected global symbols")
    assert(!allKeys.exists(SymbolUtils.isLocalSymbol),
      "Local symbols should NOT be in global definitions map")
  }
}
