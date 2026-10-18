package ba.sake.basamake.bsp

import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, DidChangeBuildTarget, PublishDiagnosticsParams, StatusCode, TaskFinishParams, TaskProgressParams, TaskStartParams}
import org.eclipse.lsp4j.{Diagnostic, DiagnosticSeverity, Position, PublishDiagnosticsParams => LspPublishDiagnosticsParams, Range}
import org.eclipse.lsp4j.services.LanguageClient
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.config.{BasamakeConfig, BspOverride}
import ba.sake.basamake.util.ProcessUtils
import ba.sake.basamake.index.indexing.{WorkspaceIndex, SemanticdbDirs, IndexedSymbolTable}

/** Orchestrator of the workspace's BSP connections.
  *
  * Owns: connections map, BspRouter, diagnostics state, compile progress.
  * Coordinates WatchFilter, BspWatcher, and per-connection BspConnections —
  * Shutdown also sweeps JVM descendants unless disabled for shared-JVM use. */
class BspManager (
    workspaceRoot: os.Path,
    workspaceIndex: WorkspaceIndex,
    depsSymbolTable: IndexedSymbolTable,
    config: BasamakeConfig,
    killJvmDescendantsOnShutdown: Boolean = true
) extends BspEvents with StrictLogging {

  private val connections = new ConcurrentHashMap[BspConnectionId, BspConnection]()
  private val importOffered = ConcurrentHashMap.newKeySet[BspConnection]()
  private val imported = ConcurrentHashMap.newKeySet[BspConnection]()
  private val importCompileUris = new ConcurrentHashMap[BspConnection, java.util.Set[String]]()
  private val pendingImports = new ConcurrentHashMap[BspConnection, CompletableFuture[org.eclipse.lsp4j.MessageActionItem]]()
  private val importExecutor = Executors.newVirtualThreadPerTaskExecutor()
  private val router = new BspRouter
  private var client: Option[LanguageClient] = None
  /** BSP compile tasks → window/workDoneProgress (spinner). Falls back to
    * logMessage when the client lacks the progress capability. */
  private val compileProgress = new CompileProgressReporter
  private var knownBspFiles: Set[os.Path] = Set.empty
  @volatile private var configState: BasamakeConfig = config

  private val watchFilter = new WatchFilter(workspaceRoot, config)
  private val bspWatcher = new BspWatcher(
    workspaceRoot,
    watchFilter.isIgnored,
    onGitignoreChanged = () => {
      watchFilter.reload()
      workspaceIndex.reloadIgnores()
    },
    onBspFilesChanged = batch => {
      router.invalidateBootstrapCache()
      // never let a failing batch escape the watcher's executor thread
      try handleBspChanges(batch)
      catch { case e: Exception => logger.error(s"Failed to process .bsp changes: ${e.getMessage}", e) }
    },
    onConfigChanged = () => {
      stateLock.lock()
      try {
        configState = BasamakeConfig.load(workspaceRoot)
        connections.values().asScala.foreach { conn =>
          val enabled = configState.bspOverrides.find(_.bspFile == relativeBspPath(conn.spec.path))
            .flatMap(_.autoCompile).getOrElse(true)
          conn.setAutoCompile(enabled)
        }
      } finally { stateLock.unlock() }
    },
    onSemanticdbChanged = roots => onAfterCompile(roots)
  )

  // Diagnostics: uri → (targetId → List[Diagnostic])
  private val diagnostics = mutable.Map.empty[String, Map[BuildTargetIdentifier, List[Diagnostic]]]
  // Diagnostic ownership: uri → connection that last published diagnostics for it.
  // Lets detachConnection clear ONLY the detached connection's diagnostics (a
  // multi-BSP workspace keeps the other servers' diagnostics intact).
  private val diagnosticsOwners = mutable.Map.empty[String, BspConnectionId]
  // guards diagnostics, diagnosticsOwners, knownBspFiles
  private val stateLock = new java.util.concurrent.locks.ReentrantLock()

  /** Warm-start dependency sources from .basamake/bsp data.json files: source root →
    * source jars of that target. Used for lookups + indexing before the first BSP
    * handshake of a session; live handshake data takes precedence once available. */
  @volatile private var warmDepsBySourceRoot: List[(os.Path, List[os.Path])] = Nil

  private val shuttingDown = new AtomicBoolean(false)

  def initialize(lspClient: LanguageClient, warmDeps: List[(os.Path, List[os.Path])] = Nil, workDoneProgress: Boolean = false): Unit = {
    client = Some(lspClient)
    compileProgress.setClient(lspClient)
    compileProgress.setEnabled(workDoneProgress)
    warmDepsBySourceRoot = warmDeps
    // Register warm-start dependency sources: cached jars become routable NOW,
    // uncached jars stay unindexed until a file of that target is opened.
    warmDeps.foreach { case (srcRoot, deps) =>
      try depsSymbolTable.registerTarget(srcRoot.toString, deps)
      catch { case e: Exception => logger.warn(s"registerTarget failed for $srcRoot: ${e.getMessage}", e) }
    }
    val discovered = BspDiscovery.discover(workspaceRoot, watchFilter.engine)
    configState = BasamakeConfig.ensureBspDefaults(workspaceRoot, discovered.map(_.path).map(relativeBspPath))
    knownBspFiles = discovered.map(_.path).toSet
    for (spec <- discovered) applyOverrides(spec).foreach(attachConnection)

    refreshSemanticdbWatches()
    bspWatcher.start()
  }

  // ---- poke: the one entry point from LSP handlers ----
  // makes sure BSP connection is alive, respawns if needed, and (when requested)
  // triggers a debounced, per-target-coalesced compile.
  def poke(uri: String, compile: Boolean): Unit = {
    if (shuttingDown.get()) return
    val connOpt = router.route(uri).flatMap(id => Option(connections.get(id)))
    connOpt match {
      case Some(conn) =>
        if (!imported.contains(conn)) {
          if (compile) importCompileUris.computeIfAbsent(conn, _ => ConcurrentHashMap.newKeySet[String]()).add(uri)
          offerImport(conn, uri)
          return
        }
        try {
          if (compile) {
            logger.info(s"Compile trigger for $uri → ${conn.spec.content.name}")
            conn.requestCompile(uri)
          } else {
            conn.poke()
          }
        } catch {
          case e: Exception => logger.warn(s"poke failed for $uri: ${e.getMessage}", e)
        }
      case None =>
        logger.debug(s"No BSP connection for $uri — poke is a no-op")
    }
  }

  private def offerImport(conn: BspConnection, uri: String): Unit = {
    if (!importOffered.add(conn)) return
    val compileOnChange = new org.eclipse.lsp4j.MessageActionItem("Compile on change")
    val externalCompile = new org.eclipse.lsp4j.MessageActionItem("Don't compile on change")
    val request = new org.eclipse.lsp4j.ShowMessageRequestParams()
    request.setType(org.eclipse.lsp4j.MessageType.Info)
    val bspFile = relativeBspPath(conn.spec.path)
    request.setMessage(s"Import ${conn.spec.content.name} build ($bspFile)? Choose when Basamake compiles.")
    request.setActions(java.util.List.of(compileOnChange, externalCompile))
    client.foreach { c =>
      stateLock.lock()
      try {
        if (!shuttingDown.get() && !imported.contains(conn) && connections.get(BspConnectionId(conn.spec.path.toString)) == conn) {
          val response = c.showMessageRequest(request)
          pendingImports.put(conn, response)
          response.thenAcceptAsync(choice => {
            if (choice != null) {
              val title = choice.getTitle
              if (title == compileOnChange.getTitle || title == externalCompile.getTitle) {
                selectImportMode(conn, title == compileOnChange.getTitle)
              }
            }
            resumeImport(conn, uri)
          }, importExecutor).whenComplete((_, error) => {
            pendingImports.remove(conn)
            if (error != null && !shuttingDown.get()) logger.warn(s"BSP import failed for $bspFile: ${error.getMessage}")
          })
        }
      } catch {
        case e: Exception => logger.warn(s"Failed to offer BSP import for $bspFile: ${e.getMessage}", e)
      } finally { stateLock.unlock() }
    }
  }

  private def resumeImport(conn: BspConnection, uri: String, compileIfEmpty: Boolean = false): Unit = {
    val compileUris = Option(importCompileUris.remove(conn)).toList.flatMap(_.asScala)
    if (imported.contains(conn)) {
      if (compileUris.isEmpty) poke(uri, compile = compileIfEmpty)
      else compileUris.foreach(poke(_, compile = true))
    }
  }

  private def selectImportMode(conn: BspConnection, autoCompile: Boolean): Unit = {
    stateLock.lock()
    try {
      val current = connections.get(BspConnectionId(conn.spec.path.toString))
      if (!shuttingDown.get() && current == conn && !imported.contains(conn)) {
        val bspFile = relativeBspPath(conn.spec.path)
        if (BasamakeConfig.setBspAutoCompile(workspaceRoot, bspFile, autoCompile)) {
          configState = BasamakeConfig.load(workspaceRoot)
          conn.setAutoCompile(autoCompile)
          importOffered.add(conn)
          imported.add(conn)
        } else {
          client.foreach(_.showMessage(new org.eclipse.lsp4j.MessageParams(org.eclipse.lsp4j.MessageType.Error,
            "Could not save the BSP import mode. Check .basamake/config.json and reload the workspace.")))
        }
      }
    } finally { stateLock.unlock() }
  }

  /** The install prompt already selected a mode. Discover and approve the new
    * connection atomically so editor events cannot offer a second prompt. */
  def importInstalledBuild(uri: String, autoCompile: Boolean): Boolean = {
    stateLock.lock()
    val conn = try {
      rescanBspConfigs()
      val owning = router.route(uri).flatMap(id => Option(connections.get(id)))
      owning.foreach { connection =>
        Option(pendingImports.remove(connection)).foreach(_.cancel(true))
        selectImportMode(connection, autoCompile)
      }
      owning
    } finally { stateLock.unlock() }
    conn.foreach(resumeImport(_, uri, compileIfEmpty = true))
    conn.isDefined
  }

  /** Whether a discovered BSP configuration owns this URI. This deliberately
    * says nothing about process liveness: a configured connection that is
    * temporarily down should reconnect, not trigger an installation offer. */
  def handles(uri: String): Boolean =
    router.route(uri).exists(id => connections.containsKey(id))

  /** Config is refreshed by BspWatcher, so a manual blacklist edit applies to
    * the next LSP file-open without restarting the language server. */
  def isInstallOfferBlacklisted(markerFile: String): Boolean =
    configState.offerInstallBlacklist.contains(markerFile)

  /** Persist a dismissal and update the in-memory view immediately; the file
    * watcher handles subsequent manual edits to the same setting. */
  def blacklistInstallOffer(markerFile: String): Boolean = {
    val written = BasamakeConfig.blacklistInstallOffer(workspaceRoot, markerFile)
    if (written) configState = BasamakeConfig.load(workspaceRoot)
    written
  }

  /** Re-run discovery after an external tool has created BSP config files.
    * The watcher normally notices this too, but an explicit rescan makes the
    * install action deterministic even when a client has no file watcher. */
  private def rescanBspConfigs(): Unit = {
    if (!shuttingDown.get()) {
      router.invalidateBootstrapCache()
      try handleBspChanges(Set.empty)
      catch { case e: Exception => logger.warn(s"Failed to rescan BSP configs: ${e.getMessage}", e) }
    }
  }

  /** Clear diagnostics for a specific URI (e.g., when a file is closed/renamed).
    * Always publishes an empty list — VS Code keeps showing stale diagnostics
    * (e.g. published by a previous server session) unless we explicitly clear them. */
  def clearDiagnostics(uri: String): Unit = {
    stateLock.lock()
    val removed =
      try {
        diagnosticsOwners.remove(uri)
        diagnostics.remove(uri)
      } finally {
        stateLock.unlock()
      }
    logger.debug(s"clearDiagnostics($uri): entry existed=${removed.isDefined}")
    client.foreach(_.publishDiagnostics(
      new LspPublishDiagnosticsParams(uri, java.util.Collections.emptyList())))
  }

  /** React to filesystem create/delete/change events (workspace/didChangeWatchedFiles).
    * Deleted files: clear diagnostics immediately. Source events: trigger a compile,
    * once per connection (the client debounces watcher events into a single batch;
    * the per-target debounce coalesces bursts — including didSave + its watcher
    * change event — into one build). Catches renames and edits done outside VS
    * Code's file-operation flow (terminal mv, git checkout, sed), which never
    * send didRenameFiles or didSave. */
  def onWatchedFilesChanged(created: List[String], deleted: List[String], changed: List[String] = Nil): Unit = {
    deleted.foreach(clearDiagnostics)
    val uris = (created ++ deleted ++ changed).filter(isWatchedSource)
    if (uris.isEmpty) return
    logger.info(s"watcher: ${created.size} created, ${deleted.size} deleted, ${changed.size} changed source event(s) — scheduling compile")
    Thread.ofVirtual().start(() => {
      val firstUriByConn = mutable.Map.empty[BspConnection, String]
      uris.foreach { uri =>
        router.route(uri).flatMap(id => Option(connections.get(id))).foreach { conn =>
          if (imported.contains(conn) && !firstUriByConn.contains(conn)) firstUriByConn(conn) = uri
        }
      }
      firstUriByConn.foreach { case (conn, uri) =>
        try conn.requestCompile(uri)
        catch { case e: Exception => logger.warn(s"watcher-triggered compile failed for $uri: ${e.getMessage}") }
      }
    })
  }

  private def isWatchedSource(uri: String): Boolean = {
    val lower = uri.toLowerCase(java.util.Locale.ROOT)
    lower.endsWith(".scala") || lower.endsWith(".java") || lower.endsWith(".sbt")
  }

  // ---- BspEvents: diagnostics ----
  override def onDiagnostics(params: PublishDiagnosticsParams, connId: BspConnectionId): Unit = {
    val uri = params.getTextDocument.getUri
    stateLock.lock()
    try { diagnosticsOwners(uri) = connId }
    finally { stateLock.unlock() }
    val targetId = Option(params.getBuildTarget).getOrElse(new BuildTargetIdentifier(""))
    val newDiags = Option(params.getDiagnostics).getOrElse(java.util.Collections.emptyList())
      .asScala.map(bspDiagToLsp).toList
    logger.debug(s"onDiagnostics($uri): ${newDiags.size} diagnostic(s), reset=${params.getReset}")

    stateLock.lock()
    val perTarget =
      try {
        val current = diagnostics.getOrElse(uri, Map.empty)
        val updated =
          if (params.getReset) current + (targetId -> newDiags)
          else current + (targetId -> (current.getOrElse(targetId, Nil) ++ newDiags))
        diagnostics(uri) = updated
        updated
      } finally {
        stateLock.unlock()
      }
    val union = perTarget.values.flatten.toList.asJava
    client.foreach(_.publishDiagnostics(new LspPublishDiagnosticsParams(uri, union)))
  }

  // ---- BspEvents: after-compile → WorkspaceIndex.invalidate ----
  override def onAfterCompile(roots: List[SemanticdbDirs]): Unit =
    try workspaceIndex.invalidate(roots)
    catch { case e: Exception => logger.warn(s"WorkspaceIndex.invalidate failed: ${e.getMessage}", e) }

  // ---- BspEvents: dependency sources — register target deps, target-scoped ----
  override def onDependencySources(depsByTarget: Map[BuildTargetIdentifier, List[os.Path]]): Unit = {
    // Register per target — the target id scopes the jar set for cross-jar
    // candidate derivation (a target's list IS its resolved classpath).
    // Nothing is indexed here; lookups index exactly the jars they need, inline
    // (see IndexedSymbolTable).
    depsByTarget.foreach { case (tid, paths) =>
      try depsSymbolTable.registerTarget(tid.getUri, paths)
      catch { case e: Exception => logger.warn(s"registerTarget failed for $tid: ${e.getMessage}", e) }
    }
  }

  /** Dependency source jars relevant to `uri`: live per-target handshake data when
    * the owning connection is alive, else the data.json warm-start mapping, else
    * (for dep sources opened via goto-def, which live outside the workspace and
    * match neither) the jar owning the extracted file. */
  def dependencySourcesFor(uri: String): List[os.Path] = {
    val live = router.route(uri).flatMap(id => Option(connections.get(id))) match {
      case Some(conn) => conn.dependencySourcesFor(uri)
      case None       => Nil
    }
    if (live.nonEmpty) live
    else warmDepsBySourceRoot.collectFirst {
      case (root, deps) if uriPathUnderRoot(uri, root) => deps
    }.getOrElse(depFileCandidates(uri))
  }

  /** Exact live compiler inputs for the build target owning `uri`. */
  def scalaPresentationTargetFor(uri: String): Option[ScalaPresentationTarget] =
    router.route(uri).flatMap(id => Option(connections.get(id))).flatMap(_.scalaPresentationTargetFor(uri))

  /** Candidates for a dep/JDK source file (`~/.cache/basamake/deps/<fp>/src/...`):
    * the jar the file was extracted from (owning jar). */
  private def depFileCandidates(uri: String): List[os.Path] = {
    val path = try os.Path(java.net.URI.create(uri)) catch { case _: Exception => return Nil }
    depsSymbolTable.candidatesForPath(path)
  }

  private def uriPathUnderRoot(uri: String, root: os.Path): Boolean = {
    val path = try os.Path(java.net.URI.create(uri)) catch { case _: Exception => return false }
    path.startsWith(root)
  }

  override def onTargetChanged(params: DidChangeBuildTarget, connId: BspConnectionId): Unit = {
    logger.debug(s"buildTargetDidChange: ${params.getChanges.size()} events")
    Option(connections.get(connId)).foreach { conn =>
      conn.refreshDependencySources(BspConnection.changedTargetIds(params))
    }
  }

  override def onShowMessage(params: org.eclipse.lsp4j.MessageParams): Unit =
    client.foreach(_.showMessage(params))

  // ---- BSP task notifications → LSP logMessage ----
  override def onTaskStart(params: TaskStartParams, connId: BspConnectionId): Unit = {
    compileProgress.onTaskStart(connId, params)
    val msg = Option(params.getMessage).filter(_.nonEmpty)
    msg.foreach { m =>
      logToClient(org.eclipse.lsp4j.MessageType.Info, s"Compiling: $m")
    }
  }

  override def onTaskProgress(params: TaskProgressParams, connId: BspConnectionId): Unit =
    compileProgress.onTaskProgress(connId, params)

  override def onTaskFinish(params: TaskFinishParams, connId: BspConnectionId): Unit = {
    compileProgress.onTaskFinish(connId, params)
    val msg = Option(params.getMessage).filter(_.nonEmpty)
    val (msgType, text) = params.getStatus match {
      case StatusCode.ERROR =>
        (org.eclipse.lsp4j.MessageType.Error,
         msg.fold("Compilation failed")(m => s"Compilation failed: $m"))
      case StatusCode.CANCELLED =>
        (org.eclipse.lsp4j.MessageType.Warning, "Compilation cancelled")
      case _ =>
        (org.eclipse.lsp4j.MessageType.Info,
         msg.fold("Compilation succeeded")(m => m))
    }
    logToClient(msgType, text)
  }

  // ---- Connection lifecycle → LSP logMessage ----
  override def onConnectionStarted(spec: BspConnectionSpec): Unit =
    logToClient(org.eclipse.lsp4j.MessageType.Info,
      s"Connecting to build server: ${spec.content.name}")

  override def onConnectionSucceeded(spec: BspConnectionSpec, targetCount: Int): Unit = {
    refreshSemanticdbWatches()
    logToClient(org.eclipse.lsp4j.MessageType.Info,
      s"Connected to ${spec.content.name} — $targetCount build target(s)")
  }

  private def refreshSemanticdbWatches(): Unit = {
    if (!shuttingDown.get()) {
      try bspWatcher.setSemanticdbRoots(connections.values().asScala.toList.flatMap(_.semanticdbRoots).distinct)
      catch { case e: Exception => logger.warn(s"Failed to register SemanticDB watches: ${e.getMessage}", e) }
    }
  }

  override def onConnectionFailed(spec: BspConnectionSpec, error: String): Unit =
    logToClient(org.eclipse.lsp4j.MessageType.Error,
      s"Failed to connect to ${spec.content.name}: $error")

  private def logToClient(msgType: org.eclipse.lsp4j.MessageType, msg: String): Unit = {
    client.foreach { c =>
      c.logMessage(new org.eclipse.lsp4j.MessageParams(msgType, msg))
      logger.debug(s"→ LSP client: [$msgType] $msg")
    }
  }

  // ---- Lifecycle ----
  def shutdown(): Unit = {
    stateLock.lock()
    try {
      if (!shuttingDown.compareAndSet(false, true)) return
      logger.info("BspManager shutdown started...")
      pendingImports.values().asScala.foreach(_.cancel(true))
      pendingImports.clear()
      importExecutor.shutdownNow()
    } finally { stateLock.unlock() }
    try importExecutor.awaitTermination(5, TimeUnit.SECONDS)
    catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    bspWatcher.stop()
    compileProgress.endAllConnections()
    connections.values().asScala.foreach(_.shutdown())
    connections.clear()
    if (killJvmDescendantsOnShutdown) {
      val killed = ProcessUtils.terminateProcessHandleTree(java.lang.ProcessHandle.current())
      if (killed > 0) logger.info(s"Killed $killed descendant process node(s) during shutdown")
    }
  }

  private def handleBspChanges(changed: Set[os.Path]): Unit = {
    stateLock.lock()
    try {
      val current = BspDiscovery.discover(workspaceRoot, watchFilter.engine).map(_.path).toSet
      configState = BasamakeConfig.ensureBspDefaults(workspaceRoot, current.toList.map(relativeBspPath))
      val (newFiles, deletedFiles, modifiedFiles) =
        BspManager.classifyBspChanges(knownBspFiles, current, changed)

      for (p <- deletedFiles) {
      try {
        logger.info(s"BSP config deleted: $p")
        knownBspFiles -= p
        detachConnection(BspConnectionId(p.toString))
      } catch {
        case e: Exception => logger.warn(s"Failed to process deleted BSP config $p: ${e.getMessage}", e)
      }
    }
      for (p <- newFiles) {
      try {
        logger.info(s"New BSP config detected: $p")
        knownBspFiles += p
        BspDiscovery.parseSingleSpec(p, workspaceRoot).foreach(spec => applyOverrides(spec).foreach(attachConnection))
      } catch {
        case e: Exception => logger.warn(s"Failed to process new BSP config $p: ${e.getMessage}", e)
      }
    }
      for (p <- modifiedFiles) {
      try {
        BspDiscovery.parseSingleSpec(p, workspaceRoot).foreach { spec =>
          val connId = BspConnectionId(spec.path.toString)
          Option(connections.get(connId)) match {
            case Some(existingConn) =>
              val sameContent = existingConn.spec.content.name == spec.content.name
                && existingConn.spec.content.argv == spec.content.argv
              if (!sameContent) {
                logger.info(s"BSP config modified: $p — content changed, detach + re-attach")
                detachConnection(connId)
                applyOverrides(spec).foreach(attachConnection)
              } else {
                logger.debug(s"BSP config modified: $p — content unchanged, skip re-attach")
              }
            case None => applyOverrides(spec).foreach(attachConnection)
          }
        }
      } catch {
        case e: Exception => logger.warn(s"Failed to process modified BSP config $p: ${e.getMessage}", e)
      }
    }
    } finally {
      stateLock.unlock()
    }
  }

  private[bsp] def detachConnection(connId: BspConnectionId): Unit = {
    Option(connections.remove(connId)).foreach { conn =>
      imported.remove(conn)
      importCompileUris.remove(conn)
      importOffered.remove(conn)
      Option(pendingImports.remove(conn)).foreach(_.cancel(true))
      // Clear ONLY the URIs this connection published diagnostics for. Other
      // connections' diagnostics (same or different URIs) stay untouched.
      stateLock.lock()
      val ownedUris =
        try {
          val uris = diagnosticsOwners.collect { case (uri, owner) if owner == connId => uri }.toList
          uris.foreach { uri =>
            diagnosticsOwners.remove(uri)
            diagnostics.remove(uri)
          }
          uris
        } finally {
          stateLock.unlock()
        }
      ownedUris.foreach { uri =>
        client.foreach(_.publishDiagnostics(new LspPublishDiagnosticsParams(uri, java.util.Collections.emptyList())))
      }
      // a killed server never sends taskFinish — end its progress tokens now
      compileProgress.endAll(connId)
      val bspDir = conn.spec.path.toNIO.getParent
      router.unregisterBspRoot(bspDir, connId)
      conn.shutdown()
      refreshSemanticdbWatches()
    }
  }

  private def attachConnection(spec: BspConnectionSpec): Unit = {
    val specWithRoot = spec.copy(workspaceRoot = workspaceRoot)
    val id = BspConnectionId(specWithRoot.path.toString)
    val conn = BspConnection(specWithRoot, this)
    connections.put(id, conn)
    val bspDir = specWithRoot.path.toNIO.getParent
    router.registerBspRoot(bspDir, Set(id))
    refreshSemanticdbWatches()
  }

  private def applyOverrides(spec: BspConnectionSpec): Option[BspConnectionSpec] = {
    val relPath = try spec.path.relativeTo(workspaceRoot).toString
      catch { case _: Exception => spec.path.toString }
    configState.bspOverrides.find(_.bspFile == relPath) match {
      case Some(ov) =>
        if (ov.enabled) {
          val merged = spec.copy(
            compileTimeoutSec = ov.compileTimeoutSec.getOrElse(spec.compileTimeoutSec),
            handshakeTimeoutSec = ov.handshakeTimeoutSec.getOrElse(spec.handshakeTimeoutSec),
            autoCompile = ov.autoCompile.getOrElse(true)
          )
          logger.debug(s"Override applied for $relPath: compileTimeoutSec=${merged.compileTimeoutSec}, handshakeTimeoutSec=${merged.handshakeTimeoutSec}")
          Some(merged)
        } else {
          logger.info(s"BSP connection $relPath disabled by override")
          None
        }
      case None => Some(spec)
    }
  }

  private def relativeBspPath(path: os.Path): String =
    try path.relativeTo(workspaceRoot).toString
    catch { case _: Exception => path.toString }

  private def bspDiagToLsp(bsp: ch.epfl.scala.bsp4j.Diagnostic): Diagnostic = {
    val diag = new Diagnostic()
    diag.setRange(new Range(
      new Position(bsp.getRange.getStart.getLine, bsp.getRange.getStart.getCharacter),
      new Position(bsp.getRange.getEnd.getLine, bsp.getRange.getEnd.getCharacter)
    ))
    diag.setSeverity(Option(bsp.getSeverity).map(convertSeverity).getOrElse(DiagnosticSeverity.Error))
    diag.setMessage(stripAnsi(Option(bsp.getMessage).getOrElse("")))
    Option(bsp.getSource).foreach(diag.setSource)
    diag
  }

  private def convertSeverity(s: ch.epfl.scala.bsp4j.DiagnosticSeverity): DiagnosticSeverity = {
    import ch.epfl.scala.bsp4j.DiagnosticSeverity as B
    if (s == B.ERROR) DiagnosticSeverity.Error
    else if (s == B.WARNING) DiagnosticSeverity.Warning
    else if (s == B.INFORMATION) DiagnosticSeverity.Information
    else if (s == B.HINT) DiagnosticSeverity.Hint
    else DiagnosticSeverity.Error
  }

  private val AnsiPattern = "\u001b\\[[0-9;]*m".r
  private def stripAnsi(s: String): String = AnsiPattern.replaceAllIn(s, "")
}

object BspManager {
  def apply(workspaceRoot: os.Path, workspaceIndex: WorkspaceIndex, depsSymbolTable: IndexedSymbolTable, config: BasamakeConfig): BspManager =
    new BspManager(workspaceRoot, workspaceIndex, depsSymbolTable, config)

  private def classifyBspChanges(
      known: Set[os.Path], current: Set[os.Path], changed: Set[os.Path]
  ): (Set[os.Path], Set[os.Path], Set[os.Path]) = {
    val newFiles = current -- known
    val deletedFiles = known -- current
    val modifiedFiles = known.intersect(current).intersect(changed)
    (newFiles, deletedFiles, modifiedFiles)
  }
}
