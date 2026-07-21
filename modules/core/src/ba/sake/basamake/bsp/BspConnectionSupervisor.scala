package ba.sake.basamake.bsp

import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.{BuildTarget, BuildTargetIdentifier, DependencySourcesResult, SourceItemKind, SourcesResult}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import ba.sake.basamake.core.*
import ba.sake.basamake.util.ProcessUtils
import ba.sake.basamake.navigation.NavigationUriUtils

object BspConnectionSupervisor extends StrictLogging {
  private val MaxCrashRetries = 5  // retry crashes + handshake failures 5 times
  private val HandshakeTimeoutSec = 20L
  private val HealthTtlSec = 30L
  private val HealthProbeTimeoutSec = 10L
  private val ConnectionGracePeriodMs = 30_000L // reset attempt counter only if connection survived 30s+

  def supervise(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage],
      lspClient: LanguageClient,
      onRoutingReady: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTarget], SourcesResult, DependencySourcesResult) => Unit,
      onCompileSuccess: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTargetIdentifier]) => Unit = (_, _) => (),
      onBuildTargetChanged: (ch.epfl.scala.bsp4j.BuildServer, DependencySourcesResult, List[BuildTargetIdentifier], List[BuildTargetIdentifier]) => Unit = (_, _, _, _) => ()
  ): Unit = {
    logger.info(s"Supervisor started for ${durable.bspFile.get().path} (state: Idle)")

    while durable.currentState != BspConnectionState.Failed
        && durable.currentState != BspConnectionState.Detached
    do {
      durable.currentState match
        case BspConnectionState.Idle =>
          val msg = queue.take()
          msg match
            case ConnectionMessage.Shutdown =>
              durable.currentState = BspConnectionState.Detached
            case ConnectionMessage.ReloadRequested(newSpec) =>
              durable.bspFile.set(newSpec)
            case _ =>
              logger.info(s"Idle -> Spawning (triggered by ${msg.getClass.getSimpleName})")
              transitionToRunning(durable, queue, lspClient, onRoutingReady, onCompileSuccess, onBuildTargetChanged, Some(msg))
        case BspConnectionState.Reloading =>
          transitionToRunning(durable, queue, lspClient, onRoutingReady, onCompileSuccess, onBuildTargetChanged, None)
        case BspConnectionState.BackoffWait =>
          backoffSleep(durable, queue)
        case BspConnectionState.Spawning | BspConnectionState.Handshaking =>
          logger.info(s"$durable.currentState at top-level — re-entering transitionToRunning")
          transitionToRunning(durable, queue, lspClient, onRoutingReady, onCompileSuccess, onBuildTargetChanged, None)
        case BspConnectionState.Connected =>
          logger.warn(s"Connected state at top level — triggering reload")
          durable.currentState = BspConnectionState.Reloading
        case cs =>
          logger.warn(s"Unexpected top-level state $cs, resetting to Idle")
          durable.currentState = BspConnectionState.Idle
    }

    durable.currentState match {
      case BspConnectionState.Failed =>
        lspClient.showMessage(
          new MessageParams(
            MessageType.Error,
            s"BSP connection to ${durable.bspFile.get().path} failed after ${durable.attemptCounter.get()} attempt(s)"
          )
        )
        logger.error(s"Connection ${durable.bspFile.get().path} reached Failed state")
      case _ =>
        logger.info(s"Connection ${durable.bspFile.get().path} detached")
    }
  }

  private val ShutdownTimeoutSec = 2L

  private def destroyProcess(process: java.lang.Process): Unit = {
    if process != null && process.isAlive then
      val signaled = ProcessUtils.terminateProcessTree(process)
      logger.info(s"Destroying BSP process ${process.pid()} (signaled $signaled process node(s))")
  }

  private def gracefulShutdown(buildServer: ch.epfl.scala.bsp4j.BuildServer): Unit = {
    try
      buildServer.buildShutdown().get(ShutdownTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      buildServer.onBuildExit()
    catch
      case _: Exception => () // best-effort, destroyProcess follows
  }

  // ---- State handlers ----
  // TODO refactor this, hard to read
  private def transitionToRunning(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage],
      lspClient: LanguageClient,
      onRoutingReady: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTarget], SourcesResult, DependencySourcesResult) => Unit,
      onCompileSuccess: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTargetIdentifier]) => Unit,
      onBuildTargetChanged: (ch.epfl.scala.bsp4j.BuildServer, DependencySourcesResult, List[BuildTargetIdentifier], List[BuildTargetIdentifier]) => Unit,
      triggerMsg: Option[ConnectionMessage]
  ): Unit = {
    durable.currentState = BspConnectionState.Spawning
    logger.info(s"Spawning (attempt ${durable.attemptCounter.get() + 1})")

    try {
      val result      = BspHandshake.execute(durable.bspFile.get(), queue, HandshakeTimeoutSec)
      val process     = result.process
      val buildServer = result.buildServer
      val targets     = result.targets.getTargets.asScala.toList
      val allTargetIds = targets.map(_.getId)
      val targetSourceRootsById = targetToSourceRoots(result.sources)

      durable.currentState = BspConnectionState.Connected
      durable.connectedAtMs = java.lang.System.currentTimeMillis()
      logger.info(s"Connected with ${durable.bspFile.get().path} (targets: ${allTargetIds.map(_.getUri).mkString(", ")})")

      // Immediate crash detection: listen for process exit
      process.onExit().thenAccept { _ =>
        queue.offer(ConnectionMessage.ProcessExited)
      }

      // compile-in-flight flag: suppress health probes while waiting for buildTargetCompile response
      val compileInFlight = new AtomicBoolean(false)
      val progressTokenRegistered = new AtomicBoolean(false)
      val progressSupported = new AtomicBoolean(false)

      try onRoutingReady(buildServer, targets, result.sources, result.dependencySources)
      catch case e: Exception => logger.error(s"Failed to announce routing info", e)

      triggerMsg.foreach { msg =>
        logger.debug(s"Dispatching trigger message: ${msg.getClass.getSimpleName}")
        dispatch(msg, durable, lspClient, buildServer, targetSourceRootsById, allTargetIds, onCompileSuccess, onBuildTargetChanged, compileInFlight, progressTokenRegistered, progressSupported)
      }

      try {
        var lastSuccessfulResponse = java.lang.System.currentTimeMillis()
        var consecutiveProbeFailures = 0
        while durable.currentState == BspConnectionState.Connected do
          val msg = queue.poll(HealthTtlSec, java.util.concurrent.TimeUnit.SECONDS)
          if msg == null then
            if consecutiveProbeFailures > 0 && !compileInFlight.get() && probeHealth(durable, buildServer) then
              consecutiveProbeFailures = 0
              lastSuccessfulResponse = java.lang.System.currentTimeMillis()
            else if !compileInFlight.get() && !probeHealth(durable, buildServer) then
              consecutiveProbeFailures += 1
              if consecutiveProbeFailures >= 2 then
                logger.warn("Health probe failed 2x — backing off")
                transitionToBackoff(durable)
            else
              consecutiveProbeFailures = 0
          else {
            val now = java.lang.System.currentTimeMillis()
            val stale = (now - lastSuccessfulResponse) > HealthTtlSec * 1000
            if stale && !compileInFlight.get() then
              if probeHealth(durable, buildServer) then
                consecutiveProbeFailures = 0
                lastSuccessfulResponse = now
              else
                consecutiveProbeFailures += 1
                if consecutiveProbeFailures >= 2 then
                  logger.warn("Health probe failed 2x on stale msg — re-queuing message and backing off")
                  transitionToBackoff(durable)
                  if durable.currentState != BspConnectionState.Detached then
                    queue.offer(msg)
            else
              try
                dispatch(msg, durable, lspClient, buildServer, targetSourceRootsById, allTargetIds, onCompileSuccess, onBuildTargetChanged, compileInFlight, progressTokenRegistered, progressSupported)
                lastSuccessfulResponse = now
                consecutiveProbeFailures = 0
              catch
                case e: Exception =>
                  logger.error(s"Dispatch failed: ${e.getMessage}", e)
                  transitionToBackoff(durable)
                  if durable.currentState != BspConnectionState.Detached then
                    queue.offer(msg)
          }
      } finally {
        gracefulShutdown(buildServer)
        destroyProcess(process)
      }
    } catch {
      case e: Exception =>
        logger.error(s"Handshake failed", e)
        handleHandshakeFailure(durable)
    }
  }

  private def handleHandshakeFailure(durable: DurableRecord): Unit = {
    logger.info(s"Handshake failed for ${durable.bspFile.get().path} — entering backoff")
    transitionToBackoff(durable)
  }

  private def dispatch(
      msg: ConnectionMessage,
      durable: DurableRecord,
      lspClient: LanguageClient,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      targetToSourceRoots: Map[BuildTargetIdentifier, List[String]],
      allTargetIds: List[BuildTargetIdentifier],
      onCompileSuccess: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTargetIdentifier]) => Unit,
      onBuildTargetChanged: (ch.epfl.scala.bsp4j.BuildServer, DependencySourcesResult, List[BuildTargetIdentifier], List[BuildTargetIdentifier]) => Unit,
      compileInFlight: AtomicBoolean,
      progressTokenRegistered: AtomicBoolean,
      progressSupported: AtomicBoolean
  ): Unit = 
    msg match {
      case ConnectionMessage.ProcessExited =>
        logger.warn("BSP process exited")
        transitionToBackoff(durable)
      case ConnectionMessage.ReloadRequested(newSpec) =>
        logger.info("Reload requested")
        durable.bspFile.set(newSpec)
        durable.currentState = BspConnectionState.Reloading
      case ConnectionMessage.BspPublishDiagnostics(params) =>
        handleDiagnostics(params, durable, lspClient)
      case ConnectionMessage.DidOpen(params) =>
        triggerCompile(
          params.getTextDocument.getUri,
          buildServer,
          targetToSourceRoots,
          allTargetIds,
          onCompileSuccess,
          durable,
          compileInFlight
        )
      case ConnectionMessage.DidChange(_) =>
        ()
      case ConnectionMessage.DidSave(params) =>
        logger.info(s"didSave: ${params.getTextDocument.getUri}")
        triggerCompile(
          params.getTextDocument.getUri,
          buildServer,
          targetToSourceRoots,
          allTargetIds,
          onCompileSuccess,
          durable,
          compileInFlight
        )
      case ConnectionMessage.DidClose(_) =>
        ()
      case ConnectionMessage.RecheckUri(uri) =>
        triggerCompile(uri, buildServer, targetToSourceRoots, allTargetIds, onCompileSuccess, durable, compileInFlight)
      case ConnectionMessage.BuildTargetChanged(params) =>
        handleBuildTargetChanged(params, durable, buildServer, lspClient, onBuildTargetChanged, progressTokenRegistered, progressSupported)
      case ConnectionMessage.Shutdown =>
        logger.info("Received shutdown poison pill")
        durable.currentState = BspConnectionState.Detached
      case _ => ()
    }

  private val ReindexProgressToken = "basamake-reindex"

  private def handleBuildTargetChanged(
      params: ch.epfl.scala.bsp4j.DidChangeBuildTarget,
      durable: DurableRecord,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      lspClient: LanguageClient,
      onBuildTargetChanged: (ch.epfl.scala.bsp4j.BuildServer, DependencySourcesResult, List[BuildTargetIdentifier], List[BuildTargetIdentifier]) => Unit,
      progressTokenRegistered: AtomicBoolean,
      progressSupported: AtomicBoolean
  ): Unit = {
    if buildServer == null || durable.currentState != BspConnectionState.Connected then return

    val changes = Option(params.getChanges).map(_.asScala.toList).getOrElse(Nil)
    if changes.isEmpty then return

    val (changedOrCreated, deleted) = changes.partition { e =>
      val kind = Option(e.getKind)
      kind.contains(ch.epfl.scala.bsp4j.BuildTargetEventKind.CREATED) ||
        kind.contains(ch.epfl.scala.bsp4j.BuildTargetEventKind.CHANGED)
    }
    val changedOrCreatedIds = changedOrCreated.map(_.getTarget)
    val deletedIds = deleted.map(_.getTarget)

    if !progressTokenRegistered.getAndSet(true) then {
      try {
        val token = Either.forLeft[String, Integer](ReindexProgressToken)
        lspClient.createProgress(new WorkDoneProgressCreateParams(token))
          .get(2, java.util.concurrent.TimeUnit.SECONDS)
        progressSupported.set(true)
        logger.debug(s"Progress token '$ReindexProgressToken' registered")
      } catch {
        case e: Exception =>
          logger.debug(s"Progress token '$ReindexProgressToken' registration failed: ${e.getMessage}")
          progressSupported.set(false)
      }
    }

    if changedOrCreatedIds.nonEmpty then {
      sendProgressBegin(lspClient, changedOrCreatedIds.size, progressSupported)
      try {
        val depParams = new ch.epfl.scala.bsp4j.DependencySourcesParams(changedOrCreatedIds.asJava)
        val result = buildServer.buildTargetDependencySources(depParams)
          .get(durable.bspFile.get().compileTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        onBuildTargetChanged(buildServer, result, changedOrCreatedIds, Nil)
      } catch {
        case e: Exception =>
          logger.error(s"Failed to fetch dependency sources for changed targets: ${e.getMessage}")
      } finally {
        sendProgressEnd(lspClient, progressSupported)
      }
    }

    if deletedIds.nonEmpty then {
      logger.info(s"BSP target(s) deleted: ${deletedIds.map(_.getUri).mkString(", ")}")
      onBuildTargetChanged(buildServer, null, Nil, deletedIds)
    }
  }

  private def sendProgressBegin(lspClient: LanguageClient, targetCount: Int, progressSupported: AtomicBoolean): Unit = {
    if !progressSupported.get() then return
    try {
      val token = Either.forLeft[String, Integer](ReindexProgressToken)
      val beginNotif = new WorkDoneProgressBegin()
      beginNotif.setTitle(s"Reindexing $targetCount target(s)…")
      val begin = Either.forLeft[WorkDoneProgressNotification, Object](beginNotif)
      lspClient.notifyProgress(new ProgressParams(token, begin))
    } catch {
      case e: Exception => logger.debug(s"Failed to send progress begin: ${e.getMessage}")
    }
  }

  private def sendProgressEnd(lspClient: LanguageClient, progressSupported: AtomicBoolean): Unit = {
    if !progressSupported.get() then return
    try {
      val token = Either.forLeft[String, Integer](ReindexProgressToken)
      val end = Either.forLeft[WorkDoneProgressNotification, Object](
        new WorkDoneProgressEnd()
      )
      lspClient.notifyProgress(new ProgressParams(token, end))
    } catch {
      case e: Exception => logger.debug(s"Failed to send progress end: ${e.getMessage}")
    }
  }

  private[bsp] def triggerCompile(
      uri: String,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      targetToSourceRoots: Map[BuildTargetIdentifier, List[String]],
      allTargetIds: List[BuildTargetIdentifier],
      onCompileSuccess: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTargetIdentifier]) => Unit,
      durable: DurableRecord,
      compileInFlight: AtomicBoolean
  ): Unit = {
    val targetIds = selectCompileTargetIds(uri, buildServer, targetToSourceRoots, allTargetIds, durable)
    if targetIds.isEmpty then return
    logger.info(s"Compile triggered for $uri for targets: ${targetIds.map(_.getUri).mkString(", ")}")
    compileInFlight.set(true)
    try
      val params = new ch.epfl.scala.bsp4j.CompileParams(targetIds.asJava)
      val result = buildServer.buildTargetCompile(params)
        .get(durable.bspFile.get().compileTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      logger.info(s"Compile completed for $uri with status ${result.getStatusCode}")
      val shouldIndex = result.getStatusCode == ch.epfl.scala.bsp4j.StatusCode.OK ||
        hasBestEffortFlag(buildServer, targetIds)
      if shouldIndex then
        try onCompileSuccess(buildServer, targetIds)
        catch case e: Exception =>
          logger.warn(s"SemanticDB refresh failed after compile for $uri: ${e.getMessage}")
    catch
      case e: Exception =>
        logger.error(s"Compile failed for $uri", e)
    finally
      compileInFlight.set(false)
  }

  /** Check if any target has -Ybest-effort in scalacOptions.
    * Returns false for non-Scala servers or on timeout/error (safe default). */
  private def hasBestEffortFlag(
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      targetIds: List[BuildTargetIdentifier]
  ): Boolean = {
    try {
      buildServer match {
        case scalaBuild: ch.epfl.scala.bsp4j.ScalaBuildServer =>
          val params = new ch.epfl.scala.bsp4j.ScalacOptionsParams(targetIds.asJava)
          val result = scalaBuild.buildTargetScalacOptions(params)
            .get(2, java.util.concurrent.TimeUnit.SECONDS)
          Option(result.getItems).toList.flatMap(_.asScala).exists { item =>
            Option(item.getOptions).toList.flatMap(_.asScala).contains("-Ybest-effort")
          }
        case _ => false
      }
    } catch {
      case _: Exception => false
    }
  }

  private[bsp] def selectCompileTargetIds(
      uri: String,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      targetToSourceRoots: Map[BuildTargetIdentifier, List[String]],
      allTargetIds: List[BuildTargetIdentifier],
      durable: DurableRecord = DurableRecord(
        new java.util.concurrent.atomic.AtomicReference(null),
        new java.util.concurrent.atomic.AtomicInteger(0),
        new java.util.concurrent.atomic.AtomicReference(Map.empty),
        BspConnectionState.Idle
      )
  ): List[BuildTargetIdentifier] = {
    // 1. Best: exact file→target mapping via BSP inverseSources, if BSP server knows (implements it)
    val inverseTargets = tryInverseSources(uri, buildServer, durable)
    if inverseTargets.nonEmpty then return inverseTargets
    // 2. Good: directory-level source-root matching (no BSP call, from handshake cache)
    val rootMatches = targetIdsForUri(uri, targetToSourceRoots)
    if rootMatches.nonEmpty then rootMatches
    // 3. Last resort: compile everything
    else if allTargetIds.nonEmpty then
      logger.warn(s"No matching BSP targets for $uri (inverseSources+sourceRoots both failed), falling back to all connection targets")
      allTargetIds
    else Nil
  }

  private def targetToSourceRoots(sources: SourcesResult): Map[BuildTargetIdentifier, List[String]] =
    def ensureTrailingSlash(uri: String): String =
      if uri.endsWith("/") then uri else s"$uri/"
    sources.getItems.asScala.toList.map { item =>
      val targetId = item.getTarget
      val roots = Option(item.getSources)
        .map(_.asScala.toList)
        .getOrElse(Nil)
        .filterNot(_.getGenerated)
        .collect {
          case si if si.getKind == SourceItemKind.DIRECTORY => ensureTrailingSlash(si.getUri)
          case si if si.getKind == SourceItemKind.FILE      => si.getUri
        }
      targetId -> roots
    }.toMap

  private[bsp] def targetIdsForUri(
      uri: String,
      targetToSourceRoots: Map[BuildTargetIdentifier, List[String]]
  ): List[BuildTargetIdentifier] = {
    def inSourceRoot(uri: String, sourceRoot: String): Boolean = {
      val normalizedUri = NavigationUriUtils.normalizeUri(uri)
      val normalizedSourceRoot = NavigationUriUtils.normalizeUri(sourceRoot)
      if normalizedSourceRoot.endsWith("/") then normalizedUri.startsWith(normalizedSourceRoot)
      else normalizedUri == normalizedSourceRoot || normalizedUri.startsWith(s"$normalizedSourceRoot/")
    }
    targetToSourceRoots.toList.collect {
      case (targetId, roots) if roots.exists(inSourceRoot(uri, _)) => targetId
    }
  }
  

  /** Ask the BSP server which targets contain `uri`.
    * Returns Nil if the call fails or inverseSources is unsupported — caller falls back.
    * Once inverseSources fails for the connection lifetime, marks it unsupported (avoids 5s stall per didSave). */
  private def tryInverseSources(
      uri: String,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      durable: DurableRecord
  ): List[BuildTargetIdentifier] = {
    if buildServer == null then return Nil
    if durable.inverseSourcesUnsupported then return Nil
    try
      val params = new ch.epfl.scala.bsp4j.InverseSourcesParams(
        new ch.epfl.scala.bsp4j.TextDocumentIdentifier(uri)
      )
      val result = buildServer.buildTargetInverseSources(params)
        .get(2, java.util.concurrent.TimeUnit.SECONDS) // reduced from 5s→2s
      result.getTargets.asScala.toList
    catch
      case e: Exception =>
        if !durable.inverseSourcesUnsupported then
          durable.inverseSourcesUnsupported = true
          logger.info(s"inverseSources unsupported by ${durable.bspFile.get().content.name} (${e.getMessage}) — caching, will skip")
        Nil
  }


  // ---- Diagnostics ----
  private def handleDiagnostics(
      params: ch.epfl.scala.bsp4j.PublishDiagnosticsParams,
      durable: DurableRecord,
      lspClient: LanguageClient
  ): Unit = {
    val uri = params.getTextDocument.getUri
    val targetId = Option(params.getBuildTarget).getOrElse(new BuildTargetIdentifier(""))
    val newDiags = Option(params.getDiagnostics)
      .getOrElse(java.util.Collections.emptyList())
      .asScala
      .map(bspDiagToLsp)
      .toList

    val perTarget: Map[BuildTargetIdentifier, List[Diagnostic]] =
      durable.lastKnownDiagnostics.get().getOrElse(uri, Map.empty)

    val updated =
      if params.getReset then perTarget + (targetId -> newDiags)
      else perTarget + (targetId -> (perTarget.getOrElse(targetId, Nil) ++ newDiags))

    durable.lastKnownDiagnostics.set(durable.lastKnownDiagnostics.get() + (uri -> updated))

    // Republish union across all targets
    val allDiags = updated.values.flatten.toList.asJava
    lspClient.publishDiagnostics(new PublishDiagnosticsParams(uri, allDiags))
  }

  private def bspDiagToLsp(bsp: ch.epfl.scala.bsp4j.Diagnostic): Diagnostic = {
    val diag = new Diagnostic()
    diag.setRange(
      new Range(
        new Position(bsp.getRange.getStart.getLine, bsp.getRange.getStart.getCharacter),
        new Position(bsp.getRange.getEnd.getLine, bsp.getRange.getEnd.getCharacter)
      )
    )
    diag.setSeverity(
      Option(bsp.getSeverity) match
        case Some(s) => convertSeverity(s)
        case None    => DiagnosticSeverity.Error
    )
    diag.setMessage(stripAnsi(Option(bsp.getMessage).getOrElse("")))
    Option(bsp.getSource).foreach(diag.setSource)
    diag
  }

  private def convertSeverity(bspSev: ch.epfl.scala.bsp4j.DiagnosticSeverity): DiagnosticSeverity = {
    import ch.epfl.scala.bsp4j.DiagnosticSeverity as B
    import DiagnosticSeverity as L
    if bspSev == B.ERROR then L.Error
    else if bspSev == B.WARNING then L.Warning
    else if bspSev == B.INFORMATION then L.Information
    else if bspSev == B.HINT then L.Hint
    else L.Error
  }

  // Strip ANSI escape codes from compiler output
  private val AnsiPattern = "\u001b\\[[0-9;]*m".r
  private def stripAnsi(s: String): String = AnsiPattern.replaceAllIn(s, "")

  // ---- Backoff ----

  private[bsp] def transitionToBackoff(durable: DurableRecord): Unit = {
    if durable.currentState == BspConnectionState.Detached
        || durable.currentState == BspConnectionState.Failed
    then return

    // Only reset crash counter if connection survived longer than grace period.
    // Prevents infinite crash loops where a healthy handshake is immediately followed
    // by process exit (e.g. sbt rewriting .bsp/sbt.json triggers reload→kill→respawn).
    val connectionLivedMs = java.lang.System.currentTimeMillis() - durable.connectedAtMs
    if connectionLivedMs >= ConnectionGracePeriodMs then
      durable.attemptCounter.set(0)

    durable.attemptCounter.incrementAndGet()
    if durable.attemptCounter.get() > MaxCrashRetries then
      durable.currentState = BspConnectionState.Failed
      logger.error(
        s"Connection ${durable.bspFile.get().path} failed after ${durable.attemptCounter.get()} consecutive crash(es)"
      )
    else
      durable.currentState = BspConnectionState.BackoffWait
      logger.info(
        s"Connection ${durable.bspFile.get().path} crashed → BackoffWait, will retry (${durable.attemptCounter.get()}/${MaxCrashRetries})"
      )
  }

  private[bsp] def backoffSleep(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage]
  ): Unit = {
    val delayMs = Math.min(1000L * Math.pow(2, durable.attemptCounter.get() - 1).toLong, 30000L)
    logger.info(s"Backing off for ${delayMs}ms (attempt ${durable.attemptCounter.get()})")
    val msg = queue.poll(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    if durable.currentState == BspConnectionState.Detached then return

    msg match
      case ConnectionMessage.ReloadRequested(newSpec) =>
        durable.bspFile.set(newSpec)
        durable.currentState = BspConnectionState.Reloading
      case ConnectionMessage.Shutdown =>
        durable.currentState = BspConnectionState.Detached
      case _ =>
        if durable.currentState == BspConnectionState.Detached then return
        if msg != null then queue.offer(msg) // re-offer before transition
        durable.currentState = BspConnectionState.Spawning
  }

  private def probeHealth(durable: DurableRecord, buildServer: ch.epfl.scala.bsp4j.BuildServer): Boolean = {
    try
      logger.debug(s"Sending health probe for ${durable.bspFile.get().path} (workspaceBuildTargets)...")
      buildServer.workspaceBuildTargets()
        .get(HealthProbeTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      logger.debug("Health probe succeeded")
      true
    catch
      case _: java.util.concurrent.TimeoutException =>
        logger.warn("Health probe timed out")
        false
      case e: Exception =>
        logger.warn(s"Health probe failed: ${e.getMessage}")
        false
  }
}
