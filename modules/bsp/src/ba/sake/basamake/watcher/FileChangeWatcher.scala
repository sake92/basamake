package ba.sake.basamake.watcher

import com.typesafe.scalalogging.StrictLogging

/** Owns one os-lib watcher. The caller serializes start/restart/stop. */
class FileChangeWatcher(
    workspaceRoot: os.Path,
    onChanged: Set[os.Path] => Unit,
    filterOnCreated: os.Path => Boolean = _ => true
) extends StrictLogging {

  private var watcher: Option[AutoCloseable] = None
  private var roots: Seq[os.Path] = Seq(workspaceRoot)

  def start(): Unit = {
    logger.info(s"Starting file watcher on ${roots.mkString(", ")}")
    // Keep the old watch alive until the replacement is ready; duplicate events
    // are harmless because BspWatcher debounces them. os-lib owns daemon threads.
    val next = os.watch.watch(roots, onChanged, filter = filterOnCreated)
    watcher.foreach(_.close())
    watcher = Some(next)
  }

  /** Restart also rescans directories previously pruned by the traversal filter. */
  def setRoots(paths: Seq[os.Path]): Unit = {
    roots = paths
    if (watcher.nonEmpty) start()
  }

  def stop(): Unit = {
    watcher.foreach(_.close())
    watcher = None
  }
}
