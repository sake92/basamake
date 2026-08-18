package ba.sake.basamake.bsp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.jsonrpc.messages.Either
import com.typesafe.scalalogging.StrictLogging
import ch.epfl.scala.bsp4j.{TaskFinishParams, TaskProgressParams, TaskStartParams}
import ba.sake.basamake.bsp.BspConnectionId

/** Forwards BSP compile task notifications to the LSP client as
  * `window/workDoneProgress` items — one token per BSP task id, grouped by the
  * owning BSP connection. VS Code renders these as a status-bar spinner while a
  * build runs ("Compiling …").
  *
  * Tokens are balanced by construction: taskStart begins, taskProgress reports,
  * taskFinish ends. If the BSP process dies mid-task (no finish arrives),
  * `endAll(connId)` — called on connection detach/shutdown — ends every token
  * of that connection, so no stuck progress item survives.
  *
  * Defensive by design (mirrors IndexingProgressReporter): a missing
  * `window/workDoneProgress` capability or a rejected createProgress degrades
  * silently to the logMessage fallback (BspManager keeps logging task start/
  * finish either way). */
class CompileProgressReporter extends StrictLogging {

  @volatile private var client: Option[LanguageClient] = None
  @volatile private var enabled: Boolean = false

  /** connection → active tokens of that connection (token = progress token). */
  private val activeByConn = new ConcurrentHashMap[BspConnectionId, java.util.Set[String]]()

  /** Called from connect() — the client proxy arrives before initialize. */
  def setClient(c: LanguageClient): Unit = client = Some(c)

  /** Called from initialize() with the client's window.workDoneProgress capability. */
  def setEnabled(flag: Boolean): Unit = enabled = flag

  private def tokensOf(connId: BspConnectionId): java.util.Set[String] =
    activeByConn.computeIfAbsent(connId, _ => ConcurrentHashMap.newKeySet[String]())

  /** LSP window/workDoneProgress tokens are a single shared namespace — scope
    * by connection id so two BSP servers emitting the same task id don't collide. */
  private def tokenFor(connId: BspConnectionId, taskId: String): String =
    s"basamake-compile-${connId.value}-$taskId"

  def onTaskStart(connId: BspConnectionId, params: TaskStartParams): Unit = {
    if (!enabled || client.isEmpty) return
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("")
    if (taskId.isEmpty) return
    val token = tokenFor(connId, taskId)
    try {
      client.get.createProgress(new WorkDoneProgressCreateParams(Either.forLeft(token)))
        .get(5, TimeUnit.SECONDS)
    } catch {
      case e: Exception =>
        logger.warn(s"Client rejected compile progress token $token — falling back to logMessage: ${e.getMessage}")
        return
    }
    val begin = new WorkDoneProgressBegin()
    val msg = Option(params.getMessage).filter(_.nonEmpty).getOrElse("Compiling")
    begin.setTitle("Compiling")
    begin.setMessage(msg)
    begin.setCancellable(false)
    if (notify(token, begin)) tokensOf(connId).add(token)
  }

  def onTaskProgress(connId: BspConnectionId, params: TaskProgressParams): Unit = {
    if (!enabled || client.isEmpty) return
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("")
    if (taskId.isEmpty) return
    val token = tokenFor(connId, taskId)
    if (tokensOf(connId).contains(token)) {
      val r = new WorkDoneProgressReport()
      Option(params.getProgress).foreach { pct =>
        r.setPercentage(math.max(0, math.min(100, pct.toInt)))
      }
      Option(params.getMessage).filter(_.nonEmpty).foreach(r.setMessage)
      notify(token, r)
    }
  }

  def onTaskFinish(connId: BspConnectionId, params: TaskFinishParams): Unit = {
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("")
    val token = tokenFor(connId, taskId)
    // Only the ACTIVE token gets an end — a stray/duplicate finish is a no-op,
    // and a token disabled in flight must never end twice (endAll after a later
    // detach). Removal happens regardless of `enabled`.
    val wasActive = tokensOf(connId).remove(token)
    if (wasActive && enabled && client.isDefined && taskId.nonEmpty) {
      val end = new WorkDoneProgressEnd()
      Option(params.getMessage).filter(_.nonEmpty).foreach(end.setMessage)
      notify(token, end)
    }
  }

  /** End all active items of one connection (server died / connection detached). */
  def endAll(connId: BspConnectionId): Unit = {
    val tokens = activeByConn.remove(connId)
    if (tokens == null || !enabled || client.isEmpty) return
    tokens.forEach { token => notify(token, new WorkDoneProgressEnd()) }
  }

  /** End all active items (BspManager shutdown). */
  def endAllConnections(): Unit =
    activeByConn.keySet().forEach(endAll)

  /** Test seam. */
  private[basamake] def activeTokenCount(connId: BspConnectionId): Int = {
    val tokens = activeByConn.get(connId)
    if (tokens == null) 0 else tokens.size()
  }

  private def notify(token: String, value: WorkDoneProgressNotification): Boolean = {
    try {
      client.get.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(value)))
      true
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to send compile progress for $token — disabling progress: ${e.getMessage}")
        enabled = false
        false
    }
  }
}
