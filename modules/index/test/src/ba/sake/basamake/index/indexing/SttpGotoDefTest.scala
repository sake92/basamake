package ba.sake.basamake.index.indexing

import munit.FunSuite
import ba.sake.basamake.index.*
import scala.meta.internal.semanticdb.{Range => SdbRange, SymbolOccurrence}

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
    os.pwd / "test" / "resources" / "jars" / name

  private val sttpClient3Jar = fixtureJar("sttp-client3-core_3-3.11.0-sources.jar")
  private val sttpModelJar = fixtureJar("sttp-model-core_3-1.7.17-sources.jar")

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
      DepTestUtils.pairMainWithSemanticdb(workspace, List(
        SymbolOccurrence(symbol = "sttp/client3/HttpError.", range = Some(SdbRange(0, 21, 0, 30)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "sttp/client3/HttpError#", range = Some(SdbRange(0, 21, 0, 30)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "sttp/client3/SttpBackend.", range = Some(SdbRange(0, 32, 0, 43)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "sttp/model/UriInterpolator#UriContext().", range = Some(SdbRange(0, 45, 0, 55)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "sttp/client3/SttpApi#basicRequest.", range = Some(SdbRange(0, 57, 0, 69)), role = SymbolOccurrence.Role.REFERENCE)
      ))

      val depsTable = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      depsTable.registerTarget(List(sttpClient3Jar, sttpModelJar))

      val idx = new WorkspaceIndex(workspace, new InMemorySymbolTable, Some(depsTable))
      idx.initialize(List(SemanticdbDirs(workspace, workspace / ".semanticdb")))
      idx.onDidOpen(mainFile)

      // each importee must resolve into the extracted dep source — the background
      // index of the two jars runs on first lookup, so poll until warm
      assert(DepTestUtils.eventually(gotoDefOn(mainFile, mainText, idx, "HttpError").nonEmpty, timeoutMs = 60000),
        "HttpError must resolve (case class + companion)")
      assert(DepTestUtils.eventually(gotoDefOn(mainFile, mainText, idx, "SttpBackend").nonEmpty, timeoutMs = 60000),
        "SttpBackend must resolve (trait synthetic-companion TERM symbol)")
      assert(DepTestUtils.eventually(gotoDefOn(mainFile, mainText, idx, "UriContext").nonEmpty, timeoutMs = 60000),
        "UriContext must resolve (implicit-class conversion method, in sttp.model)")
      assert(DepTestUtils.eventually(gotoDefOn(mainFile, mainText, idx, "basicRequest").nonEmpty, timeoutMs = 60000),
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
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(sttpClient3Jar)))
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(sttpModelJar)))
    }
  }
}
