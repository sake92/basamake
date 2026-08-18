package ba.sake.basamake.watcher

import com.typesafe.scalalogging.StrictLogging

/** Generic workspace file watcher. Spawns internal os-lib threads that fire
  * callbacks on changes. No BSP logic, no debounce, no state tracking —
  * those are the manager's responsibility. */
class FileChangeWatcher(
    workspaceRoot: os.Path,
    onChanged: Set[os.Path] => Unit,
    filterOnCreated: os.Path => Boolean = _ => true
) extends StrictLogging {

  private var watcher: Option[AutoCloseable] = None

  def start(): Unit = {
    logger.info(s"Starting file watcher on $workspaceRoot")
    // os.watch.watch spawns a daemon thread
    watcher = Some(os.watch.watch(
      Seq(workspaceRoot),
      changed => onChanged(changed),
      filter = filterOnCreated // applies only to created files, not existing/modified or deleted files
    ))
  }

  def stop(): Unit =
    watcher.foreach(_.close())
    watcher = None
}
