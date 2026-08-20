package ba.sake.basamake.lsp

import java.util.concurrent.{CompletableFuture, CopyOnWriteArrayList}
import scala.jdk.CollectionConverters.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient

/** The single reusable LanguageClient for lsp tests. Captures diagnostics,
  * log messages and progress; replaces the old fakeClient / capturingClient /
  * progressClient triple. */
final class TestLanguageClient extends LanguageClient {

  private val lock = new Object
  private var diagByUri = Map.empty[String, List[Diagnostic]]
  private var publishCountByUri = Map.empty[String, Int]          // NEW
  private val messages = new CopyOnWriteArrayList[MessageParams]()
  private val progress = new CopyOnWriteArrayList[ProgressParams]()

  override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = lock.synchronized {
    val ds = Option(p.getDiagnostics).map(_.asScala.toList).getOrElse(Nil)
    diagByUri = diagByUri.updated(p.getUri, ds)
    publishCountByUri = publishCountByUri.updated(p.getUri, publishCountByUri.getOrElse(p.getUri, 0) + 1)
    lock.notifyAll()
  }
  override def telemetryEvent(x: Any): Unit = ()
  override def showMessage(p: MessageParams): Unit = ()
  override def showMessageRequest(p: ShowMessageRequestParams): CompletableFuture[MessageActionItem] =
    CompletableFuture.completedFuture(null)
  override def logMessage(p: MessageParams): Unit = messages.add(p)
  override def createProgress(p: WorkDoneProgressCreateParams): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)
  override def notifyProgress(p: ProgressParams): Unit = progress.add(p)
  override def applyEdit(p: ApplyWorkspaceEditParams): CompletableFuture[ApplyWorkspaceEditResponse] =
    CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))

  def diagnosticsFor(uri: String): List[Diagnostic] = lock.synchronized(diagByUri.getOrElse(uri, Nil))
  def loggedMessages: List[MessageParams] = messages.asScala.toList
  def progressNotifications: List[ProgressParams] = progress.asScala.toList

  /** Waits until a NEW (post-call) Info logMessage starting with "Compiled"
    * arrives — BspManager.onTaskFinish forwards an Info log for a successful
    * BSP compile (statusCode OK). Diagnostics alone can't mark completion:
    * scala-cli's BSP republishes an empty reset when it retries a failed task,
    * so an empty diagnostics state does NOT mean "compile finished". */
  def awaitCompileSucceeded(timeoutSec: Long = 120): Unit = {
    val startIdx = messages.size()
    val deadline = System.currentTimeMillis() + timeoutSec * 1000
    def newMsgs: List[MessageParams] = messages.asScala.toList.drop(startIdx)
    while (System.currentTimeMillis() < deadline) {
      if (newMsgs.exists(m => m.getType == MessageType.Info && m.getMessage.startsWith("Compiled"))) return
      Thread.sleep(100)
    }
    throw new AssertionError(
      s"awaitCompileSucceeded: no successful compile message within ${timeoutSec}s; " +
        s"new messages: ${newMsgs.map(m => s"[${m.getType}] ${m.getMessage}").mkString(" | ")}")
  }

  /** Waits (deadline-bounded) until diagnostics for `uri` satisfy `predicate`
    * AND at least `minPublishCount` publishDiagnostics batches were received
    * for the uri. minPublishCount>0 guards against `_.isEmpty` passing
    * vacuously before any compile has published anything. */
  def awaitDiagnostics(uri: String, predicate: List[Diagnostic] => Boolean, timeoutSec: Long = 30,
                       minPublishCount: Int = 0): List[Diagnostic] = {
    val deadline = System.currentTimeMillis() + timeoutSec * 1000
    lock.synchronized {
      def ready: Boolean =
        publishCountByUri.getOrElse(uri, 0) >= minPublishCount && predicate(diagByUri.getOrElse(uri, Nil))
      while (!ready && System.currentTimeMillis() < deadline) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining > 0) lock.wait(math.min(remaining, 200L))
      }
      if (ready) diagByUri.getOrElse(uri, Nil)
      else throw new AssertionError(
        s"awaitDiagnostics($uri): condition not met within ${timeoutSec}s " +
          s"(publishes seen: ${publishCountByUri.getOrElse(uri, 0)}, min required: $minPublishCount); " +
          s"last seen: ${diagByUri.getOrElse(uri, Nil).size} diagnostic(s)")
    }
  }
}
