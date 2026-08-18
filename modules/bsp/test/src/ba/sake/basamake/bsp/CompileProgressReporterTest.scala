package ba.sake.basamake.bsp

import java.util.concurrent.{CompletableFuture, CopyOnWriteArrayList}
import munit.FunSuite
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.{TaskFinishParams, TaskId, TaskProgressParams, TaskStartParams, StatusCode}
import ba.sake.basamake.bsp.BspConnectionId

class CompileProgressReporterTest extends FunSuite {

  private val connId = BspConnectionId("bsp://test")

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

  private def taskId(id: String): TaskId = new TaskId(id)

  private def startParams(id: String, msg: String): TaskStartParams = {
    val p = new TaskStartParams(taskId(id))
    p.setMessage(msg)
    p
  }

  private def progressParams(id: String, pct: Long, msg: String): TaskProgressParams = {
    val p = new TaskProgressParams(taskId(id))
    p.setProgress(pct)
    p.setMessage(msg)
    p
  }

  private def finishParams(id: String, status: StatusCode, msg: String): TaskFinishParams = {
    val p = new TaskFinishParams(taskId(id), status)
    p.setMessage(msg)
    p
  }

  /** (token, kind, message) of one ProgressParams. */
  private def progressEvent(p: ProgressParams): (String, String, String) = {
    val n = p.getValue.getLeft
    val msg = n match {
      case b: WorkDoneProgressBegin  => b.getMessage
      case r: WorkDoneProgressReport => r.getMessage
      case e: WorkDoneProgressEnd    => e.getMessage
    }
    (p.getToken.getLeft, n.getKind.toString.toLowerCase, msg)
  }

  private def freshReporter(created: java.util.List[WorkDoneProgressCreateParams],
                            sent: java.util.List[ProgressParams]): CompileProgressReporter = {
    val rep = new CompileProgressReporter
    rep.setClient(new FakeClient(created, sent))
    rep.setEnabled(true)
    rep
  }

  test("task start/progress/finish → balanced begin/report/end") {
    val created = new CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = freshReporter(created, sent)

    rep.onTaskStart(connId, startParams("task-1", "Compiling project A"))
    rep.onTaskProgress(connId, progressParams("task-1", 50L, "50%"))
    rep.onTaskFinish(connId, finishParams("task-1", StatusCode.OK, "Compiled"))

    assertEquals(created.asScala.map(_.getToken.getLeft).toList, List(s"basamake-compile-${connId.value}-task-1"))
    val events = sent.asScala.toList.map(progressEvent)
    assertEquals(events, List(
      (s"basamake-compile-${connId.value}-task-1", "begin", "Compiling project A"),
      (s"basamake-compile-${connId.value}-task-1", "report", "50%"),
      (s"basamake-compile-${connId.value}-task-1", "end", "Compiled")
    ))
    assertEquals(rep.activeTokenCount(connId), 0, "token must not leak after finish")
  }

  test("begin carries no percentage (spinner-style) and report carries it") {
    val created = new CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = freshReporter(created, sent)

    rep.onTaskStart(connId, startParams("t", "Compiling"))
    val begin = sent.get(0).getValue.getLeft.asInstanceOf[WorkDoneProgressBegin]
    assert(begin.getPercentage == null, "spinner-style begin must not pin a percentage")

    rep.onTaskProgress(connId, progressParams("t", 42L, "42%"))
    val report = sent.get(1).getValue.getLeft.asInstanceOf[WorkDoneProgressReport]
    assertEquals(report.getPercentage.intValue(), 42)
    rep.onTaskFinish(connId, finishParams("t", StatusCode.OK, ""))
  }

  test("finish with error/cancel still ends the token (no stuck spinner)") {
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = freshReporter(new CopyOnWriteArrayList[WorkDoneProgressCreateParams](), sent)

    rep.onTaskStart(connId, startParams("t", "Compiling"))
    rep.onTaskFinish(connId, finishParams("t", StatusCode.ERROR, "boom"))
    rep.onTaskFinish(connId, finishParams("t", StatusCode.CANCELLED, "cancelled"))

    val kinds = sent.asScala.toList.map(progressEvent(_)._2)
    assertEquals(kinds, List("begin", "end"))
    assertEquals(rep.activeTokenCount(connId), 0)
  }

