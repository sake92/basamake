package ba.sake.basamake.lsp

import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.jsonrpc.messages.Either
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.index.indexing.{IndexingPhase, IndexingProgressListener}

/** Forwards navigation indexing events to the LSP client as `window/workDoneProgress`
  * items — one per phase (workspace / dependencies / JDK). VS Code renders these in
  * the status bar natively (no extension changes needed).
  *
  * Defensive by design: lsp4j's LanguageClient default methods THROW
  * UnsupportedOperationException, and a createProgress sent before the client
  * registered its progress handlers (the initialize handshake window) is
  * rejected with MethodNotFound. A rejected begin is TRANSIENT — it is retried
  * after a cooldown; only a broken transport (notifyProgress throwing) disables
  * the reporter. Indexing must never be held hostage by progress UI.
  *
  * Lifecycle: constructed before the client proxy exists; `setClient` is called
  * from connect(), `setEnabled` from initialize() with the client's
  * `window.workDoneProgress` capability. Events before enable are dropped —
  * nothing indexes before initialize anyway. */
class IndexingProgressReporter extends IndexingProgressListener with StrictLogging {

  @volatile private var client: Option[LanguageClient] = None
  @volatile private var enabled: Boolean = false

  private var throttleNanos = 100L * 1000000L // 100ms

  private final class PhaseState(val token: String, val title: String) {
    var active: Boolean = false
    var lastSendNanos: Long = 0L
    var lastBeginAttemptNanos: Long = 0L
  }

  private val workspace = new PhaseState("basamake-workspace", "Indexing workspace")
  private val deps = new PhaseState("basamake-deps", "Indexing dependencies")
  private val jdk = new PhaseState("basamake-jdk", "Indexing JDK sources")

  // The first begin can race the client's initialize handshake —
  // vscode-languageclient registers window/workDoneProgress/create only AFTER
  // the handshake completes, so an early createProgress is rejected with
  // MethodNotFound. That rejection is TRANSIENT: retry the begin after a
  // cooldown instead of disabling the reporter (which would kill progress on
  // cold caches — exactly when it matters most).
  private var beginRetryNanos = 10L * 1000000000L // 10s

  private def stateOf(phase: IndexingPhase): PhaseState = phase match {
    case IndexingPhase.Workspace    => workspace
    case IndexingPhase.Dependencies => deps
    case IndexingPhase.Jdk          => jdk
  }

  /** Called from connect() — the client proxy arrives before initialize. */
  def setClient(c: LanguageClient): Unit = client = Some(c)

  /** Called from initialize() with the client's window.workDoneProgress capability. */
  def setEnabled(flag: Boolean): Unit = enabled = flag

  /** Test seam — 0 disables throttling (back-to-back events all pass). */
  private[lsp] def setThrottleMillis(ms: Long): Unit = throttleNanos = ms * 1000000L

  /** Test seam — 0 disables the begin-retry cooldown. */
  private[lsp] def setBeginRetryMillis(ms: Long): Unit = beginRetryNanos = ms * 1000000L

  override def onProgress(phase: IndexingPhase, done: Long, total: Long, message: String): Unit = {
    if (!enabled || client.isEmpty) return
    if (total <= 0) return
    val st = stateOf(phase)
    val now = System.nanoTime()
    st.synchronized {
      if (!st.active) {
        if (done >= total) return // stray completion for a never-begun phase
        if (now - st.lastBeginAttemptNanos < beginRetryNanos) return // cooldown after a rejected begin
        begin(st, done, total, message)
      } else if (done >= total) {
        end(st, message)
      } else if (now - st.lastSendNanos >= throttleNanos) {
        report(st, done, total, message)
      }
    }
  }

  private def begin(st: PhaseState, done: Long, total: Long, message: String): Unit = {
    st.lastBeginAttemptNanos = System.nanoTime()
    try {
      client.get.createProgress(new WorkDoneProgressCreateParams(Either.forLeft(st.token)))
        .get(5, TimeUnit.SECONDS)
    } catch {
      case e: Exception =>
        logger.warn(s"Client rejected progress token ${st.token} — retrying later: ${e.toString}")
        return
    }
    val b = new WorkDoneProgressBegin()
    b.setTitle(st.title)
    b.setCancellable(false)
    b.setPercentage(0)
    b.setMessage(s"$done/$total $message")
    notify(st, b)
    st.active = true
    st.lastSendNanos = System.nanoTime()
  }

  private def report(st: PhaseState, done: Long, total: Long, message: String): Unit = {
    val r = new WorkDoneProgressReport()
    r.setPercentage((done * 100 / total).toInt)
    r.setMessage(s"$done/$total $message")
    notify(st, r)
    st.lastSendNanos = System.nanoTime()
  }

  private def end(st: PhaseState, message: String): Unit = {
    val e = new WorkDoneProgressEnd()
    e.setMessage(message)
    notify(st, e)
    st.active = false
  }

  private def notify(st: PhaseState, value: WorkDoneProgressNotification): Unit = {
    try {
      client.get.notifyProgress(new ProgressParams(Either.forLeft(st.token), Either.forLeft(value)))
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to send progress for ${st.token} — disabling progress: ${e.getMessage}")
        enabled = false
    }
  }
}
