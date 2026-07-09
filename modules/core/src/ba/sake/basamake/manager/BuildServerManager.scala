package ba.sake.basamake.manager

import ba.sake.basamake.core.*
import ba.sake.basamake.bsp.{BspConnectionSupervisor, BspDiscovery, BspConnectionId, BspConnectionState, BspConnectionFile}
import ba.sake.basamake.config.BasamakeConfig
import ba.sake.basamake.routing.RoutingTable
import ba.sake.basamake.watcher.BspFileWatcher
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Path
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.collection.mutable
import scala.compiletime.uninitialized
import ba.sake.basamake.bsp.BspConnectionSpec

private case class ConnectionContext(
    record: DurableRecord,
    queue: BlockingQueue[ConnectionMessage]
)

/** Manages BSP connections, including lifecycle, message routing, and shutdown. */
class BuildServerManager extends StrictLogging {
  private val connections = mutable.LinkedHashMap[BspConnectionId, ConnectionContext]()
  private var client: LanguageClient = uninitialized
  private var workspaceRoot: Path = uninitialized
  private var config: BasamakeConfig = uninitialized
  private val routingTable = RoutingTable.empty
  private var watcher: BspFileWatcher = uninitialized
  private var watcherThread: Thread = uninitialized

  def initialize(workspaceRoot: Path, lspClient: LanguageClient, config: BasamakeConfig): Unit = {
    this.client = lspClient
    this.workspaceRoot = workspaceRoot
    this.config = config

    val bspFiles = BspDiscovery.discover(workspaceRoot)
    logger.info(s"Discovered ${bspFiles.size} BSP connection(s)")

    for bspFile <- bspFiles do
      applyOverrides(bspFile).foreach(attachConnection)

    // Start file watcher for live attach/detach/reload
    watcher = BspFileWatcher(
      workspaceRoot,
      onAttach = spec => applyOverrides(spec).foreach(attachConnection),
      onDetach = path => detachConnection(BspConnectionId(path.toAbsolutePath.toString)),
      onReload = spec => {
        val connId = BspConnectionId(spec.path.toAbsolutePath.toString)
        reloadConnection(connId, spec)
      }
    )
    watcherThread = Thread.ofVirtual().start(() => watcher.start())
    logger.info("File watcher started")
  }

  /** Apply per-.bsp-file overrides. Returns None if the connection is disabled. */
  private def applyOverrides(spec: BspConnectionSpec): Option[BspConnectionSpec] =
    val relPath = workspaceRoot.relativize(spec.path).toString
    config.bspOverrides.find(_.bspFile == relPath) match
      case Some(ov) if !ov.enabled =>
        logger.info(s"BSP connection $relPath is disabled by override")
        None
      case Some(ov) =>
        val merged = spec.copy(debounceMs = ov.debounceMs.getOrElse(spec.debounceMs))
        logger.debug(s"Override applied for $relPath: debounceMs=${merged.debounceMs}")
        Some(merged)
      case None =>
        Some(spec)

  /** Create a durable record, queue, and VT for a new connection spec. */
  private def attachConnection(bspFile: BspConnectionSpec): Unit = {
    val id = BspConnectionId(bspFile.path.toAbsolutePath.toString)
    val record = DurableRecord(
      bspFile = bspFile,
      attemptCounter = 0,
      lastKnownDiagnostics = Map.empty,
      currentState = BspConnectionState.Idle
    )
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    connections(id) = ConnectionContext(record, queue)

    val routingCallback = (targets: List[ch.epfl.scala.bsp4j.BuildTarget],
                           sources: ch.epfl.scala.bsp4j.SourcesResult) =>
      val dirs = BspConnectionSupervisor.extractSourceDirs(sources)
      routingTable.update(id, dirs)
      logger.info(s"Routing updated for $id: ${dirs.size} source dirs")
      dirs.foreach(d => logger.debug(s"  $d"))

    val vt = Thread.ofVirtual().start(() =>
      BspConnectionSupervisor.supervise(record, queue, client, routingCallback)
    )
    logger.info(s"Spawned supervisor for $id (${bspFile.content.name}) on VT ${vt.threadId()}")
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
        routingTable.remove(connId)
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
  def route(uri: String): BlockingQueue[ConnectionMessage] =
    routingTable.lookup(uri) match
      case Some(connId) =>
        connections.get(connId) match
          case Some(ctx) => ctx.queue
          case None =>
            throw IllegalStateException(s"Connection $connId not found for $uri")
      case None =>
        connections.values.headOption.map(_.queue).getOrElse(
          throw IllegalStateException("No BSP connections available. Is the workspace initialized?")
        )

  /** Graceful shutdown: stop watcher, detach all connections. */
  def shutdown(): Unit =
    // Stop watcher first so no new connections spawned during shutdown
    if watcher != null then watcher.stop()
    if watcherThread != null then watcherThread.interrupt()

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
