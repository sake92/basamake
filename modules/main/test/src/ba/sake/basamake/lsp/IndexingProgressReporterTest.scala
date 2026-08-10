package ba.sake.basamake.lsp

import java.util.concurrent.{CompletableFuture, CopyOnWriteArrayList}
import munit.FunSuite
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import scala.jdk.CollectionConverters.*
import ba.sake.basamake.navigation.indexing.IndexingPhase

class IndexingProgressReporterTest extends FunSuite {

  /** Fake client that records createProgress + notifyProgress calls. */
  private final class FakeClient(created: java.util.List[WorkDoneProgressCreateParams],
                                 sent: java.util.List[ProgressParams]) extends LanguageClient {
    override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = ()
    override def telemetryEvent(x: Any): Unit = ()
    override def showMessage(p: MessageParams): Unit = ()
    override def showMessageRequest(p: ShowMessageRequestParams) =
      CompletableFuture.completedFuture(null.asInstanceOf[MessageActionItem])
    override def logMessage(p: MessageParams): Unit = ()
    override def applyEdit(p: ApplyWorkspaceEditParams) =
      CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))
    override def createProgress(p: WorkDoneProgressCreateParams): CompletableFuture[Void] = {
      created.add(p)
      CompletableFuture.completedFuture(null.asInstanceOf[Void])
    }
    override def notifyProgress(p: ProgressParams): Unit = sent.add(p)
  }

  /** (token, kind, message) of one ProgressParams — kind+message live on the
    * concrete Begin/Report/End classes, not on WorkDoneProgressNotification. */
  private def progressEvent(p: ProgressParams): (String, String, String) = {
    val n = p.getValue.getLeft
    val msg = n match {
      case b: WorkDoneProgressBegin  => b.getMessage
      case r: WorkDoneProgressReport => r.getMessage
      case e: WorkDoneProgressEnd    => e.getMessage
    }
    (p.getToken.getLeft, n.getKind.toString.toLowerCase, msg)
  }

  test("sends begin/report/end with counts and percentages") {
    val created = new CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = new IndexingProgressReporter
    rep.setClient(new FakeClient(created, sent))
    rep.setEnabled(true)
    rep.setThrottleMillis(0) // back-to-back events must all pass in this test

    rep.onProgress(IndexingPhase.Dependencies, 0, 130, "jarA")
    rep.onProgress(IndexingPhase.Dependencies, 12, 130, "jarB 45%")
    rep.onProgress(IndexingPhase.Dependencies, 130, 130, "Indexed jarB")

    assertEquals(created.asScala.map(_.getToken.getLeft).toList, List("basamake-deps"),
      "one token must be created for the phase")
    val events = sent.asScala.toList.map(progressEvent)
    assertEquals(events.head, ("basamake-deps", "begin", "0/130 jarA"))
    assertEquals(events(1), ("basamake-deps", "report", "12/130 jarB 45%"))
    assertEquals(events.last, ("basamake-deps", "end", "Indexed jarB"))
  }

  test("throttles reports but always sends begin and end") {
    val created = new CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = new IndexingProgressReporter
    rep.setClient(new FakeClient(created, sent))
    rep.setEnabled(true)

    rep.onProgress(IndexingPhase.Workspace, 0, 1000, "scanning")
    (1 to 1000).foreach { i => rep.onProgress(IndexingPhase.Workspace, i, 1000, s"file$i") }
    rep.onProgress(IndexingPhase.Workspace, 1000, 1000, "done")

    val kinds = sent.asScala.toList.map(progressEvent(_)._2)
    assertEquals(kinds.head, "begin")
    assertEquals(kinds.last, "end")
    assert(kinds.size < 100, s"1000 events must be throttled well below 100 notifications, got ${kinds.size}")
  }

  test("emits nothing when disabled (no window.workDoneProgress capability)") {
    val created = new CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = new IndexingProgressReporter
    rep.setClient(new FakeClient(created, sent)) // never setEnabled

    rep.onProgress(IndexingPhase.Jdk, 0, 1, "src.zip")
    rep.onProgress(IndexingPhase.Jdk, 1, 1, "src.zip")

    assertEquals(created.size(), 0)
    assertEquals(sent.size(), 0)
  }

  test("disables permanently when createProgress fails — indexing must not throw") {
    val failing = new LanguageClient {
      override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = ()
      override def telemetryEvent(x: Any): Unit = ()
      override def showMessage(p: MessageParams): Unit = ()
      override def showMessageRequest(p: ShowMessageRequestParams) =
        CompletableFuture.completedFuture(null.asInstanceOf[MessageActionItem])
      override def logMessage(p: MessageParams): Unit = ()
      override def applyEdit(p: ApplyWorkspaceEditParams) =
        CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))
      override def createProgress(p: WorkDoneProgressCreateParams): CompletableFuture[Void] =
        throw new RuntimeException("client does not support progress")
    }
    val rep = new IndexingProgressReporter
    rep.setClient(failing)
    rep.setEnabled(true)

    rep.onProgress(IndexingPhase.Jdk, 0, 1, "src.zip") // must NOT throw
    rep.onProgress(IndexingPhase.Jdk, 1, 1, "src.zip") // must NOT throw
  }
}
