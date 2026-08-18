package ba.sake.basamake.bsp

import java.util.concurrent.ConcurrentHashMap
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
  * it manipulates no process state directly (connection.shutdown() only). */
class BspManager (
    workspaceRoot: os.Path,
    workspaceIndex: WorkspaceIndex,
    depsSymbolTable: IndexedSymbolTable,
    config: BasamakeConfig
) extends BspEvents with StrictLogging {

  private val connections = new ConcurrentHashMap[BspConnectionId, BspConnection]()
  private val router = new BspRouter
  private var client: Option[LanguageClient] = None
  /** BSP compile tasks → window/workDoneProgress (spinner). Falls back to
    * logMessage when the client lacks the progress capability. */
  private val compileProgress = new CompileProgressReporter
  private var knownBspFiles: Set[os.Path] = Set.empty

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
    }
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
      try depsSymbolTable.registerTarget(deps)
      catch { case e: Exception => logger.warn(s"registerTarget failed for $srcRoot: ${e.getMessage}", e) }
    }
    val discovered = BspDiscovery.discover(workspaceRoot, watchFilter.engine)
    knownBspFiles = discovered.map(_.path).toSet
    for (spec <- discovered) applyOverrides(spec).foreach(attachConnection)

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
          if (!firstUriByConn.contains(conn)) firstUriByConn(conn) = uri
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

  // ---- BspEvents: dependency sources — register target deps, paths only ----
  override def onDependencySources(depsByTarget: Map[BuildTargetIdentifier, List[os.Path]]): Unit = {
    // Register per target — paths only. Nothing is indexed here; lookups index
    // exactly the jars they need, inline (see IndexedSymbolTable).
    depsByTarget.foreach { case (tid, paths) =>
      try depsSymbolTable.registerTarget(paths)
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

  override def onConnectionSucceeded(spec: BspConnectionSpec, targetCount: Int): Unit =
    logToClient(org.eclipse.lsp4j.MessageType.Info,
      s"Connected to ${spec.content.name} — $targetCount build target(s)")

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
    if (!shuttingDown.compareAndSet(false, true)) return
    logger.info("BspManager shutdown started...")
    bspWatcher.stop()
    compileProgress.endAllConnections()
    connections.values().asScala.foreach(_.shutdown())
    connections.clear()
    val killed = ProcessUtils.terminateProcessHandleTree(java.lang.ProcessHandle.current())
    if (killed > 0) logger.info(s"Killed $killed descendant process node(s) during shutdown")
  }

  private def handleBspChanges(changed: Set[os.Path]): Unit = {
    stateLock.lock()
    try {
      val current = BspDiscovery.discover(workspaceRoot, watchFilter.engine).map(_.path).toSet
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
                attachConnection(spec)
              } else {
                logger.debug(s"BSP config modified: $p — content unchanged, skip re-attach")
              }
            case None => attachConnection(spec)
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
    }
  }

  private def attachConnection(spec: BspConnectionSpec): Unit = {
    val specWithRoot = spec.copy(workspaceRoot = workspaceRoot)
    val id = BspConnectionId(specWithRoot.path.toString)
    val conn = BspConnection(specWithRoot, this)
    connections.put(id, conn)
    val bspDir = specWithRoot.path.toNIO.getParent
    router.registerBspRoot(bspDir, Set(id))
  }

  private def applyOverrides(spec: BspConnectionSpec): Option[BspConnectionSpec] = {
    val relPath = try spec.path.relativeTo(workspaceRoot).toString
      catch { case _: Exception => spec.path.toString }
    config.bspOverrides.find(_.bspFile == relPath) match {
      case Some(ov) =>
        if (ov.enabled) {
          val merged = spec.copy(
            compileTimeoutSec = ov.compileTimeoutSec.getOrElse(spec.compileTimeoutSec),
            handshakeTimeoutSec = ov.handshakeTimeoutSec.getOrElse(spec.handshakeTimeoutSec)
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
