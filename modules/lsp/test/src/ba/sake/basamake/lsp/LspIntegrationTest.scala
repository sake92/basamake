package ba.sake.basamake.lsp

import munit.FunSuite
import org.eclipse.lsp4j.DiagnosticSeverity
import scala.jdk.CollectionConverters.*

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

  test("warm workflow: edit → compile → definition → references → hover") {
    val root = BspProjectFixture.prepare("simple", "lsp-e2e-warm")
    val client = LspTestClient.start(root)
    try {
      client.initialize()
      client.open("Main.scala")
      client.open("Utils.scala")
      // minPublishCount=1: the FIRST compile must have run AND be clean —
      // without the count guard an empty await passes before any compile.
      client.awaitDiagnostics("Main.scala", _.isEmpty, timeoutSec = 300, minPublishCount = 1)

      // Round 2: edit + save, still clean, then all three navigation requests.
      client.replaceAndSave("Main.scala",
        """|object Main:
           |  def main(args: Array[String]): Unit =
           |    println(Utils.add(Utils.add(2, 3), 4))
           |""".stripMargin)
      client.awaitDiagnostics("Main.scala", _.isEmpty, timeoutSec = 120, minPublishCount = 2)
      // The diagnostics await above can pass on the open-compile's start-clear
      // publishes before the edited file was compiled; references need the
      // round-2 semanticdb, so wait for an actually completed compile.
      client.awaitCompileSucceeded()

      // `Utils.add` on line 2: `    println(Utils.add(...)` — `Utils` at char 12, `add` at char 18.
      val defs = client.goToDefinition("Main.scala", line = 2, char = 18)
      assert(defs.nonEmpty, "expected definition for Utils.add")
      assertEquals(os.Path(java.net.URI.create(defs.head.getUri)).last, "Utils.scala")
      // JSON-RPC round-trip fidelity (subsumes LspTransportTest's position check):
      // `def add` is on 0-based line 2 of Utils.scala.
      assert(defs.head.getRange != null, "definition must carry a range")
      assertEquals(defs.head.getRange.getStart.getLine, 2, "definition line must survive the JSON-RPC round-trip")

      val refs = client.findReferences("Main.scala", line = 2, char = 18, includeDeclaration = false)
      assert(refs.size >= 2, s"expected both call sites of Utils.add, got ${refs.size}")
      assert(refs.forall(r => os.Path(java.net.URI.create(r.getUri)).last == "Main.scala"),
        "all references must be in Main.scala")

      val hov = client.hover("Utils.scala", line = 2, char = 6) // `def add` — `add` at char 6
      assert(hov.exists(h => h.getContents != null && h.getContents.getRight != null
        && !h.getContents.getRight.getValue.isEmpty), "expected non-empty hover on Utils.add")

      client.printTimings()
    } finally {
      client.close()
      os.remove.all(root)
    }
  }

  test("broken → fixed → broken again (diagnostics track the source)") {
    val root = BspProjectFixture.prepare("errors", "lsp-e2e-cycle")
    val client = LspTestClient.start(root)
    try {
      client.initialize()
      client.open("Main.scala")

      val brokenMain = os.read(root / "Main.scala") // the committed broken version
      client.awaitDiagnostics("Main.scala", _.nonEmpty, timeoutSec = 300)

      client.replaceAndSave("Main.scala", FixedMain)
      client.awaitDiagnostics("Main.scala", _.isEmpty, timeoutSec = 120, minPublishCount = 2)
      // scala-cli's BSP retries a failed compile task; the retry can swallow a
      // save made mid-compile (it compiles its stale file state and returns OK).
      // Wait for a compile that actually COMPLETED before re-breaking, so the
      // next save triggers a fresh compile of the broken content.
      client.awaitCompileSucceeded()

      client.replaceAndSave("Main.scala", brokenMain)
      client.awaitDiagnostics("Main.scala", _.nonEmpty, timeoutSec = 120, minPublishCount = 3)

      client.printTimings()
    } finally {
      client.close()
      os.remove.all(root)
    }
  }

  test("file created on disk → watcher picks it up → definition works; deletion → clean compile") {
    val root = BspProjectFixture.prepare("simple", "lsp-e2e-watch")
    val client = LspTestClient.start(root)
    try {
      client.initialize()
      client.open("Main.scala")
      client.awaitDiagnostics("Main.scala", _.isEmpty, timeoutSec = 300, minPublishCount = 1)

      // External tooling creates Extra.scala (no LSP notifications at all).
      client.writeOnDisk("Extra.scala",
        """|object Extra:
           |  def extraValue: Int = 99
           |""".stripMargin)

      // Use it and save: the BSP compile can only succeed if it saw the new
      // file; goto-def can only resolve if the index saw it too.
      client.replaceAndSave("Main.scala",
        """|object Main:
           |  def main(args: Array[String]): Unit =
           |    println(Extra.extraValue)
           |""".stripMargin)
      // Gate on the COMPILE completing, not on diagnostics: scala-cli publishes
      // diagnostics only when the per-URI state changes, so a second clean
      // compile emits no new publish at all. A failing compile would instead
      // publish errors and never send the Info "Compiled" message.
      client.awaitCompileSucceeded()

      // Watcher → index is eventually consistent: poll goto-def briefly.
      // Line 2, char 12 = `Extra` in `    println(Extra.extraValue)`.
      val defs = client.awaitUntil(90) {
        client.goToDefinition("Main.scala", line = 2, char = 12)
      }(_.nonEmpty)
      assertEquals(os.Path(java.net.URI.create(defs.head.getUri)).last, "Extra.scala")

      // External deletion + remove the usage: compile must stay clean.
      client.deleteOnDisk("Extra.scala")
      client.replaceAndSave("Main.scala",
        """|object Main:
           |  def main(args: Array[String]): Unit =
           |    println("done")
           |""".stripMargin)
      client.awaitCompileSucceeded()
      // Sanity: no diagnostics were ever reported for the edited file.
      assert(client.awaitDiagnostics("Main.scala", _ => true, timeoutSec = 10).isEmpty,
        "expected no diagnostics after the deletion phase")

      client.printTimings()
    } finally {
      client.close()
      os.remove.all(root)
    }
  }

  test("BSP killed → next save respawns it → recompile → diagnostics flow again") {
    val root = BspProjectFixture.prepare("errors", "lsp-e2e-restart")
    val client = LspTestClient.start(root)
    try {
      client.initialize()
      client.open("Main.scala")

      val brokenMain = os.read(root / "Main.scala")
      client.awaitDiagnostics("Main.scala", _.nonEmpty, timeoutSec = 300)

      // Fix once (clean state).
      client.replaceAndSave("Main.scala", FixedMain)
      client.awaitDiagnostics("Main.scala", _.isEmpty, timeoutSec = 120, minPublishCount = 2)
      client.awaitCompileSucceeded()

      // Kill the BSP process out from under basamake (crash simulation).
      killScalaCliDescendants()

      // User keeps working: break the file again and save. Basamake must
      // detect the dead connection (3s ping timeout), respawn, recompile,
      // and deliver diagnostics.
      client.replaceAndSave("Main.scala", brokenMain)
      client.awaitDiagnostics("Main.scala", _.nonEmpty, timeoutSec = 300, minPublishCount = 3)

      client.printTimings()
    } finally {
      client.close() // also verifies the respawned BSP is killed at shutdown
      os.remove.all(root)
    }
  }

  /** Forcibly destroy scala-cli descendant processes of this JVM (the BSP
    * server runs as a child of the in-process test server). Suites run
    * sequentially, so no other test can be affected. */
  private def killScalaCliDescendants(): Unit = {
    val killed = java.lang.ProcessHandle.current().descendants().iterator().asScala.count { p =>
      val cmd = p.info().commandLine().orElse("")
      val isScalaCli = cmd.contains("scala-cli")
      if (isScalaCli) { p.destroyForcibly(); true } else false
    }
    assert(killed > 0, "expected at least one scala-cli BSP descendant process to kill")
    Thread.sleep(500) // let the OS reap; the ping (not the sleep) drives recovery
  }

  test("multi-BSP workspace: files route to the correct BSP") {
    val root = BspProjectFixture.prepareMultiProject("multi-bsp", "lsp-e2e-multibsp")
    val client = LspTestClient.start(root)
    try {
      client.initialize()

      client.open("project-a/MainA.scala")
      client.open("project-b/MainB.scala")

      // minPublishCount=1: BOTH compiles must have run and be clean.
      client.awaitDiagnostics("project-a/MainA.scala", _.isEmpty, timeoutSec = 300, minPublishCount = 1)
      client.awaitDiagnostics("project-b/MainB.scala", _.isEmpty, timeoutSec = 300, minPublishCount = 1)

      // UtilsA.valueA in MainA line 2 char 12 → resolves inside project-a only.
      val defsA = client.goToDefinition("project-a/MainA.scala", line = 2, char = 12)
      assert(defsA.nonEmpty, "expected definition for UtilsA.valueA")
      val uriA = java.net.URI.create(defsA.head.getUri).toString
      assert(uriA.contains("project-a") && uriA.endsWith("UtilsA.scala"), s"routed wrong: $uriA")

      val defsB = client.goToDefinition("project-b/MainB.scala", line = 2, char = 12)
      assert(defsB.nonEmpty, "expected definition for UtilsB.valueB")
      val uriB = java.net.URI.create(defsB.head.getUri).toString
      assert(uriB.contains("project-b") && uriB.endsWith("UtilsB.scala"), s"routed wrong: $uriB")

      client.printTimings()
    } finally {
      client.close() // verifies BOTH scala-cli BSPs are killed at shutdown
      os.remove.all(root)
    }
  }
}
