package ba.sake.basamake.bsp

import java.util.concurrent.{Executors, RejectedExecutionException, ScheduledFuture, TimeUnit}
import java.util.concurrent.locks.ReentrantLock
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.watcher.FileChangeWatcher
import ba.sake.basamake.index.indexing.SemanticdbDirs

/** Owns the os-lib watcher and debounces BSP and SemanticDB changes. */
final class BspWatcher(
    workspaceRoot: os.Path,
    isIgnored: os.Path => Boolean,
    onGitignoreChanged: () => Unit,
    onBspFilesChanged: Set[os.Path] => Unit,
    onConfigChanged: () => Unit,
    onSemanticdbChanged: List[SemanticdbDirs] => Unit = _ => ()
) extends StrictLogging {

  private val DebounceMs = 500L
  // Owned by this watcher, shut down in stop(). Daemon: abrupt exit loses only
  // pending cache refreshes, which the next startup rebuilds.
  private val debounceExecutor = Executors.newSingleThreadScheduledExecutor((r: Runnable) => {
    val t = new Thread(r, "basamake-bsp-watcher-debounce")
    t.setDaemon(true)
    t
  })
  private val lock = new ReentrantLock()
  private var pendingBspChanges: Set[os.Path] = Set.empty
  private var pendingSemanticdbRoots: Set[SemanticdbDirs] = Set.empty
  private var pendingDebounceTask: Option[ScheduledFuture[?]] = None
  private var stopped = false
  private var watchRoots: List[os.Path] = List(workspaceRoot)
  @volatile private var semanticdbRoots: List[SemanticdbDirs] = Nil

  private val configPath = workspaceRoot / ".basamake" / "config.json"
  private val watcher = FileChangeWatcher(workspaceRoot, onFileChanged, shouldTraverse)

  // Ancestors must pass the filter too: target/ is normally pruned before the
  // watcher ever reaches its registered SemanticDB output directory.
  private def semanticdbTree(root: SemanticdbDirs): os.Path =
    root.semanticdbDir / "META-INF" / "semanticdb"

  private def shouldTraverse(path: os.Path): Boolean =
    !isIgnored(path) || path == configPath || semanticdbRoots.exists { root =>
      semanticdbTree(root).startsWith(path) ||
        (path.startsWith(semanticdbTree(root)) && (os.isDir(path) || path.ext == "semanticdb"))
    }

  def start(): Unit = {
    lock.lock()
    try { if (!stopped) watcher.start() }
    finally lock.unlock()
  }

  /** Cached and live BSP metadata share this registration path. Watching an
    * existing ancestor also catches outputs that do not exist until compilation. */
  def setSemanticdbRoots(roots: List[SemanticdbDirs]): Unit = {
    lock.lock()
    try {
      val distinct = roots.distinct
      if (!stopped && distinct.toSet != semanticdbRoots.toSet) {
        val previous = semanticdbRoots
        semanticdbRoots = distinct
        val external = distinct.map(_.semanticdbDir).filterNot(_.startsWith(workspaceRoot)).map { dir =>
          var ancestor = dir / os.up
          while (!os.isDir(ancestor) && ancestor != ancestor / os.up) ancestor = ancestor / os.up
          ancestor
        }.distinct
        val candidates = workspaceRoot :: external
        val nextWatchRoots = candidates.filterNot(p => candidates.exists(other => other != p && p.startsWith(other)))
        try watcher.setRoots(nextWatchRoots)
        catch {
          case e: Exception =>
            semanticdbRoots = previous
            throw e
        }
        watchRoots = nextWatchRoots
        // Catch changes made before registration or while the watcher restarted.
        enqueue(Set.empty, distinct.toSet)
      }
    } finally lock.unlock()
  }

  /** Safe before start() and on repeated calls. */
  def stop(): Unit = {
    lock.lock()
    try {
      stopped = true
      pendingDebounceTask.foreach(_.cancel(false))
      pendingDebounceTask = None
      pendingBspChanges = Set.empty
      pendingSemanticdbRoots = Set.empty
      debounceExecutor.shutdownNow()
      watcher.stop()
    } finally lock.unlock()
  }

  private def onFileChanged(changedPaths: Set[os.Path]): Unit = {
    val watched = changedPaths.filter(p => !isIgnored(p) || p == configPath)
    val changedBspFiles = watched.filter(_.segments.toSeq.contains(".bsp"))
    if (watched.exists(_.last == ".gitignore")) onGitignoreChanged()
    if (watched.contains(configPath)) onConfigChanged()
    val affectedRoots = semanticdbRoots.filter { root =>
      changedPaths.exists { path =>
        semanticdbTree(root).startsWith(path) ||
          (path.startsWith(semanticdbTree(root)) && (path.ext == "semanticdb" || os.isDir(path) || !os.exists(path)))
      }
    }.toSet
    if (changedBspFiles.nonEmpty || affectedRoots.nonEmpty) enqueue(changedBspFiles, affectedRoots)
  }

  private def enqueue(bspFiles: Set[os.Path], roots: Set[SemanticdbDirs]): Unit = {
    lock.lock()
    try {
      if (stopped) return
      pendingBspChanges ++= bspFiles
      pendingSemanticdbRoots ++= roots
      pendingDebounceTask.foreach(_.cancel(false))
      val task: Runnable = () => {
        lock.lock()
        val (bspBatch, semBatch) = try {
          // An external clean can delete the ancestor we were watching. Promote
          // that watch to a surviving ancestor before awaiting rebuilt outputs.
          if (!stopped && watchRoots.exists(p => !os.isDir(p))) {
            val surviving = watchRoots.map { path =>
              var ancestor = path
              while (!os.isDir(ancestor) && ancestor != ancestor / os.up) ancestor = ancestor / os.up
              ancestor
            }.distinct
            watcher.setRoots(surviving)
            watchRoots = surviving
          }
          val batch = (pendingBspChanges, pendingSemanticdbRoots.intersect(semanticdbRoots.toSet))
          pendingBspChanges = Set.empty
          pendingSemanticdbRoots = Set.empty
          pendingDebounceTask = None
          batch
        } finally lock.unlock()
        if (bspBatch.nonEmpty) {
          try onBspFilesChanged(bspBatch)
          catch { case e: Exception => logger.error(s"Failed to process BSP changes: ${e.getMessage}", e) }
        }
        if (semBatch.nonEmpty && !Thread.currentThread().isInterrupted) {
          try onSemanticdbChanged(semBatch.toList)
          catch { case e: Exception => logger.error(s"Failed to refresh SemanticDB: ${e.getMessage}", e) }
        }
      }
      try pendingDebounceTask = Some(debounceExecutor.schedule(task, DebounceMs, TimeUnit.MILLISECONDS))
      catch { case _: RejectedExecutionException => () }
    } finally lock.unlock()
  }
}
