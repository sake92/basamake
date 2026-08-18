package ba.sake.basamake.bsp

import java.util.concurrent.{CompletableFuture, CopyOnWriteArrayList}
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient

/** Minimal capturing LanguageClient for bsp-module tests (lsp's TestLanguageClient
  * lives in modules-lsp-test and is not visible here). */
final class CapturingLanguageClient extends LanguageClient {
  val published = new CopyOnWriteArrayList[PublishDiagnosticsParams]()
  val logged = new CopyOnWriteArrayList[MessageParams]()
  val progressed = new CopyOnWriteArrayList[ProgressParams]()
  override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = published.add(p)
  override def telemetryEvent(x: Any): Unit = ()
  override def showMessage(p: MessageParams): Unit = ()
  override def showMessageRequest(p: ShowMessageRequestParams): CompletableFuture[MessageActionItem] = CompletableFuture.completedFuture(null)
  override def logMessage(p: MessageParams): Unit = logged.add(p)
  override def createProgress(p: WorkDoneProgressCreateParams): CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def notifyProgress(p: ProgressParams): Unit = progressed.add(p)
  override def applyEdit(p: ApplyWorkspaceEditParams): CompletableFuture[ApplyWorkspaceEditResponse] = CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))
}
