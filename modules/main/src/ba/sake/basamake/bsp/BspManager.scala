package ba.sake.basamake.bsp

import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{Timer, TimerTask}
import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, DidChangeBuildTarget, PublishDiagnosticsParams, StatusCode, TaskFinishParams, TaskStartParams}
import org.eclipse.lsp4j.{Diagnostic, DiagnosticSeverity, Position, PublishDiagnosticsParams => LspPublishDiagnosticsParams, Range}
import org.eclipse.lsp4j.services.LanguageClient
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.config.{BasamakeConfig, BspOverride}
import ba.sake.basamake.watcher.FileChangeWatcher
import ba.sake.basamake.util.ProcessUtils
import ba.sake.basamake.navigation.indexing.{WorkspaceIndex, SemanticdbDirs, GitIgnoreEngine}

class BspManager private (
    workspaceRoot: os.Path,
    workspaceIndex: WorkspaceIndex
) extends BspEventSink with BspAfterCompileSink with StrictLogging {

  private val connections = new ConcurrentHashMap[BspConnectionId, BspConnection]()
  private val router = new BspRouter
  private var client: LanguageClient = uninitialized
  private var watcher: FileChangeWatcher = uninitialized
  private var knownBspFiles: Set[os.Path] = Set.empty
  private val config = BasamakeConfig.load(workspaceRoot)

  // Diagnostics: uri → (targetId → List[Diagnostic])
  private val diagnostics = mutable.Map.empty[String, Map[BuildTargetIdentifier, List[Diagnostic]]]

  private val DebounceMs = 500L
  private val debounceTimer = new Timer("basamake-bsp-watcher-debounce", true)
  private val debounceLock = new Object
  private var pendingBspChanges: Set[os.Path] = Set.empty
  private var pendingDebounceTask: Option[TimerTask] = None
  private val shuttingDown = new AtomicBoolean(false)

  def initialize(workspaceRoot: os.Path, lspClient: LanguageClient): Unit = {
    this.client = lspClient
    val discovered = BspDiscovery.discover(workspaceRoot, new GitIgnoreEngine(workspaceRoot, exemptLastNames = Set(".bsp")))
    knownBspFiles = discovered.map(_.path).toSet
    for (spec <- discovered) applyOverrides(spec).foreach(attachConnection)

    watcher = FileChangeWatcher(workspaceRoot, onFileChanged, !watchIgnored(_))
    watcher.start()
    logger.debug(s"File watcher started for workspace $workspaceRoot")
  }

  // ---- poke: the one entry point from LSP handlers ----
  // makes sure BSP connection is alive, respawns if needed, and triggers compile if requested.
  def poke(uri: String, compile: Boolean): Unit = {
    if (shuttingDown.get()) return
    val connOpt = router.route(uri).flatMap(id => Option(connections.get(id)))
    connOpt match {
      case Some(conn) =>
        try {
          if (compile || !conn.compiledOnce) conn.compile(uri)
          else conn.poke()
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
    val removed = synchronized { diagnostics.remove(uri) }
    logger.debug(s"clearDiagnostics($uri): entry existed=${removed.isDefined}")
    if (client != null) {
      client.publishDiagnostics(
        new LspPublishDiagnosticsParams(uri, java.util.Collections.emptyList()))
    }
  }

  /** React to filesystem create/delete events (workspace/didChangeWatchedFiles).
    * Deleted files: clear diagnostics immediately. Created+deleted files: trigger
    * a compile, once per connection (the client debounces watcher events into a
    * single batch). Catches renames done outside VS Code's file-operation flow
    * (e.g. terminal mv), which never send didRenameFiles. */
  def onWatchedFilesChanged(created: List[String], deleted: List[String]): Unit = {
    deleted.foreach(clearDiagnostics)
    val uris = (created ++ deleted).filter(isWatchedSource)
    if (uris.isEmpty) return
    Thread.ofVirtual().start(() => {
      val firstUriByConn = mutable.Map.empty[BspConnection, String]
      uris.foreach { uri =>
        router.route(uri).flatMap(id => Option(connections.get(id))).foreach { conn =>
          if (!firstUriByConn.contains(conn)) firstUriByConn(conn) = uri
        }
      }
      firstUriByConn.foreach { case (conn, uri) =>
        try conn.compile(uri)
        catch { case e: Exception => logger.warn(s"watcher-triggered compile failed for $uri: ${e.getMessage}") }
      }
    })
  }

  private def isWatchedSource(uri: String): Boolean = {
    val lower = uri.toLowerCase(java.util.Locale.ROOT)
    lower.endsWith(".scala") || lower.endsWith(".java") || lower.endsWith(".sbt")
  }

  // ---- BspEventSink: diagnostics ----
  override def onDiagnostics(params: PublishDiagnosticsParams): Unit = {
    val uri = params.getTextDocument.getUri
    val targetId = Option(params.getBuildTarget).getOrElse(new BuildTargetIdentifier(""))
    val newDiags = Option(params.getDiagnostics).getOrElse(java.util.Collections.emptyList())
      .asScala.map(bspDiagToLsp).toList
    logger.debug(s"onDiagnostics($uri): ${newDiags.size} diagnostic(s), reset=${params.getReset}")

    val perTarget = synchronized {
      val current = diagnostics.getOrElse(uri, Map.empty)
      val updated =
        if (params.getReset) current + (targetId -> newDiags)
        else current + (targetId -> (current.getOrElse(targetId, Nil) ++ newDiags))
      diagnostics(uri) = updated
      updated
    }
    val union = perTarget.values.flatten.toList.asJava
    if (client != null) client.publishDiagnostics(new LspPublishDiagnosticsParams(uri, union))
  }

  // ---- BspAfterCompileSink: forward to WorkspaceIndex.invalidate ----
  override def onAfterCompile(roots: List[SemanticdbDirs]): Unit =
    try workspaceIndex.invalidate(roots)
    catch { case e: Exception => logger.warn(s"WorkspaceIndex.invalidate failed: ${e.getMessage}", e) }
    

  override def onTargetChanged(params: DidChangeBuildTarget): Unit =
    logger.debug(s"buildTargetDidChange: ${params.getChanges.size()} events — no-op in v1")

  override def onShowMessage(params: org.eclipse.lsp4j.MessageParams): Unit =
    if (client != null) client.showMessage(params)

  // ---- BSP task notifications → LSP logMessage ----
  override def onTaskStart(params: TaskStartParams): Unit = {
    val msg = Option(params.getMessage).filter(_.nonEmpty)
    msg.foreach { m =>
      logToClient(org.eclipse.lsp4j.MessageType.Info, s"Compiling: $m")
    }
  }

  override def onTaskFinish(params: TaskFinishParams): Unit = {
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
    if (client != null) {
      client.logMessage(new org.eclipse.lsp4j.MessageParams(msgType, msg))
      logger.debug(s"→ LSP client: [$msgType] $msg")
    }
  }

  // ---- Lifecycle ----
  def shutdown(): Unit = {
    if (!shuttingDown.compareAndSet(false, true)) return
    logger.info("BspManager shutdown started...")
    if (watcher != null) watcher.stop()
    debounceLock.synchronized {
      pendingDebounceTask.foreach(_.cancel())
      pendingDebounceTask = None
      pendingBspChanges = Set.empty
    }
    debounceTimer.cancel()
    connections.values().asScala.foreach(_.shutdown())
    connections.clear()
    val killed = ProcessUtils.terminateProcessHandleTree(java.lang.ProcessHandle.current())
    if (killed > 0) logger.info(s"Killed $killed descendant process node(s) during shutdown")
  }

  // ---- File watcher ----
  private def onFileChanged(changedPaths: Set[os.Path]): Unit = {
    val watched = changedPaths.filterNot(watchIgnored)
    val changedBspFiles = watched.filter(_.segments.toSeq.contains(".bsp"))
    if (changedBspFiles.nonEmpty) {
      logger.info(s"Detected .bsp change(s): ${changedBspFiles.mkString(", ")}")
      enqueueBspChangeBatch(changedBspFiles)
    }
  }

  private def enqueueBspChangeBatch(changedBspFiles: Set[os.Path]): Unit = {
    debounceLock.synchronized {
      pendingBspChanges = pendingBspChanges ++ changedBspFiles
      pendingDebounceTask.foreach(_.cancel())
      val task = new TimerTask {
        override def run(): Unit = {
          val batch = debounceLock.synchronized {
            val toHandle = pendingBspChanges
            pendingBspChanges = Set.empty
            pendingDebounceTask = None
            toHandle
          }
          if (batch.nonEmpty) {
            router.invalidateBootstrapCache()
            handleBspChanges(batch)
          }
        }
      }
      pendingDebounceTask = Some(task)
      debounceTimer.schedule(task, DebounceMs)
    }
  }

  private def handleBspChanges(changed: Set[os.Path]): Unit = synchronized {
    val current = BspDiscovery.discover(workspaceRoot, new GitIgnoreEngine(workspaceRoot, exemptLastNames = Set(".bsp"))).map(_.path).toSet
    val (newFiles, deletedFiles, modifiedFiles) =
      BspManager.classifyBspChanges(knownBspFiles, current, changed)

    for (p <- deletedFiles) {
      logger.info(s"BSP config deleted: $p")
      knownBspFiles -= p
      detachConnection(BspConnectionId(p.toString))
    }
    for (p <- newFiles) {
      logger.info(s"New BSP config detected: $p")
      knownBspFiles += p
      BspDiscovery.parseSingleSpec(p, workspaceRoot).foreach(spec => applyOverrides(spec).foreach(attachConnection))
    }
    for (p <- modifiedFiles) {
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
    }
  }

  private def detachConnection(connId: BspConnectionId): Unit = {
    Option(connections.remove(connId)).foreach { conn =>
      val ownedUris = synchronized {
        val uris = diagnostics.keys.toList
        uris.foreach(u => diagnostics.remove(u))
        uris
      }
      if (client != null) ownedUris.foreach { uri =>
        client.publishDiagnostics(new LspPublishDiagnosticsParams(uri, java.util.Collections.emptyList()))
      }
      router.unregisterGroundTruth(connId)
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

  private def watchIgnored(path: os.Path): Boolean = {
    val relOpt = try Some(path.relativeTo(workspaceRoot)) catch { case _: Exception => None }
    relOpt match {
      case None => true
      case Some(rel) if rel.segments.isEmpty => false
      case Some(rel) =>
        val segs = rel.segments.toSeq
        segs.sliding(2).exists(_.toSeq == Seq(".basamake", "logs")) ||
          segs.head == "target" ||
          segs.head == "out" ||
          segs.head == ".deder" ||
          segs.head == ".metals"
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

  private[bsp] def routeForTesting(uri: String): Option[BspConnectionId] = router.route(uri)
  private[bsp] def initializeForTestingOnlyDiscover(): Unit = {
    val discovered = BspDiscovery.discover(workspaceRoot, new GitIgnoreEngine(workspaceRoot, exemptLastNames = Set(".bsp")))
    knownBspFiles = discovered.map(_.path).toSet
    for (spec <- discovered) applyOverrides(spec).foreach(attachConnection)
  }
}

object BspManager {
  def apply(workspaceRoot: os.Path, workspaceIndex: WorkspaceIndex): BspManager =
    new BspManager(workspaceRoot, workspaceIndex)

  private[bsp] def classifyBspChanges(
      known: Set[os.Path], current: Set[os.Path], changed: Set[os.Path]
  ): (Set[os.Path], Set[os.Path], Set[os.Path]) = {
    val newFiles = current -- known
    val deletedFiles = known -- current
    val modifiedFiles = known.intersect(current).intersect(changed)
    (newFiles, deletedFiles, modifiedFiles)
  }

  private[bsp] def forTesting(root: os.Path, index: WorkspaceIndex = null): BspManager =
    new BspManager(root, index)

  private[bsp] def forTestingWithCapturedDiagnostics(
      root: os.Path = os.temp.dir(prefix = "bsp-diag-test-")
  ): (BspManager, java.util.List[LspPublishDiagnosticsParams], java.util.List[org.eclipse.lsp4j.MessageParams]) = {
    val captured = new CopyOnWriteArrayList[LspPublishDiagnosticsParams]()
    val capturedLog = new CopyOnWriteArrayList[org.eclipse.lsp4j.MessageParams]()
    val fakeClient = new LanguageClient {
      override def publishDiagnostics(p: LspPublishDiagnosticsParams): Unit = captured.add(p)
      override def telemetryEvent(x$0: Any): Unit = ()
      override def showMessage(x$0: org.eclipse.lsp4j.MessageParams): Unit = ()
      override def showMessageRequest(x$0: org.eclipse.lsp4j.ShowMessageRequestParams): java.util.concurrent.CompletableFuture[org.eclipse.lsp4j.MessageActionItem] = java.util.concurrent.CompletableFuture.completedFuture(null)
      override def logMessage(x$0: org.eclipse.lsp4j.MessageParams): Unit = capturedLog.add(x$0)
      override def createProgress(x$0: org.eclipse.lsp4j.WorkDoneProgressCreateParams): java.util.concurrent.CompletableFuture[Void] = java.util.concurrent.CompletableFuture.completedFuture(null)
      override def applyEdit(x$0: org.eclipse.lsp4j.ApplyWorkspaceEditParams): java.util.concurrent.CompletableFuture[org.eclipse.lsp4j.ApplyWorkspaceEditResponse] = java.util.concurrent.CompletableFuture.completedFuture(new org.eclipse.lsp4j.ApplyWorkspaceEditResponse(false))
    }
    val mgr = new BspManager(root, null)
    mgr.client = fakeClient
    (mgr, captured, capturedLog)
  }
}
