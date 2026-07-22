package ba.sake.basamake.bsp

import java.util.concurrent.{BlockingQueue, ConcurrentHashMap, LinkedBlockingQueue}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.locks.LockSupport
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{DocumentSymbol, Location, Position, PublishDiagnosticsParams, SymbolInformation}
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ba.sake.tupson.{given, *}
import ba.sake.basamake.core.*
import ba.sake.basamake.config.BasamakeConfig
import ba.sake.basamake.navigation.{DependencySliceCache, NavigationIndex}
import ba.sake.basamake.watcher.FileChangeWatcher
import ba.sake.basamake.util.ProcessUtils


/** Manages BSP connections in a workspace, including lifecycle, message routing, and shutdown. */
class BuildServerManager extends StrictLogging {

  private val connections = ConcurrentHashMap[BspConnectionId, BspConnectionContext]()
  private val navStates = ConcurrentHashMap[BspConnectionId, NavRefreshState]()
  private var client: LanguageClient = uninitialized
  private var workspaceRoot: os.Path = uninitialized
  private var config: BasamakeConfig = uninitialized
  private val router = BspRouter()
  private var watcher: FileChangeWatcher = uninitialized
  private var knownBspFiles: Set[os.Path] = Set.empty
  private val openUris = ConcurrentHashMap.newKeySet[String]()
  private val debounceMs = 300L
  private val debounceTimer = new Timer("basamake-bsp-watcher-debounce", true)
  private val debounceLock = Object()
  private var pendingBspChanges: Set[os.Path] = Set.empty
  private var pendingDebounceTask: Option[TimerTask] = None
  private val shuttingDown = new AtomicBoolean(false)

  /** Deps sources slices live across BSP-servers and across BSP re-attach/reload; keyed per dep (uri + fingerprint). */
  private val depSliceCache = new DependencySliceCache()

