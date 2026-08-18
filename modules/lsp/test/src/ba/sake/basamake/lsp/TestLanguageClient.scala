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
  private val messages = new CopyOnWriteArrayList[MessageParams]()
  private val progress = new CopyOnWriteArrayList[ProgressParams]()

  override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = lock.synchronized {
    val ds = Option(p.getDiagnostics).map(_.asScala.toList).getOrElse(Nil)
    diagByUri = diagByUri.updated(p.getUri, ds)
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

  /** Waits (deadline-bounded) until diagnostics for `uri` satisfy `predicate`. */
  def awaitDiagnostics(uri: String, predicate: List[Diagnostic] => Boolean, timeoutSec: Long = 30): List[Diagnostic] = {
    val deadline = System.currentTimeMillis() + timeoutSec * 1000
    lock.synchronized {
      while (!predicate(diagByUri.getOrElse(uri, Nil)) && System.currentTimeMillis() < deadline) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining > 0) lock.wait(math.min(remaining, 200L))
      }
      val ds = diagByUri.getOrElse(uri, Nil)
      if (predicate(ds)) ds
      else throw new AssertionError(
        s"awaitDiagnostics($uri): condition not met within ${timeoutSec}s; last seen: ${ds.size} diagnostic(s)")
    }
  }
}
