package ba.sake.basamake.bsp

import java.util.concurrent.BlockingQueue
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.{BuildTarget, SourceItemKind, SourcesResult}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import ba.sake.basamake.core.*

object BspConnectionSupervisor extends StrictLogging {
  private val MaxCrashRetries = 1  // one retry per crash sequence
  private val HandshakeTimeoutSec = 20L
  private val HealthTtlSec = 30L
  private val HealthProbeTimeoutSec = 3L

  def supervise(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage],
      lspClient: LanguageClient,
      onRoutingReady: (List[BuildTarget], SourcesResult) => Unit
  ): Unit = {
    logger.info(s"Supervisor started for ${durable.bspFile.path} (state: Idle — no process)")

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
              durable.bspFile = newSpec
            case _ =>
              logger.info(s"Idle -> Spawning (triggered by ${msg.getClass.getSimpleName})")
              transitionToRunning(durable, queue, lspClient, onRoutingReady, Some(msg))
        case BspConnectionState.Reloading =>
          transitionToRunning(durable, queue, lspClient, onRoutingReady, None)
        case BspConnectionState.BackoffWait =>
          backoffSleep(durable, queue)
        case BspConnectionState.Spawning | BspConnectionState.Handshaking =>
          logger.warn(s"Unexpected top-level state $durable.currentState, resetting to Idle")
          durable.currentState = BspConnectionState.Idle
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
            s"BSP connection failed after ${durable.attemptCounter} attempt(s)"
          )
        )
        logger.error(s"Connection ${durable.bspFile.path} reached Failed state")
      case _ =>
        logger.info(s"Connection ${durable.bspFile.path} detached")
    }
  }

  private def destroyProcess(process: java.lang.Process): Unit = {
    if process != null && process.isAlive then
      logger.info(s"Destroying BSP process ${process.pid()}")
      process.destroyForcibly()
      try process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      catch case _: InterruptedException => ()
  }

  // ---- State handlers ----
  // TODO refactor this, hard to read
  private def transitionToRunning(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage],
      lspClient: LanguageClient,
      onRoutingReady: (List[BuildTarget], SourcesResult) => Unit,
      triggerMsg: Option[ConnectionMessage]
  ): Unit = {
    durable.currentState = BspConnectionState.Spawning
    logger.info(s"Spawning (attempt ${durable.attemptCounter + 1})")

    try {
      val result      = BspHandshake.execute(durable.bspFile, queue, durable, HandshakeTimeoutSec)
      val process     = result.process
      val buildServer = result.buildServer
      val targets     = result.targets.getTargets.asScala.toList

      durable.currentState = BspConnectionState.Connected
      durable.attemptCounter = 0
      logger.info(s"Connected with ${durable.bspFile.path} (targets: ${targets.map(_.getId.getUri).mkString(", ")})")

      try onRoutingReady(targets, result.sources)
      catch case e: Exception => logger.error(s"Failed to announce routing info", e)

      triggerMsg.foreach { msg =>
        logger.debug(s"Dispatching trigger message: ${msg.getClass.getSimpleName}")
        dispatch(msg, durable, lspClient, buildServer, targets)
      }

      try {
        var lastSuccessfulResponse = java.lang.System.currentTimeMillis()
        while durable.currentState == BspConnectionState.Connected do
          val msg = queue.poll(HealthTtlSec, java.util.concurrent.TimeUnit.SECONDS)
          if msg == null then
            if !probeHealth(buildServer) then
              logger.warn("Health probe failed on idle timeout — backing off")
              transitionToBackoff(durable)
          else {
            val now = java.lang.System.currentTimeMillis()
            val stale = (now - lastSuccessfulResponse) > HealthTtlSec * 1000
            if stale && !probeHealth(buildServer) then
              logger.warn("Health probe failed — re-queuing message and backing off")
              transitionToBackoff(durable)
              if durable.currentState != BspConnectionState.Detached then
                queue.offer(msg) // re-queue the message for next attempt
            else
              try
                dispatch(msg, durable, lspClient, buildServer, targets)
                lastSuccessfulResponse = now
              catch
                case e: Exception =>
                  logger.error(s"Dispatch failed: ${e.getMessage}", e)
                  transitionToBackoff(durable)
                  if durable.currentState != BspConnectionState.Detached then
                    queue.offer(msg)
          }
      } finally {
        destroyProcess(process)
        durable.bspProcess = None
      }
    } catch {
      case e: Exception =>
        logger.error(s"Handshake failed", e)
        durable.currentState = BspConnectionState.Failed
    }
  }

  private def dispatch(
      msg: ConnectionMessage,
      durable: DurableRecord,
      lspClient: LanguageClient,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      targets: List[ch.epfl.scala.bsp4j.BuildTarget]
  ): Unit = 
    msg match {
      case ConnectionMessage.ProcessExited =>
        logger.warn("BSP process exited")
        transitionToBackoff(durable)
      case ConnectionMessage.ReloadRequested(newSpec) =>
        logger.info("Reload requested")
        durable.bspFile = newSpec
        durable.currentState = BspConnectionState.Reloading
      case ConnectionMessage.BspPublishDiagnostics(params) =>
        handleDiagnostics(params, durable, lspClient)
      case ConnectionMessage.DidOpen(params) =>
        triggerCompile(params.getTextDocument.getUri, buildServer, targets)
      case ConnectionMessage.DidChange(_) =>
        ()
      case ConnectionMessage.DidSave(params) =>
        logger.info(s"didSave: ${params.getTextDocument.getUri}")
        triggerCompile(params.getTextDocument.getUri, buildServer, targets)
      case ConnectionMessage.DidClose(_) =>
        ()
      case ConnectionMessage.Shutdown =>
        logger.info("Received shutdown poison pill")
        durable.currentState = BspConnectionState.Detached
      case _ => ()
    }

  private def triggerCompile(
      uri: String,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      targets: List[ch.epfl.scala.bsp4j.BuildTarget]
  ): Unit = {
    if targets.isEmpty then return
    logger.info(s"Compile triggered for $uri")
    try
      val params = new ch.epfl.scala.bsp4j.CompileParams(
        targets.map(_.getId).asJava
      )
      buildServer.buildTargetCompile(params).get()
      logger.info(s"Compile completed for $uri")
    catch
      case e: Exception =>
        logger.error(s"Compile failed for $uri", e)
  }

  // ---- Diagnostics ----
  private def handleDiagnostics(
      params: ch.epfl.scala.bsp4j.PublishDiagnosticsParams,
      durable: DurableRecord,
      lspClient: LanguageClient
  ): Unit = {
    val uri      = params.getTextDocument.getUri
    val targetId = Option(params.getBuildTarget).map(_.getUri).getOrElse("")
    val newDiags = Option(params.getDiagnostics)
      .getOrElse(java.util.Collections.emptyList())
      .asScala
      .map(bspDiagToLsp)
      .toList

    val perTarget: Map[String, List[Diagnostic]] =
      durable.lastKnownDiagnostics.getOrElse(uri, Map.empty)

    val updated =
      if params.getReset then perTarget + (targetId -> newDiags)
      else perTarget + (targetId -> (perTarget.getOrElse(targetId, Nil) ++ newDiags))

    durable.lastKnownDiagnostics = durable.lastKnownDiagnostics + (uri -> updated)

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

  private def transitionToBackoff(durable: DurableRecord): Unit = {
    if durable.currentState == BspConnectionState.Detached
        || durable.currentState == BspConnectionState.Failed
    then return

    durable.attemptCounter += 1
    if durable.attemptCounter > MaxCrashRetries then
      durable.currentState = BspConnectionState.Failed
      logger.error(
        s"Connection ${durable.bspFile.path} failed after ${durable.attemptCounter} consecutive crash(es)"
      )
    else
      durable.currentState = BspConnectionState.BackoffWait
      logger.info(
        s"Connection ${durable.bspFile.path} crashed → BackoffWait, will retry (${durable.attemptCounter}/${MaxCrashRetries})"
      )
  }

  private def backoffSleep(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage]
  ): Unit = {
    val delayMs = 1000L  // fixed 1 second
    logger.info(s"Backing off for ${delayMs}ms (attempt ${durable.attemptCounter})")
    val msg = queue.poll(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    if durable.currentState == BspConnectionState.Detached then return

    msg match
      case ConnectionMessage.ReloadRequested(newSpec) =>
        durable.bspFile = newSpec
        durable.currentState = BspConnectionState.Reloading
      case ConnectionMessage.Shutdown =>
        durable.currentState = BspConnectionState.Detached
      case _ =>
        if durable.currentState == BspConnectionState.Detached then return
        durable.currentState = BspConnectionState.Spawning
  }

  private def probeHealth(buildServer: ch.epfl.scala.bsp4j.BuildServer): Boolean = {
    try
      logger.debug("Sending health probe (workspaceBuildTargets)...")
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