package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.{Diagnostic, PublishDiagnosticsParams => LspPublishDiagnosticsParams, Range, Position}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

class BspManagerDiagnosticsTest extends FunSuite {

  test("two targets emit diagnostics for same uri → union published") {
    val (mgr, published, _) = BspManager.forTestingWithCapturedDiagnostics()
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
    val (mgr, published, _) = BspManager.forTestingWithCapturedDiagnostics()
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

  test("clearDiagnostics publishes empty even when no entry existed") {
    val (mgr, published, _) = BspManager.forTestingWithCapturedDiagnostics()
    val uri = "file:///x/A.scala"
    // No diagnostics were ever published for this uri — clearing must still
    // publish an empty list (VS Code keeps stale diagnostics otherwise).
    mgr.clearDiagnostics(uri)

    assert(published.size >= 1)
    val last = published.get(published.size - 1)
    assertEquals(last.getUri, uri)
    assertEquals(last.getDiagnostics.size(), 0)
  }

  test("onWatchedFilesChanged: deleted file cleared, created file safe no-op") {
    val (mgr, published, _) = BspManager.forTestingWithCapturedDiagnostics()
    val deletedUri = "file:///x/Deleted.scala"
    val createdUri = "file:///x/Created.scala"
    // Seed a diagnostic for the deleted file, then deliver watcher events.
    val target = new BuildTargetIdentifier("bsp://A")
    mgr.onDiagnostics(makeParams(deletedUri, target, "old-err", reset = true))
    published.clear()
    mgr.onWatchedFilesChanged(created = List(createdUri), deleted = List(deletedUri))

    // Deleted file's diagnostics were cleared; non-source files are ignored.
    val cleared = published.asScala.filter(_.getUri == deletedUri)
    assert(cleared.nonEmpty, s"expected empty publish for deleted file, got ${published.asScala.map(_.getUri)}")
    assertEquals(cleared.last.getDiagnostics.size(), 0)
  }

  test("onWatchedFilesChanged: non-source files ignored") {
    val (mgr, published, _) = BspManager.forTestingWithCapturedDiagnostics()
    mgr.onWatchedFilesChanged(created = List("file:///x/readme.txt"), deleted = Nil)
    // No source files involved → no diagnostics published at all.
    assertEquals(published.size(), 0)
  }

  test("onWatchedFilesChanged: changed source files flow through the batch (deleted still clears)") {
    val (mgr, published, _) = BspManager.forTestingWithCapturedDiagnostics()
    val deletedUri = "file:///x/Deleted.scala"
    val changedUri = "file:///x/Changed.scala"
    // Seed a diagnostic for the deleted file, then deliver a mixed batch
    // (created=empty, deleted + changed source events — e.g. git checkout
    // rewriting a file produces a Changed event).
    val target = new BuildTargetIdentifier("bsp://A")
    mgr.onDiagnostics(makeParams(deletedUri, target, "old-err", reset = true))
    published.clear()
    mgr.onWatchedFilesChanged(created = Nil, deleted = List(deletedUri), changed = List(changedUri))

    val cleared = published.asScala.filter(_.getUri == deletedUri)
    assert(cleared.nonEmpty, s"expected empty publish for deleted file, got ${published.asScala.map(_.getUri)}")
    assertEquals(cleared.last.getDiagnostics.size(), 0)
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
