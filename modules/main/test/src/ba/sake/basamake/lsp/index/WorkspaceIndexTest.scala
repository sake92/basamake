package ba.sake.basamake.lsp.index

import munit.FunSuite
import ba.sake.basamake.navigation.*

class WorkspaceIndexTest extends FunSuite {

  val sbtDir = os.pwd / "modules" / "main" / "test" / "resources" / "examples" / "sbt"
  val mainFile = sbtDir / "src" / "main" / "scala" / "Main.scala"
  val utilsFile = sbtDir / "src" / "main" / "scala" / "utils.scala"

  // Main.scala (0-indexed lines):
  //  0: import upickle.default._
  //  1: (empty)
  //  2: @main def hello(): Unit =
  //  3:   val c = Array(1, 2, 3)
  //  4:   val d = c
  //  5:   println("Hello world!")
  //  6:   println(msg)          // line 6, char 8-11 = 'msg'
  //  7:   write(Seq(1, 2, 3))
  //  8: (empty)
  //  9: def msg =               // line 9, char 4-7 = 'msg'
  // 10:   utils.getMsg()        // line 10, char 2-7 = 'utils', char 8-14 = 'getMsg'

  private def freshIndex(): WorkspaceIndex = {
    val idx = WorkspaceIndex()
    idx.initialize(sbtDir) 
    idx
  }

  test("initialize populates symbolTable from source-AST fallback") {
    val idx = freshIndex()
    val utilsSym = idx.symbolTable.get("_empty_/utils.")
    assert(utilsSym.isDefined, "Expected _empty_/utils. in symbol table")
    assert(utilsSym.get.path.last == "utils.scala", s"Expected utils.scala path, got ${utilsSym.get.path.last}")

    val getMsgSym = idx.symbolTable.get("_empty_/utils.getMsg().")
    assert(getMsgSym.isDefined, "Expected _empty_/utils.getMsg(). in symbol table")
    assertEquals(getMsgSym.get.path, utilsFile)

    // v1 note: _empty_/Main$package.msg(). is NOT resolved by the references resolver
    // because top-level defs wrapped under $package are not reachable via the
    // current _empty_/ fallback in lookup(). Known limitation.
  }

  test("findSymbolsAt resolves cross-file utils identifier") {
    val idx = freshIndex()
    idx.onDidOpen(mainFile, os.read(mainFile))

    // utils.getMsg() at line 10, char 4 — the 'utils' identifier
    val syms = idx.findSymbolsAt(mainFile, 10, 4)
    assert(syms.exists(_ == "_empty_/utils."),
      s"Expected _empty_/utils., got: ${syms}")
  }

  test("gotoDefinitions resolves cross-file utils from Main.scala") {
    val idx = freshIndex()
    idx.onDidOpen(mainFile, os.read(mainFile))

    // utils.getMsg() at line 10, char 4 — the 'utils' identifier
    val locs = idx.gotoDefinitions(mainFile, 10, 4)
    assert(locs.nonEmpty, s"Expected locations for utils, got $locs")
    assertEquals(locs.head.path.last, "utils.scala")
  }

  test("gotoDefinitions resolves cross-file getMsg member") {
    val idx = freshIndex()
    idx.onDidOpen(mainFile, os.read(mainFile))

    // utils.getMsg() at line 10, char 10 — the 'getMsg' identifier
    val locs = idx.gotoDefinitions(mainFile, 10, 10)
    assert(locs.nonEmpty, s"Expected locations for getMsg, got $locs")
    assertEquals(locs.head.path.last, "utils.scala")
  }

  test("references finds utils def + usage across open files") {
    val idx = freshIndex()
    idx.onDidOpen(mainFile, os.read(mainFile))
    idx.onDidOpen(utilsFile, os.read(utilsFile))

    // 'utils' definition at line 2, char 7 in utils.scala (0-indexed: "object utils")
    val refs = idx.references(utilsFile, 1, 7, includeDeclaration = true)
    assert(refs.nonEmpty, s"Expected references for utils, got $refs")
    // Should include the usage in Main.scala at line 10 (utils.getMsg)
    val mainRefs = refs.filter(_.path == mainFile)
    assert(mainRefs.nonEmpty, s"Expected utils reference in Main.scala, got refs in: ${refs.map(_.path.last)}")
  }

  test("source-only fallback: WorkspaceIndex works without semanticdb") {
    val tmpDir = os.temp.dir()
    try {
      os.copy(mainFile, tmpDir / "Main.scala")
      os.copy(utilsFile, tmpDir / "utils.scala")
      val idx = WorkspaceIndex()
      idx.initialize(tmpDir)

      val utilsSym = idx.symbolTable.get("_empty_/utils.")
      assert(utilsSym.isDefined, "Source-only fallback: expected _empty_/utils.")
    } finally {
      os.remove.all(tmpDir)
    }
  }
}
