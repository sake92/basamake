package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import ba.sake.basamake.navigation.*
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, Range => SdbRange, SymbolOccurrence}

/** Regression for the sttp client3 import line:
  * `import sttp.client3.{HttpError, SttpBackend, UriContext, basicRequest}`.
  *
  * Ground-truth importee symbols (Scala 3.7.4 compiler semanticdb, dumped from
  * a real compile — the four names resolve to four DIFFERENT definition shapes):
  *   HttpError     → `sttp/client3/HttpError.` + `sttp/client3/HttpError#`
  *                   (generic case class + companion — both must be indexed)
  *   SttpBackend   → `sttp/client3/SttpBackend.`
  *                   (trait — the importee is the SYNTHETIC COMPANION TERM,
  *                   not the type symbol; usage sites use `SttpBackend#`)
  *   UriContext    → `sttp/model/UriInterpolator#UriContext().`
  *                   (implicit class NESTED in trait UriInterpolator, living in
  *                   the sttp.model artifact — the importee is the implicit
  *                   CONVERSION METHOD symbol, not the class)
  *   basicRequest  → `sttp/client3/SttpApi#basicRequest.`
  *                   (val in trait SttpApi, reached via `package object client3
  *                   extends SttpApi`)
  */
class SttpGotoDefTest extends FunSuite, TestCacheRoot {

  private def fixtureJar(name: String): os.Path =
    os.pwd / "modules" / "navigation" / "test" / "resources" / "jars" / name

  private val sttpClient3Jar = fixtureJar("sttp-client3-core_3-3.11.0-sources.jar")
  private val sttpModelJar = fixtureJar("sttp-model-core_3-1.7.17-sources.jar")

  /** Pair `Main.scala` at the workspace root with a hand-crafted semanticdb
    * whose occurrences carry the compiler's importee symbols for the four
    * names (as a real compile would emit them). */
  private def pairMainWithSemanticdb(workspace: os.Path, occurrences: List[SymbolOccurrence]): Unit = {
    val semDir = workspace / ".semanticdb"
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "Main.scala",
      text = os.read(workspace / "Main.scala"),
      language = Language.SCALA,
      symbols = Nil,
      occurrences = occurrences
    )
    os.makeDir.all(semDir)
    os.write(semDir / "Main.scala.semanticdb", TextDocuments(List(doc)).toByteArray)
  }

  private def gotoDefOn(mainFile: os.Path, mainText: String, idx: WorkspaceIndex, regex: String): Vector[SymbolDefinition] = {
    val (l, c) = TestPositions.at(mainText, regex)
    idx.gotoDefinitions(mainFile, l, c, depCandidates = List(sttpClient3Jar, sttpModelJar))
  }

  test("sttp client3 import line: all four importees resolve into the dep sources") {
    val workspace = os.temp.dir(prefix = "sttp-gotodef-ws-")
    val mainFile = workspace / "Main.scala"
    val mainText = "import sttp.client3.{HttpError, SttpBackend, UriContext, basicRequest}\n"
    os.write.over(mainFile, mainText)

    try {
      // importee ranges on line 0 (copied from a real Scala 3.7.4 semanticdb dump)
      pairMainWithSemanticdb(workspace, List(
        SymbolOccurrence(symbol = "sttp/client3/HttpError.", range = Some(SdbRange(0, 21, 0, 30)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "sttp/client3/HttpError#", range = Some(SdbRange(0, 21, 0, 30)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "sttp/client3/SttpBackend.", range = Some(SdbRange(0, 32, 0, 43)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "sttp/model/UriInterpolator#UriContext().", range = Some(SdbRange(0, 45, 0, 55)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "sttp/client3/SttpApi#basicRequest.", range = Some(SdbRange(0, 57, 0, 69)), role = SymbolOccurrence.Role.REFERENCE)
      ))

      val depsTable = new IndexedSymbolTable
      depsTable.registerTarget(List(sttpClient3Jar, sttpModelJar))

      val idx = new WorkspaceIndex(workspace, new InMemorySymbolTable, Some(depsTable))
      idx.initialize(List(SemanticdbDirs(workspace, workspace / ".semanticdb")))
      idx.onDidOpen(mainFile)

      def eventually(cond: => Boolean): Boolean = {
        val deadline = System.currentTimeMillis() + 60000
        while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(100)
        cond
      }

      // each importee must resolve into the extracted dep source — the background
      // index of the two jars runs on first lookup, so poll until warm
      assert(eventually(gotoDefOn(mainFile, mainText, idx, "HttpError").nonEmpty),
        "HttpError must resolve (case class + companion)")
      assert(eventually(gotoDefOn(mainFile, mainText, idx, "SttpBackend").nonEmpty),
        "SttpBackend must resolve (trait synthetic-companion TERM symbol)")
      assert(eventually(gotoDefOn(mainFile, mainText, idx, "UriContext").nonEmpty),
        "UriContext must resolve (implicit-class conversion method, in sttp.model)")
      assert(eventually(gotoDefOn(mainFile, mainText, idx, "basicRequest").nonEmpty),
        "basicRequest must resolve (val in trait SttpApi)")

      // and each resolves to the RIGHT file
      val httpErrorLocs = gotoDefOn(mainFile, mainText, idx, "HttpError")
      assert(httpErrorLocs.map(_.path.last).contains("ResponseAs.scala"),
        s"HttpError should live in ResponseAs.scala, got ${httpErrorLocs.map(_.path.last)}")
      val sttpBackendLocs = gotoDefOn(mainFile, mainText, idx, "SttpBackend")
      assert(sttpBackendLocs.map(_.path.last).contains("SttpBackend.scala"),
        s"SttpBackend should live in SttpBackend.scala, got ${sttpBackendLocs.map(_.path.last)}")
      val uriContextLocs = gotoDefOn(mainFile, mainText, idx, "UriContext")
      assert(uriContextLocs.map(_.path.last).contains("UriInterpolator.scala"),
        s"UriContext should live in UriInterpolator.scala (sttp.model), got ${uriContextLocs.map(_.path.last)}")
      val basicRequestLocs = gotoDefOn(mainFile, mainText, idx, "basicRequest")
      assert(basicRequestLocs.map(_.path.last).contains("SttpApi.scala"),
        s"basicRequest should live in SttpApi.scala, got ${basicRequestLocs.map(_.path.last)}")
    } finally {
      os.remove.all(workspace)
      os.remove.all(SourceJarIndexer.cacheRoot / os.RelPath(Fingerprint.fromJarPath(sttpClient3Jar)))
      os.remove.all(SourceJarIndexer.cacheRoot / os.RelPath(Fingerprint.fromJarPath(sttpModelJar)))
    }
  }
}
