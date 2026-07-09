package ba.sake.basamake.watcher

import com.typesafe.scalalogging.StrictLogging
import java.nio.file.{Path, Paths}

/** Generic workspace file watcher. Spawns internal os-lib threads that fire
  * callbacks on changes. No BSP logic, no debounce, no state tracking —
  * those are the manager's responsibility. */
class FileChangeWatcher(
    workspaceRoot: Path,
    onChanged: Set[Path] => Unit
) extends StrictLogging:

  private val workspaceOsPath = os.Path(workspaceRoot.toAbsolutePath)
  private var watcher: AutoCloseable = scala.compiletime.uninitialized

  def start(): Unit =
    watcher = os.watch.watch(
      Seq(workspaceOsPath),
      changed => onChanged(changed.map(p => Paths.get(p.toString)))
    )
    // os.watch.watch spawns internal daemon threads and returns immediately.
    // The VT that called start() exits here — callbacks fire on os-lib threads.

  def stop(): Unit =
    if watcher != null then watcher.close()
    // Internal os-lib watcher threads are daemon — they die with the JVM.
