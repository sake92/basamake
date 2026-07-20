package ba.sake.basamake.manager

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import java.util.Timer
import java.util.TimerTask
import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{DocumentSymbol, Location, Position, PublishDiagnosticsParams, SymbolInformation}
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import ch.epfl.scala.bsp4j.SourcesResult
import ch.epfl.scala.bsp4j.SourceItemKind
import ba.sake.basamake.core.*
import ba.sake.basamake.bsp.{BspConnectionSupervisor, BspDiscovery, BspConnectionId, BspConnectionState, BspConnectionSpec}
import ba.sake.basamake.config.BasamakeConfig
import ba.sake.basamake.routing.BspRouter
import ba.sake.basamake.navigation.SemanticdbNavigationIndex
import ba.sake.basamake.watcher.FileChangeWatcher

private case class ConnectionContext(
    record: DurableRecord,
    queue: BlockingQueue[ConnectionMessage],
    navIndex: SemanticdbNavigationIndex,
    var sourceRootsByTarget: Map[String, List[String]] = Map.empty,
    var dependencySourceUrisByTarget: Map[String, List[String]] = Map.empty
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
  private val openUris = mutable.Set.empty[String]
  private val debounceMs = 300L
  private val debounceTimer = new Timer("basamake-bsp-watcher-debounce", true)
  private val debounceLock = Object()
  private var pendingBspChanges: Set[os.Path] = Set.empty
  private var pendingDebounceTask: Option[TimerTask] = None
  @volatile private var shuttingDown = false

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

    watcher = FileChangeWatcher(
      workspaceRoot,
      onFileChanged,
      filterOnCreated = !watchIgnored(_)
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
    val ctx = ConnectionContext(record, queue, SemanticdbNavigationIndex())
    connections(id) = ctx

    val bspDir = bspSpec.path.toNIO.getParent
    router.registerBspRoot(bspDir, Set(id))

    val routingCallback = (buildServer: ch.epfl.scala.bsp4j.BuildServer,
                           targets: List[ch.epfl.scala.bsp4j.BuildTarget],
                           sources: ch.epfl.scala.bsp4j.SourcesResult,
                           dependencySources: ch.epfl.scala.bsp4j.DependencySourcesResult) => {
      val dirs = extractSourceDirs(sources)
      ctx.sourceRootsByTarget = extractTargetSourceRoots(sources)
      ctx.dependencySourceUrisByTarget = extractTargetDependencySourceUris(dependencySources)
      router.registerGroundTruth(id, dirs)
      logger.info(s"Routing updated for $id: ${dirs.size} source dirs")
      dirs.foreach(d => logger.debug(s"  $d"))
      refreshNavigationIndex(id, buildServer, targets.map(_.getId.getUri))
    }

    val compileCallback = (buildServer: ch.epfl.scala.bsp4j.BuildServer, targetIds: List[String]) =>
      refreshNavigationIndex(id, buildServer, targetIds)

    val vt = Thread.ofVirtual().start(() =>
      BspConnectionSupervisor.supervise(record, queue, client, routingCallback, compileCallback)
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

  private def extractTargetSourceRoots(sources: SourcesResult): Map[String, List[String]] =
    sources.getItems.asScala.toList.flatMap { item =>
      Option(item.getTarget).map(_.getUri -> extractSourceDirsForItem(item))
    }.toMap

  private def extractTargetDependencySourceUris(dependencySources: ch.epfl.scala.bsp4j.DependencySourcesResult): Map[String, List[String]] =
    dependencySources.getItems.asScala.toList.flatMap { item =>
      Option(item.getTarget).map { target =>
        target.getUri ->
          Option(item.getSources).toList.flatMap(_.asScala).map(_.toString)
      }
    }.toMap

  private def extractSourceDirsForItem(item: ch.epfl.scala.bsp4j.SourcesItem): List[String] =
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
        router.unregisterBspRoot(bspDir, connId)
        ctx.navIndex.clear()
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

  def definition(uri: String, position: Position): List[Location] = synchronized {
    val result = connectionForUri(uri).toList.flatMap(_.navIndex.definition(uri, position))
    logger.debug(s"definition uri=$uri line=${position.getLine} ch=${position.getCharacter} hits=${result.size}")
    result
  }

  def references(uri: String, position: Position): List[Location] = synchronized {
    val result = connectionForUri(uri).toList.flatMap(_.navIndex.references(uri, position))
    logger.debug(s"references uri=$uri line=${position.getLine} ch=${position.getCharacter} hits=${result.size}")
    result
  }

  def documentSymbols(uri: String): List[Either[SymbolInformation, DocumentSymbol]] = synchronized {
    val result = connectionForUri(uri).toList.flatMap(_.navIndex.documentSymbols(uri))
    logger.debug(s"documentSymbols uri=$uri hits=${result.size}")
    result
  }

  def trackDidOpen(uri: String): Unit = synchronized {
    openUris += uri
  }

  def trackDidClose(uri: String): Unit = synchronized {
    openUris -= uri
  }

  // ---- File watcher → BSP event classification ----
  // TODO read gitignore..
  private def watchIgnored(path: os.Path): Boolean =
    val relative = try Some(path.relativeTo(workspaceRoot))
      catch { case _: Exception => None }
    relative match
      case None => true                                   // non-workspace path
      case Some(rel) if rel.segments.isEmpty => false     // root — always watch
      case Some(rel) =>
        val segments = rel.segments.toSeq
        // Exclude build artifact / tool directories.
        segments.sliding(2).exists(_.toSeq == Seq(".basamake", "logs")) ||
          segments.head == "target" || // sbt, TODO recursive??
          segments.head == "out" || // mill
          segments.head == ".deder" ||
          segments.head == ".metals"

  /** Generic callback from FileChangeWatcher — fires on os-lib's internal threads.
    * Debounces then diffs current vs known .bsp JSON files to classify events. */
  private def onFileChanged(changedPaths: Set[os.Path]): Unit = {
    val watchedChangedPaths = changedPaths.filterNot(watchIgnored)
    if watchedChangedPaths.nonEmpty then {
      val changedBspFiles = watchedChangedPaths.filter(_.segments.toSeq.contains(".bsp"))
      if changedBspFiles.nonEmpty then
        logger.info(s"Detected .bsp change(s): ${changedBspFiles.mkString(", ")}")
        enqueueBspChangeBatch(changedBspFiles)
    }
  }

  private def enqueueBspChangeBatch(changedBspFiles: Set[os.Path]): Unit =
    debounceLock.synchronized {
      pendingBspChanges = pendingBspChanges ++ changedBspFiles
      pendingDebounceTask.foreach(_.cancel())

      val task = new TimerTask {
        override def run(): Unit =
          val batch = debounceLock.synchronized {
            val toHandle = pendingBspChanges
            pendingBspChanges = Set.empty
            pendingDebounceTask = None
            toHandle
          }
          if batch.nonEmpty then
            // TODO make invalidation more granular
            router.invalidateBootstrapCache()
            handleBspChanges(batch)
      }

      pendingDebounceTask = Some(task)
      debounceTimer.schedule(task, debounceMs)
    }

  /** Compare current filesystem to knownBspFiles snapshot.
    * Runs on debounce timer thread — must touch only manager-owned state. */
  private def handleBspChanges(changed: Set[os.Path]): Unit = {
    synchronized {
      val current = BspDiscovery.discover(workspaceRoot).map(_.path).toSet
      val (newFiles, deletedFiles, modifiedFiles) =
        BuildServerManager.classifyBspChanges(knownBspFiles, current, changed)
      val hadTopologyChange = newFiles.nonEmpty || deletedFiles.nonEmpty || modifiedFiles.nonEmpty

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

      if hadTopologyChange then
        replayOpenAndErroredUris()
    }
  }

  /** Re-dispatch compile triggers for currently open and currently errored files after BSP topology changes. */
  private def replayOpenAndErroredUris(): Unit = {
    val candidateUris =
      openUris.toSet ++ connections.values.flatMap(_.record.lastKnownDiagnostics.keys)

    for uri <- candidateUris do
      router.route(uri).flatMap(connections.get).foreach: ctx =>
        ctx.queue.offer(ConnectionMessage.RecheckUri(uri))
  }

  // ---- Lifecycle ----

  /** Graceful shutdown: stop watcher, detach connections, kill descendant processes. */
  def shutdown(): Unit =
    if shuttingDown then return
    shuttingDown = true

    // Stop watcher first so no new connections spawned during shutdown
    if watcher != null then watcher.stop()
    debounceLock.synchronized {
      pendingDebounceTask.foreach(_.cancel())
      pendingDebounceTask = None
      pendingBspChanges = Set.empty
    }
    debounceTimer.cancel()

    connections.keys.toList.foreach: connId =>
      detachConnection(connId)
    logger.info("All connections detached")

    // Kill any remaining descendant processes
    val killed = ProcessUtils.terminateProcessHandleTree(java.lang.ProcessHandle.current())
    if killed > 0 then
      logger.info(s"Killed $killed descendant process node(s) during shutdown")

  private def connectionForUri(uri: String): Option[ConnectionContext] =
    router.route(uri).flatMap(connections.get)

  private def refreshNavigationIndex(
      connId: BspConnectionId,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      targetIds: List[String]
  ): Unit =
    connections.get(connId) match
      case Some(ctx) if targetIds.nonEmpty && (ctx.sourceRootsByTarget.nonEmpty || ctx.dependencySourceUrisByTarget.nonEmpty) =>
        try
          ctx.navIndex.refresh(
            workspaceRoot,
            buildServer,
            targetIds,
            ctx.sourceRootsByTarget,
            ctx.dependencySourceUrisByTarget
          )
          logger.debug(
            s"SemanticDB refresh conn=$connId targets=${targetIds.size} sourceRoots=${ctx.sourceRootsByTarget.size} dependencySources=${ctx.dependencySourceUrisByTarget.size}"
          )
        catch case e: Exception =>
          logger.warn(s"SemanticDB refresh failed for $connId: ${e.getMessage}")
      case Some(_) => ()
      case None    => ()
}

object BuildServerManager:
  private[manager] def classifyBspChanges(
      known: Set[os.Path],
      current: Set[os.Path],
      changed: Set[os.Path]
  ): (Set[os.Path], Set[os.Path], Set[os.Path]) =
    val newFiles = current -- known
    val deletedFiles = known -- current
    val modifiedFiles = known.intersect(current).intersect(changed)
    (newFiles, deletedFiles, modifiedFiles)