  def initialize(workspaceRoot: os.Path, lspClient: LanguageClient, config: BasamakeConfig): Unit = {
    this.client = lspClient
    this.workspaceRoot = workspaceRoot
    this.config = config

    val discoveredBspSpecs = BspDiscovery.discover(workspaceRoot)
    if discoveredBspSpecs.isEmpty then
      logger.warn(s"No .bsp JSON files discovered under $workspaceRoot. No BSP connections will be established.")
    else
      logger.info(s"Discovered ${discoveredBspSpecs.size} BSP(s) — connections set up lazily (no processes started yet)")
    // Snapshot initial .bsp JSON files for change detection
    knownBspFiles = discoveredBspSpecs.map(_.path).toSet

    for bspSpec <- discoveredBspSpecs do {
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

    startStatusWriter()
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
          val merged = originalSpec.copy(
            debounceMs = ov.debounceMs.getOrElse(originalSpec.debounceMs),
            compileTimeoutSec = ov.compileTimeoutSec.getOrElse(originalSpec.compileTimeoutSec)
          )
          logger.debug(s"Override applied for $relPath: debounceMs=${merged.debounceMs} compileTimeoutSec=${merged.compileTimeoutSec}")
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
    logger.debug(s"Attaching (lazy) BSP connection for ${bspSpec.path} (${bspSpec.content.name})")
    val id = BspConnectionId(bspSpec.path.toString)
    val record = DurableRecord(
      bspFile = new AtomicReference(bspSpec),
      attemptCounter = new AtomicInteger(0),
      lastKnownDiagnostics = new AtomicReference(Map.empty),
      currentState = BspConnectionState.Idle
    )
    val msgQueue = new LinkedBlockingQueue[ConnectionMessage]()
    val ctx = BspConnectionContext(record, msgQueue, NavigationIndex(depSliceCache))
    connections.put(id, ctx)

    val bspDir = bspSpec.path.toNIO.getParent
    router.registerBspRoot(bspDir, Set(id))

    // Nav refresh runner: serialized per connection, latest-wins — never blocks supervisor VT.
    val navRefreshPending = new AtomicReference[List[BuildTargetIdentifier]](null)
    val navRefreshThread = Thread.ofVirtual().start(() => {
      while !ctx.shuttingDown do
        LockSupport.park()
        var targets = navRefreshPending.getAndSet(null)
        while targets != null && !ctx.shuttingDown do
          try
            if targets.nonEmpty then
              if ctx.sourceDirsByTarget.isEmpty && ctx.dependencySourceUrisByTarget.isEmpty then
                logger.warn(s"Nav refresh pending (${targets.size} targets) but source and dependency maps are both empty — nothing to index. BSP may not have reported sources yet.")
              else
                ctx.navIndex.refresh(
                  workspaceRoot, ctx.buildServer, targets,
                  ctx.sourceDirsByTarget, ctx.dependencySourceUrisByTarget,
                  openUris.asScala.toSet
                )
                logger.info(s"SemanticDB refresh conn=$id targets=${targets.size} workspace=${ctx.sourceDirsByTarget.size} dependency=${ctx.dependencySourceUrisByTarget.size}")
          catch case e: Exception =>
            logger.warn(s"Nav refresh failed for $id: ${e.getMessage}", e)
          targets = navRefreshPending.getAndSet(null)
    })
    navStates.put(id, NavRefreshState(navRefreshPending, navRefreshThread))

    val routingReadyCallback = (buildServer: bsp4j.BuildServer,
                           targets: List[bsp4j.BuildTarget],
                           sources: bsp4j.SourcesResult,
                           dependencySources: bsp4j.DependencySourcesResult) => {
      ctx.buildServer = buildServer
      ctx.sourceDirsByTarget = extractTargetSourceDirs(sources)
      ctx.dependencySourceUrisByTarget = extractTargetDependencySourceUris(dependencySources)
      val dirs = extractSourceDirs(sources)
      router.registerGroundTruth(id, dirs)
      navRefreshPending.set(targets.map(_.getId))
      LockSupport.unpark(navRefreshThread)
    }

    val compileCallback = (buildServer: bsp4j.BuildServer, targetIds: List[BuildTargetIdentifier]) =>
      ctx.buildServer = buildServer // keep server ref fresh
      navRefreshPending.set(targetIds)
      LockSupport.unpark(navRefreshThread)

    val targetChangedCallback = (
        buildServer: bsp4j.BuildServer,
        depSourcesResult: bsp4j.DependencySourcesResult,
        changedOrCreatedIds: List[BuildTargetIdentifier],
        deletedIds: List[BuildTargetIdentifier]) => {
      if depSourcesResult != null then
        ctx.dependencySourceUrisByTarget =
          ctx.dependencySourceUrisByTarget ++ extractTargetDependencySourceUris(depSourcesResult)
      for tid <- deletedIds do
        ctx.sourceDirsByTarget -= tid
        ctx.dependencySourceUrisByTarget -= tid
      val allAffected = changedOrCreatedIds ++ deletedIds
      if allAffected.nonEmpty then
        navRefreshPending.set(allAffected)
        LockSupport.unpark(navRefreshThread)
    }

    val vt = Thread.ofVirtual().start(() =>
      BspConnectionSupervisor.supervise(record, msgQueue, client, routingReadyCallback, compileCallback, targetChangedCallback)
    )
    logger.debug(s"Spawned supervisor thread for $id (${bspSpec.path})")
  } catch {
    case e: Exception =>
      logger.error(s"Failed to attach BSP connection for ${bspSpec.path}: ${e.getMessage}", e)
  }

  private def extractSourceDirs(sources: bsp4j.SourcesResult): List[String] =
    sources.getItems.asScala.toList.flatMap: item =>
      Option(item.getSources).toList.flatMap(_.asScala).collect {
        case si if si.getKind == bsp4j.SourceItemKind.DIRECTORY && !si.getGenerated =>
          si.getUri
      }

  /** Extract source dirs per target. Aligned with targetToSourceRoots in
    * BspConnectionSupervisor: accepts both DIRECTORY and FILE kinds, uses .map so
    * ALL targets appear in the map (even those with no source items). The nav
    * refresh guard depends on this map being populated. */
  private def extractTargetSourceDirs(sources: bsp4j.SourcesResult): Map[BuildTargetIdentifier, List[String]] =
    sources.getItems.asScala.toList.map { item =>
      val roots = Option(item.getSources).map(_.asScala.toList).getOrElse(Nil)
        .filterNot(_.getGenerated)
        .collect {
          case si if si.getKind == bsp4j.SourceItemKind.DIRECTORY => si.getUri
        }
      item.getTarget -> roots
    }.toMap

  private def extractTargetDependencySourceUris(dependencySources: bsp4j.DependencySourcesResult): Map[BuildTargetIdentifier, List[String]] =
    dependencySources.getItems.asScala.toList.flatMap { item =>
      Option(item.getTarget).map { target =>
        target ->
          Option(item.getSources).toList.flatMap(_.asScala).map(_.toString)
      }
    }.toMap

  /** Cleanly detach a connection: publish empty diagnostics, remove routing, kill process. */
  private def detachConnection(connId: BspConnectionId): Unit =
    Option(connections.get(connId)) match {
      case Some(ctx) =>
        logger.debug(s"Detaching connection $connId")
        // Publish empty diagnostics for all files owned by this connection
        val knownDiags = ctx.record.lastKnownDiagnostics.get()
        for uri <- knownDiags.keys do
          client.publishDiagnostics(
            new PublishDiagnosticsParams(uri, java.util.Collections.emptyList())
          )
        logger.debug(s"Cleared diagnostics for ${knownDiags.size} files")

        // Mark as Detached and send poison pill
        ctx.shuttingDown = true
        ctx.record.currentState = BspConnectionState.Detached
        ctx.queue.offer(ConnectionMessage.Shutdown)
        ctx.record.lastKnownDiagnostics.set(Map.empty)

        // Remove from routing and connections
        router.unregisterGroundTruth(connId)
        val bspDir = ctx.record.bspFile.get().path.toNIO.getParent
        router.unregisterBspRoot(bspDir, connId)
        ctx.navIndex.clear()
        Option(navStates.remove(connId)).foreach { navState =>
          navState.shuttingDown = true
          LockSupport.unpark(navState.thread)
        }
        connections.remove(connId)
        logger.debug(s"Connection $connId detached")
      case None =>
        logger.warn(s"Cannot detach unknown connection $connId")
    }

  /** Request a reload for a connection with a new spec. Re-applies overrides. */
  private def reloadConnection(connId: BspConnectionId, newSpec: BspConnectionSpec): Unit =
    applyOverrides(newSpec) match {
      case Some(merged) =>
        Option(connections.get(connId)) match
          case Some(ctx) =>
            logger.debug(s"Requesting reload for $connId")
            ctx.queue.offer(ConnectionMessage.ReloadRequested(merged))
          case None =>
            attachConnection(merged)
      case None =>
        detachConnection(connId)
    }

  /** Route a document URI to the owning connection's queue via longest-prefix matching. */
  def route(uri: String): Option[BlockingQueue[ConnectionMessage]] =
    router.route(uri) match {
      case Some(connId) =>
        Option(connections.get(connId)).map(_.queue) match
          case some @ Some(_) => some
          case None =>
            logger.warn(s"Connection $connId not found in connections map")
            None
      case None =>
        logger.debug(s"No BSP found for $uri")
        None
    }

  def definition(uri: String, position: Position): List[Location] = {
    val result = connectionForUri(uri).toList.flatMap(_.navIndex.definition(uri, position))
    logger.debug(s"definition uri=$uri line=${position.getLine} ch=${position.getCharacter} hits=${result.size}")
    result
  }

  def references(uri: String, position: Position): List[Location] = {
    val result = connectionForUri(uri).toList.flatMap(_.navIndex.references(uri, position))
    logger.debug(s"references uri=$uri line=${position.getLine} ch=${position.getCharacter} hits=${result.size}")
    result
  }

  def documentSymbols(uri: String): List[Either[SymbolInformation, DocumentSymbol]] = {
    val result = connectionForUri(uri).toList.flatMap(_.navIndex.documentSymbols(uri))
    logger.debug(s"documentSymbols uri=$uri hits=${result.size}")
    result
  }

  def trackDidOpen(uri: String): Unit = {
    openUris.add(uri)
  }

  /** Nudge a nav refresh for the connection owning uri. Refresh is incremental
    * (mtime+size diff), so running it on didOpen is cheap and catches compiles
    * done outside the editor (e.g. terminal build) without a file watcher.
    * Latest-wins coalescing on the nav thread absorbs rapid open bursts. */
  def requestNavRefresh(uri: String): Unit =
    router.route(uri).foreach { connId =>
      for
        ctx <- Option(connections.get(connId))
        state <- Option(navStates.get(connId))
      do
        val targets = (ctx.sourceDirsByTarget.keySet ++ ctx.dependencySourceUrisByTarget.keySet).toList
        if targets.nonEmpty then
          state.pending.set(targets)
          LockSupport.unpark(state.thread)
    }

  def trackDidClose(uri: String): Unit = {
    openUris.remove(uri)
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
        BspDiscovery.parseSingleSpec(p).foreach: spec =>
          val connId = BspConnectionId(spec.path.toString)
          val currentContent = Option(connections.get(connId)).map(_.record.bspFile.get().content)
          if currentContent.contains(spec.content) then
            logger.debug(s"BSP config rewritten but content unchanged, skipping reload: $p")
          else
            logger.info(s"BSP config modified: $p")
            reloadConnection(connId, spec)

      if hadTopologyChange then
        replayOpenAndErroredUris()
    }
  }

