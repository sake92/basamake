package ba.sake.basamake.watcher

import ba.sake.basamake.bsp.{BspConnectionFile, BspDiscovery}
import com.typesafe.scalalogging.StrictLogging
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Watches the workspace for .bsp JSON file changes and notifies the manager.
  * Uses os-lib-watch (os.watch.watch) on a dedicated virtual thread.
  * Classifies create/delete/modify events by comparing filesystem state
  * before and after each watch callback batch. */
class BspFileWatcher(
    workspaceRoot: Path,
    onAttach: BspConnectionFile => Unit,
    onDetach: java.nio.file.Path => Unit,
    onReload: BspConnectionFile => Unit
) extends StrictLogging:

  private val workspaceOsPath = os.Path(workspaceRoot.toAbsolutePath)
  @volatile private var running = true
  private val knownFiles = ConcurrentHashMap.newKeySet[Path]().asScala

  /** Start watching. Blocks the calling thread — run on a dedicated VT. */
  def start(): Unit =
    refreshKnownFiles()
    logger.info(s"File watcher started, watching ${knownFiles.size} .bsp JSON file(s)")
    try
      while running do
        try os.watch.watch(Seq(workspaceOsPath), handleEvents)
        catch
          case _: InterruptedException => ()
          case e: Exception => logger.error(s"Watch iteration error: ${e.getMessage}", e)
    catch
      case _: InterruptedException => ()
    logger.info("File watcher stopped")

  /** Request the watcher loop to stop on next iteration. */
  def stop(): Unit =
    running = false

  /** Callback from os.watch.watch — receives batches of changed paths.
    * We classify create/delete/modify by comparing before/after filesystem state. */
  private def handleEvents(changed: Set[os.Path]): Unit =
    // Debounce: let truncate-then-write bursts settle
    Thread.sleep(300)

    val currentFiles = findBspJsonFiles()
    val newFiles     = currentFiles -- knownFiles
    val deletedFiles = knownFiles -- currentFiles
    val changedPaths = changed.map(p => Paths.get(p.toString))
    val modifiedFiles = knownFiles.intersect(currentFiles).intersect(changedPaths)

    // Process deletions first (clean state before adds)
    for p <- deletedFiles do
      logger.info(s"BSP config deleted: $p")
      knownFiles -= p
      onDetach(p)

    // Process new files
    for p <- newFiles do
      logger.info(s"New BSP config detected: $p")
      knownFiles += p
      BspDiscovery.parseSingleSpec(p).foreach(onAttach)

    // Process modifications
    for p <- modifiedFiles do
      logger.info(s"BSP config modified: $p")
      BspDiscovery.parseSingleSpec(p).foreach(onReload)

  private def refreshKnownFiles(): Unit =
    knownFiles.clear()
    knownFiles.addAll(findBspJsonFiles())

  private def findBspJsonFiles(): Set[Path] =
    findBspDirs(workspaceRoot).flatMap: bspDir =>
      if Files.isDirectory(bspDir) then
        Files.list(bspDir)
          .filter(p => p.getFileName.toString.endsWith(".json"))
          .iterator().asScala
          .map(_.toAbsolutePath)
          .toSet
      else Set.empty[Path]
    .toSet

  private def findBspDirs(root: Path): List[Path] =
    if !Files.isDirectory(root) then return Nil
    val dirs = scala.collection.mutable.ListBuffer[Path]()
    Files.walk(root).forEach: p =>
      if Files.isDirectory(p) && p.getFileName.toString == ".bsp" then dirs += p
    dirs.toList
