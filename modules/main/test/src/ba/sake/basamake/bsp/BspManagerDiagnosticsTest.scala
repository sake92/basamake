package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.{Diagnostic, PublishDiagnosticsParams => LspPublishDiagnosticsParams, Range, Position}
import munit.FunSuite

class BspManagerDiagnosticsTest extends FunSuite {

  test("two targets emit diagnostics for same uri → union published") {
    val (mgr, published) = BspManager.forTestingWithCapturedDiagnostics()
    val uri = "file:///x/A.scala"
    val targetA = new BuildTargetIdentifier("bsp://A")
    val targetB = new BuildTargetIdentifier("bsp://B")

    mgr.onDiagnostics(makeParams(uri, targetA, "err-A1", reset = true))
    mgr.onDiagnostics(makeParams(uri, targetB, "err-B1", reset = true))

    assert(published.size >= 2)
    val last = published.get(published.size - 1)
    assertEquals(last.getUri, uri)
    assertEquals(last.getDiagnostics.size(), 2)
    var msgs = scala.collection.mutable.Set.empty[String]
    last.getDiagnostics.forEach(d => msgs += d.getMessage.getLeft)
    assert(msgs.contains("err-A1"), s"msgs=$msgs should contain err-A1")
    assert(msgs.contains("err-B1"))
  }

  test("reset=true clears only that target's slice") {
    val (mgr, published) = BspManager.forTestingWithCapturedDiagnostics()
    val uri = "file:///x/A.scala"
    val targetA = new BuildTargetIdentifier("bsp://A")
    val targetB = new BuildTargetIdentifier("bsp://B")

    mgr.onDiagnostics(makeParams(uri, targetA, "err-A1", reset = true))
    mgr.onDiagnostics(makeParams(uri, targetB, "err-B1", reset = true))
    // Reset A with an empty diagnostic list → A's slice becomes empty, union is just B.
    val emptyParams = makeParams(uri, targetA, "<none>", reset = true)
    emptyParams.setDiagnostics(java.util.Collections.emptyList())
    mgr.onDiagnostics(emptyParams)

    val last = published.get(published.size - 1)
    assertEquals(last.getUri, uri)
    val msgs = scala.collection.mutable.Set.empty[String]
    last.getDiagnostics.forEach(d => msgs += d.getMessage.getLeft)
    assert(msgs == Set("err-B1"), s"msgs should be Set(err-B1), got $msgs")
  }

  private def makeParams(uri: String, target: BuildTargetIdentifier, msg: String,
                         reset: Boolean): PublishDiagnosticsParams = {
    val list = new java.util.ArrayList[ch.epfl.scala.bsp4j.Diagnostic]()
    if (msg != "<none>") {
      val r = new ch.epfl.scala.bsp4j.Range(
        new ch.epfl.scala.bsp4j.Position(0, 0), new ch.epfl.scala.bsp4j.Position(0, 1))
      list.add(new ch.epfl.scala.bsp4j.Diagnostic(r, msg))
    }
    new PublishDiagnosticsParams(new TextDocumentIdentifier(uri), target, list, reset)
  }
}
