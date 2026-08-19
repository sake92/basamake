package ba.sake.basamake.lsp

import munit.FunSuite
import org.eclipse.lsp4j.DiagnosticSeverity

/** THE most important test in the project:
  * a user opens a real Scala project — .bsp discovery, BSP spawn, handshake,
  * compile, error diagnostics, fix, recompile, diagnostics clear,
  * go-to-definition — all through the real JSON-RPC transport and a real BSP
  * process. Nothing is mocked. Dep-cache isolation comes from the fixture's
  * .basamake/config.json (enableJdkIndexing=false, depsCacheRoot="deps-cache"
  * → everything stays inside the tmp copy, removed in finally). */
class LspIntegrationTest extends FunSuite {

  override def munitTimeout = scala.concurrent.duration.Duration(12, scala.concurrent.duration.MINUTES)

  // Fixed Main.scala: 42, line 3 = `    println(Utils.message)`, `Utils` at char 12.
  private val FixedMain =
    """|object Main:
       |  val broken: Int = 42
       |  def main(args: Array[String]): Unit =
       |    println(Utils.message)
       |""".stripMargin

  test("open → BSP → compile → error → fix → recompile → clear → definition") {
    val root = BspProjectFixture.prepare("errors", "lsp-e2e-errors")
    val client = LspTestClient.start(root)
    try {
      client.initialize()

      client.open("Main.scala")

      // Real BSP compile triggered by didOpen: the error must reach the client.
      // 300s cold-start budget: scala-cli BSP spawn + first-compile dependency resolution.
      val errs = client.awaitDiagnostics("Main.scala", _.nonEmpty, timeoutSec = 300)
      assert(
        errs.exists(_.getSeverity == DiagnosticSeverity.Error),
        s"expected an Error-severity diagnostic, got: ${errs.map(_.getMessage)}")

      // Fix the file, save: BSP recompiles, diagnostics clear.
      client.replaceAndSave("Main.scala", FixedMain)
      client.awaitDiagnostics("Main.scala", _.isEmpty, timeoutSec = 120)

      // Navigation works on the fixed workspace: Utils.message resolves into Utils.scala.
      val locs = client.goToDefinition("Main.scala", line = 3, char = 12)
      assert(locs.nonEmpty, "expected a definition for Utils.message")
      assertEquals(os.Path(java.net.URI.create(locs.head.getUri)).last, "Utils.scala")

      client.printTimings()
    } finally {
      client.close()
      os.remove.all(root)
    }
  }
}