  /** Re-dispatch compile triggers for currently open and currently errored files after BSP topology changes. */
  private def replayOpenAndErroredUris(): Unit = {
    val candidateUris =
      openUris.asScala.toSet ++ connections.values().asScala.flatMap(_.record.lastKnownDiagnostics.get().keys)

    for uri <- candidateUris do
      router.route(uri).flatMap(id => Option(connections.get(id))).foreach: ctx =>
        ctx.queue.offer(ConnectionMessage.RecheckUri(uri))
  }

  // ---- Lifecycle ----

  private def startStatusWriter(): Unit = {
    val thread = Thread.ofVirtual().start(() => {
      while !shuttingDown.get() do
        try
          writeStatus()
          Thread.sleep(1000)
        catch case _: InterruptedException => ()
    })
    thread.setName("basamake-status-writer")
  }

  private def writeStatus(): Unit = try {
    val connSnapshots = connections.entrySet().asScala.map(e => (e.getKey, e.getValue)).toList
    val status = BasamakeStatus(
      bspConnections = connSnapshots.map { case (id, ctx) =>
        val bspFilePath = ctx.record.bspFile.get().path
        val relPath = try bspFilePath.relativeTo(workspaceRoot).toString
          catch case _: Exception => bspFilePath.toString
        BspConnectionStatus(
          configPath = relPath,
          state = ctx.record.currentState.toString,
          targets = ctx.sourceDirsByTarget.keys.toList.sortBy(_.getUri).map { targetId =>
            BspTargetStatus(
              id = targetId.getUri,
              semanticdbEnabled = ctx.navIndex.getTargetSemanticdbFlags.get(targetId),
              bestEffortEnabled = ctx.navIndex.getTargetBestEffortFlags.get(targetId)
            )
          }
        )
      }
    )
    val statusDir = workspaceRoot / ".basamake"
    os.makeDir.all(statusDir)
    os.write.over(statusDir / "status.json", status.toJson(spaces = 2))
  } catch case e: Exception =>
    logger.warn(s"Failed to write status.json: ${e.getMessage}")

