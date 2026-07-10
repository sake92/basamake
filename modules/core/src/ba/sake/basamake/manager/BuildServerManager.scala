package ba.sake.basamake.manager

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import java.util.Timer
import java.util.TimerTask
import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import ba.sake.basamake.core.*
import ba.sake.basamake.bsp.{BspConnectionSupervisor, BspDiscovery, BspConnectionId, BspConnectionState, BspConnectionSpec}
import ba.sake.basamake.config.BasamakeConfig
import ba.sake.basamake.routing.BspRouter
import ba.sake.basamake.watcher.FileChangeWatcher
import ch.epfl.scala.bsp4j.SourcesResult
import ch.epfl.scala.bsp4j.SourceItemKind

private case class ConnectionContext(
    record: DurableRecord,
    queue: BlockingQueue[ConnectionMessage]
)

/** Manages BSP connections in a workspace, including lifecycle, message routing, and shutdown. */
class BuildServerManager extends StrictLogging {

  // TODO check thread safety
  private val connections = mutable.LinkedHashMap[BspConnectionId, ConnectionContext]()
  private var client: LanguageClient = uninitialized
  private var workspaceRoot: os.Path = uninitialized
  private var config: BasamakeConfig = uninitialized
  private val router = BspRouter()
  private var watcher: FileChangeWatcher = uninitialized
  private var knownBspFiles: Set[os.Path] = Set.empty
  private val debounceMs = 300L

  def initialize(workspaceRoot: os.Path, lspClient: LanguageClient, config: BasamakeConfig): Unit = {
    this.client = lspClient
    this.workspaceRoot = workspaceRoot
    this.config = config

    val bspSpecs = BspDiscovery.discover(workspaceRoot)
    if bspSpecs.isEmpty then
      logger.warn(s"No .bsp JSON files discovered under $workspaceRoot. No BSP connections will be established.")
    else
      logger.info(s"Discovered ${bspSpecs.size} BSP(s) — connections set up lazily (no processes started yet)")
    // Snapshot initial .bsp JSON files for change detection
    knownBspFiles = bspSpecs.map(_.path).toSet

    for bspSpec <- bspSpecs do {
      val overriddenSpec = applyOverrides(bspSpec)
      overriddenSpec.foreach(attachConnection)
    }

    watcher = FileChangeWatcher(workspaceRoot, onFileChanged,
    filter = { changedPath =>
        val p = changedPath.relativeTo(workspaceRoot)
        val segments = p.segments.toSeq
        segments.sliding(2).exists(_ == Seq(".basamake", "logs")) ||
        segments.head == "target" ||
        segments.head == ".deder" ||
        segments.head == ".metals"
      }
    )
    watcher.start()
    logger.debug(s"File watcher started for workspace $workspaceRoot")
  }

  /** Apply per .bsp file overrides. Returns None if the connection is disabled. */
  private def applyOverrides(originalSpec: BspConnectionSpec): Option[BspConnectionSpec] = {
    // Try to relativize the .bsp json path relative to workspace root.
    // Fall back to absolute path string if os-lib can't relativize.
    val relPath = try originalSpec.path.relativeTo(workspaceRoot).toString
      catch case _: Exception => originalSpec.path.toString
    config.bspOverrides.find(_.bspFile == relPath) match {
      case Some(ov) =>
        if ov.enabled then {
          val merged = originalSpec.copy(debounceMs = ov.debounceMs.getOrElse(originalSpec.debounceMs))
          logger.debug(s"Override applied for $relPath: debounceMs=${merged.debounceMs}")
          Some(merged)
        } else {
          logger.info(s"BSP connection $relPath is disabled by override")
          None
        }
      case None =>
        Some(originalSpec)
    }
  }

  /** Create a durable record, queue, and VT for a new connection spec. */
  private def attachConnection(bspSpec: BspConnectionSpec): Unit = try {
    logger.info(s"Attaching (lazy) BSP connection for ${bspSpec.path} (${bspSpec.content.name})")
    val id = BspConnectionId(bspSpec.path.toString)
    val record = DurableRecord(
      bspFile = bspSpec,
      attemptCounter = 0,
      lastKnownDiagnostics = Map.empty,
      currentState = BspConnectionState.Idle
    )
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    connections(id) = ConnectionContext(record, queue)

    val bspDir = bspSpec.path.toNIO.getParent
    router.registerBspRoot(bspDir, Set(id))

    val routingCallback = (targets: List[ch.epfl.scala.bsp4j.BuildTarget],
                           sources: ch.epfl.scala.bsp4j.SourcesResult) => {
      val dirs = extractSourceDirs(sources)
      router.registerGroundTruth(id, dirs)
      logger.info(s"Routing updated for $id: ${dirs.size} source dirs")
      dirs.foreach(d => logger.debug(s"  $d"))
    }

    val vt = Thread.ofVirtual().start(() =>
      BspConnectionSupervisor.supervise(record, queue, client, routingCallback)
    )
    logger.info(s"Spawned supervisor thread for $id (${bspSpec.path})")
  } catch {
    case e: Exception =>
      logger.error(s"Failed to attach BSP connection for ${bspSpec.path}: ${e.getMessage}", e)
  }

