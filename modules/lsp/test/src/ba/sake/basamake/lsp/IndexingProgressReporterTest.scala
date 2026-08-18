package ba.sake.basamake.lsp

import java.util.concurrent.{CompletableFuture, CopyOnWriteArrayList}
import munit.FunSuite
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import scala.jdk.CollectionConverters.*
import ba.sake.basamake.index.indexing.IndexingPhase

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

  test("a rejected createProgress is retried after cooldown, not fatal") {
    // simulates the initialize-handshake window: the client has no
    // window/workDoneProgress/create handler yet (MethodNotFound), then works
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    var calls = 0
    val flaky = new LanguageClient {
      override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = ()
      override def telemetryEvent(x: Any): Unit = ()
      override def showMessage(p: MessageParams): Unit = ()
      override def showMessageRequest(p: ShowMessageRequestParams) =
        CompletableFuture.completedFuture(null.asInstanceOf[MessageActionItem])
      override def logMessage(p: MessageParams): Unit = ()
      override def applyEdit(p: ApplyWorkspaceEditParams) =
        CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))
      override def createProgress(p: WorkDoneProgressCreateParams): CompletableFuture[Void] = {
        calls += 1
        if (calls == 1) throw new RuntimeException("MethodNotFound: no handler yet")
        CompletableFuture.completedFuture(null.asInstanceOf[Void])
      }
      override def notifyProgress(p: ProgressParams): Unit = sent.add(p)
    }
    val rep = new IndexingProgressReporter
    rep.setClient(flaky)
    rep.setEnabled(true)
    rep.setBeginRetryMillis(0) // retry immediately in the test

    rep.onProgress(IndexingPhase.Jdk, 0, 570000, "src.zip") // rejected — must NOT throw
    rep.onProgress(IndexingPhase.Jdk, 100, 570000, "src.zip") // retry succeeds → begin
    rep.onProgress(IndexingPhase.Jdk, 570000, 570000, "src.zip") // end

    assertEquals(sent.asScala.toList.map(progressEvent(_)._2), List("begin", "end"),
      "a transiently rejected begin must recover on the next event")
    assertEquals(calls, 2, "exactly one retry of createProgress")
  }
}