  /** Graceful shutdown: stop watcher, detach connections, kill descendant processes. */
  def shutdown(): Unit = {
    if !shuttingDown.compareAndSet(false, true) then return
    logger.info("shutdown started...")

    // Stop watcher first so no new connections spawned during shutdown
    if watcher != null then watcher.stop()
    debounceLock.synchronized {
      pendingDebounceTask.foreach(_.cancel())
      pendingDebounceTask = None
      pendingBspChanges = Set.empty
    }
    debounceTimer.cancel()

    connections.keySet().asScala.toList.foreach: connId =>
      detachConnection(connId)
    logger.debug("All connections detached")

    // Kill any remaining descendant processes
    val killed = ProcessUtils.terminateProcessHandleTree(java.lang.ProcessHandle.current())
    if killed > 0 then
      logger.info(s"Killed $killed descendant process node(s) during shutdown")
  }

  private def connectionForUri(uri: String): Option[BspConnectionContext] =
    router.route(uri).flatMap(id => Option(connections.get(id)))

}

object BuildServerManager {
  private[bsp] def classifyBspChanges(
      known: Set[os.Path],
      current: Set[os.Path],
      changed: Set[os.Path]
  ): (Set[os.Path], Set[os.Path], Set[os.Path]) =
    val newFiles = current -- known
    val deletedFiles = known -- current
    val modifiedFiles = known.intersect(current).intersect(changed)
    (newFiles, deletedFiles, modifiedFiles)
}


private case class NavRefreshState(
    pending: java.util.concurrent.atomic.AtomicReference[List[BuildTargetIdentifier]],
    thread: Thread,
    @volatile var shuttingDown: Boolean = false
)

private case class BspConnectionContext(
    record: DurableRecord,
    queue: BlockingQueue[ConnectionMessage],
    navIndex: NavigationIndex,
    /** target → list of source directories */
    var sourceDirsByTarget: Map[BuildTargetIdentifier, List[String]] = Map.empty,
    /** target → list of dependency source JARs */
    var dependencySourceUrisByTarget: Map[BuildTargetIdentifier, List[String]] = Map.empty,
    @volatile var buildServer: bsp4j.BuildServer = null,
    @volatile var shuttingDown: Boolean = false
)
