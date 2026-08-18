package ba.sake.basamake.bsp

import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.watcher.FileChangeWatcher

/** Owns the filesystem watcher and the .bsp-change debounce machinery.
  * No BSP semantics: splits raw change batches into .gitignore changes and
  * .bsp changes and hands them to the manager via callbacks. */
final class BspWatcher(
    workspaceRoot: os.Path,
    isIgnored: os.Path => Boolean,
    onGitignoreChanged: () => Unit,
    onBspFilesChanged: Set[os.Path] => Unit
) extends StrictLogging {

  private val DebounceMs = 500L

  // ScheduledExecutorService survives task exceptions (unlike java.util.Timer,
  // whose thread dies forever on an uncaught exception in a task).
  private val debounceExecutor = Executors.newSingleThreadScheduledExecutor((r: Runnable) => {
    val t = new Thread(r, "basamake-bsp-watcher-debounce")
    t.setDaemon(true)
    t
  })
  private val debounceLock = new Object
  private var pendingBspChanges: Set[os.Path] = Set.empty
  private var pendingDebounceTask: Option[ScheduledFuture[?]] = None

  private var started = false
  private val watcher = FileChangeWatcher(workspaceRoot, onFileChanged, p => !isIgnored(p))

  def start(): Unit = {
    started = true
    watcher.start()
    logger.debug(s"File watcher started for workspace $workspaceRoot")
  }

  /** Safe to call before start() or multiple times. */
  def stop(): Unit = {
    debounceLock.synchronized {
      pendingDebounceTask.foreach(_.cancel(false))
      pendingDebounceTask = None
      pendingBspChanges = Set.empty
    }
    debounceExecutor.shutdownNow()
    if (started) { watcher.stop(); started = false }
  }

  private def onFileChanged(changedPaths: Set[os.Path]): Unit = {
    val watched = changedPaths.filterNot(isIgnored)
    val changedBspFiles = watched.filter(_.segments.toSeq.contains(".bsp"))
    val gitignoreChanges = watched.filter(_.last == ".gitignore")
    if (gitignoreChanges.nonEmpty) {
      logger.info(s"Detected .gitignore change(s): ${gitignoreChanges.mkString(", ")} — reloading ignore engine")
      onGitignoreChanged()
    }
    if (changedBspFiles.nonEmpty) {
      logger.info(s"Detected .bsp change(s): ${changedBspFiles.mkString(", ")}")
      enqueueBspChangeBatch(changedBspFiles)
    }
  }

  private def enqueueBspChangeBatch(changedBspFiles: Set[os.Path]): Unit = {
    debounceLock.synchronized {
      pendingBspChanges = pendingBspChanges ++ changedBspFiles
      pendingDebounceTask.foreach(_.cancel(false))
      val task: Runnable = () => {
        val batch = debounceLock.synchronized {
          val toHandle = pendingBspChanges
          pendingBspChanges = Set.empty
          pendingDebounceTask = None
          toHandle
        }
        if (batch.nonEmpty) {
          // never let a failing batch kill the debounce executor
          try onBspFilesChanged(batch)
          catch { case e: Exception => logger.error(s"Failed to process .bsp changes: ${e.getMessage}", e) }
        }
      }
      pendingDebounceTask = Some(debounceExecutor.schedule(task, DebounceMs, TimeUnit.MILLISECONDS))
    }
  }
}