  private def extractSourceDirs(sources: SourcesResult): List[String] =
    sources.getItems.asScala.toList.flatMap: item =>
      Option(item.getSources).toList.flatMap(_.asScala).collect {
        case si if si.getKind == SourceItemKind.DIRECTORY && !si.getGenerated =>
          si.getUri
      }

  /** Cleanly detach a connection: publish empty diagnostics, remove routing, kill process. */
  private def detachConnection(connId: BspConnectionId): Unit =
    connections.get(connId) match
      case Some(ctx) =>
        logger.info(s"Detaching connection $connId")

        // Publish empty diagnostics for all files owned by this connection
        for uri <- ctx.record.lastKnownDiagnostics.keys do
          client.publishDiagnostics(
            new PublishDiagnosticsParams(uri, java.util.Collections.emptyList())
          )
        logger.info(s"Cleared diagnostics for ${ctx.record.lastKnownDiagnostics.size} files")

        // Mark as Detached and send poison pill
        ctx.record.currentState = BspConnectionState.Detached
        ctx.queue.offer(ConnectionMessage.Shutdown)
        ctx.record.lastKnownDiagnostics = Map.empty

        // Remove from routing and connections
        router.unregisterGroundTruth(connId)
        val bspDir = ctx.record.bspFile.path.toNIO.getParent
        router.unregisterBspRoot(bspDir)
        connections -= connId
        logger.info(s"Connection $connId detached")

      case None =>
        logger.warn(s"Cannot detach unknown connection $connId")

  /** Request a reload for a connection with a new spec. Re-applies overrides. */
  private def reloadConnection(connId: BspConnectionId, newSpec: BspConnectionSpec): Unit =
    applyOverrides(newSpec) match
      case Some(merged) =>
        connections.get(connId) match
          case Some(ctx) =>
            logger.info(s"Requesting reload for $connId")
            ctx.queue.offer(ConnectionMessage.ReloadRequested(merged))
          case None =>
            attachConnection(merged)
      case None =>
        detachConnection(connId)

  /** Route a document URI to the owning connection's queue via longest-prefix matching. */
  def route(uri: String): Option[BlockingQueue[ConnectionMessage]] =
    router.route(uri) match
      case Some(connId) =>
        connections.get(connId).map(_.queue) match
          case some @ Some(_) => some
          case None =>
            logger.warn(s"Connection $connId not found in connections map")
            None
      case None =>
        logger.debug(s"No BSP found for $uri")
        None
        

  // ---- File watcher → BSP event classification ----

  /** Generic callback from FileChangeWatcher — fires on os-lib's internal threads.
    * Debounces then diffs current vs known .bsp JSON files to classify events. */
  private def onFileChanged(changedPaths: Set[os.Path]): Unit =
    val changedBspFiles = changedPaths.filter(p => p.segments.toSeq.contains(".bsp"))
    if changedBspFiles.nonEmpty then
      logger.info(s"Detected .bsp change(s): ${changedBspFiles.mkString(", ")}")
      // Flush bootstrap cache — routes will re-walk on next query
      router.invalidateBootstrapCache()
      // Classify create/delete/modify and react (attach/detach/reload)
      classifyBspEvents(changedPaths)
    

  /** Compare current filesystem to knownBspFiles snapshot.
    * Runs on debounce timer thread — must touch only manager-owned state. */
  private def classifyBspEvents(changed: Set[os.Path]): Unit = {
    val current = BspDiscovery.discover(workspaceRoot).map(_.path).toSet
    val newFiles     = current -- knownBspFiles
    val deletedFiles = knownBspFiles -- current
    val modifiedFiles = knownBspFiles.intersect(current).intersect(changed)

    synchronized {
      // Deletions first — clean state before potential re-adds
      for p <- deletedFiles do
        logger.info(s"BSP config deleted: $p")
        knownBspFiles -= p
        detachConnection(BspConnectionId(p.toString))

      for p <- newFiles do
        logger.info(s"New BSP config detected: $p")
        knownBspFiles += p
        BspDiscovery.parseSingleSpec(p).foreach(spec => applyOverrides(spec).foreach(attachConnection))

      for p <- modifiedFiles do
        logger.info(s"BSP config modified: $p")
        BspDiscovery.parseSingleSpec(p).foreach: spec =>
          val connId = BspConnectionId(spec.path.toString)
          reloadConnection(connId, spec)
    }
  }

  // ---- Lifecycle ----

  /** Graceful shutdown: stop watcher, detach all connections. */
  def shutdown(): Unit =
    // Stop watcher first so no new connections spawned during shutdown
    if watcher != null then watcher.stop()

    connections.keys.toList.foreach: connId =>
      detachConnection(connId)
    logger.info("All connections detached")

  /** Force-kill any BSP processes that survived graceful shutdown. */
  def killBspProcesses(): Unit =
    Thread.sleep(500)
    killAllBspProcesses()
    Thread.sleep(200)
    killAllBspProcesses()

  private def killAllBspProcesses(): Unit =
    connections.values.foreach: ctx =>
      ctx.record.bspProcess.foreach: p =>
        if p.isAlive then
          logger.info(s"Force-killing BSP process ${p.pid()}")
          p.destroyForcibly()
        ctx.record.bspProcess = None
}
