package ba.sake.basamake.watcher

import com.typesafe.scalalogging.StrictLogging

/** Generic workspace file watcher. Spawns internal os-lib threads that fire
  * callbacks on changes. No BSP logic, no debounce, no state tracking —
  * those are the manager's responsibility. */
class FileChangeWatcher(
    workspaceRoot: os.Path,
    onChanged: Set[os.Path] => Unit,
    filter: os.Path => Boolean = _ => true
) extends StrictLogging:

  private var watcher: AutoCloseable = scala.compiletime.uninitialized

  def start(): Unit =
    // os.watch.watch spawns a daemon thread
    watcher = os.watch.watch(
      Seq(workspaceRoot),
      changed => onChanged(changed),
      filter = filter
    )

  def stop(): Unit =
    if watcher != null then watcher.close()