  test("endAll ends active tokens of a dead connection, and only that connection") {
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = freshReporter(new CopyOnWriteArrayList[WorkDoneProgressCreateParams](), sent)
    val other = BspConnectionId("bsp://other")

    rep.onTaskStart(connId, startParams("a", "Compiling A"))
    rep.onTaskStart(other, startParams("b", "Compiling B"))
    assertEquals(rep.activeTokenCount(connId), 1)

    rep.endAll(connId) // connection died mid-task — no finish will ever arrive

    val ends = sent.asScala.toList.map(progressEvent).filter(_._2 == "end")
    assertEquals(ends.map(_._1), List(s"basamake-compile-${connId.value}-a"))
    assertEquals(rep.activeTokenCount(connId), 0)
    assertEquals(rep.activeTokenCount(other), 1, "other connection's token must survive")
    rep.endAllConnections()
    assertEquals(rep.activeTokenCount(other), 0)
  }

  test("progress for a never-started task is ignored (no unbalanced report)") {
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = freshReporter(new CopyOnWriteArrayList[WorkDoneProgressCreateParams](), sent)

    rep.onTaskProgress(connId, progressParams("ghost", 50L, "50%"))
    assertEquals(sent.size(), 0)
  }

  test("same task id on two connections yields distinct tokens (no cross-connection collision)") {
    val created = new CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = freshReporter(created, sent)
    val connA = BspConnectionId("bsp://a")
    val connB = BspConnectionId("bsp://b")

    rep.onTaskStart(connA, startParams("t", "Compiling A"))
    rep.onTaskStart(connB, startParams("t", "Compiling B"))

    val tokens = created.asScala.map(_.getToken.getLeft).toList
    assertEquals(tokens.distinct.size, 2, s"tokens must differ across connections, got $tokens")
    val begins = sent.asScala.toList.map(progressEvent)
    assertEquals(begins.map(_._1).distinct.size, 2, "both begins must reach the client")
    assertEquals(rep.activeTokenCount(connA), 1)
    assertEquals(rep.activeTokenCount(connB), 1)
  }

  test("emits nothing when disabled (no window.workDoneProgress capability)") {
    val created = new CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = new CompileProgressReporter
    rep.setClient(new FakeClient(created, sent)) // never setEnabled

    rep.onTaskStart(connId, startParams("t", "Compiling"))
    rep.onTaskFinish(connId, finishParams("t", StatusCode.OK, ""))

    assertEquals(created.size(), 0)
    assertEquals(sent.size(), 0)
  }

  test("a rejected createProgress falls back silently (logMessage path)") {
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val flaky = new LanguageClient {
      override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = ()
      override def telemetryEvent(x: Any): Unit = ()
      override def showMessage(p: MessageParams): Unit = ()
      override def showMessageRequest(p: ShowMessageRequestParams) =
        CompletableFuture.completedFuture(null.asInstanceOf[MessageActionItem])
      override def logMessage(p: MessageParams): Unit = ()
      override def applyEdit(p: ApplyWorkspaceEditParams) =
        CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))
      override def createProgress(p: WorkDoneProgressCreateParams): CompletableFuture[Void] =
        throw new RuntimeException("MethodNotFound: no window/workDoneProgress/create handler")
      override def notifyProgress(p: ProgressParams): Unit = sent.add(p)
    }
    val rep = new CompileProgressReporter
    rep.setClient(flaky)
    rep.setEnabled(true)

    // must not throw — the logMessage fallback keeps the user informed
    rep.onTaskStart(connId, startParams("t", "Compiling"))
    rep.onTaskFinish(connId, finishParams("t", StatusCode.OK, ""))

    assertEquals(sent.size(), 0)
    assertEquals(rep.activeTokenCount(connId), 0)
  }
}
