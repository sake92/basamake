package ba.sake.basamake.bsp

import ba.sake.basamake.core.*
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.BlockingQueue
import scala.jdk.CollectionConverters.*

object BspConnectionSupervisor extends StrictLogging:
  val MaxAttempts = 10
  val MaxBackoffMs = 30000L
  val HandshakeTimeoutSec = 20L

  // Outer loop: owns DurableRecord.currentState. Each state either handles messages
  // or transitions. ox scopes are nested within state blocks and torn down on transition.
  // Runs on a dedicated virtual thread.
  def supervise(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage],
      lspClient: LanguageClient
  ): Unit =
    logger.info(s"Supervisor started for ${durable.bspFile.path}")

    while durable.currentState != BspConnectionState.Failed
        && durable.currentState != BspConnectionState.Detached
    do
      durable.currentState match
        case BspConnectionState.Idle | BspConnectionState.Reloading =>
          transitionToRunning(durable, queue, lspClient)

        case BspConnectionState.BackoffWait =>
          backoffSleep(durable, queue)

        case BspConnectionState.Connected =>
          logger.warn(s"Connected state at top level — triggering reload")
          durable.currentState = BspConnectionState.Reloading

        case cs =>
          logger.warn(s"Unexpected top-level state $cs, resetting to Idle")
          durable.currentState = BspConnectionState.Idle

    durable.currentState match
      case BspConnectionState.Failed =>
        lspClient.showMessage(
          new MessageParams(
            MessageType.Error,
            s"BSP connection failed after $MaxAttempts attempts"
          )
        )
        logger.error(s"Connection ${durable.bspFile.path} reached Failed state")
      case _ =>
        logger.info(s"Connection ${durable.bspFile.path} detached")

  private def destroyProcess(process: java.lang.Process): Unit =
    if process != null && process.isAlive then
      logger.info(s"Destroying BSP process ${process.pid()}")
      process.destroyForcibly()
      try process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      catch case _: InterruptedException => ()

  // ---- State handlers ----

  private def transitionToRunning(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage],
      lspClient: LanguageClient
  ): Unit =
    durable.currentState = BspConnectionState.Spawning
    logger.info(s"Spawning (attempt ${durable.attemptCounter + 1})")

    try
      val result = BspHandshake.execute(durable.bspFile, queue, durable, HandshakeTimeoutSec)
      val process     = result.process
      val buildServer = result.buildServer
      val targets     = result.targets.getTargets.asScala.toList

      durable.currentState = BspConnectionState.Connected
      durable.attemptCounter = 0
      logger.info(s"Connected (targets: ${targets.map(_.getId.getUri).mkString(", ")})")

      // Message loop — blocks until state changes from Connected
      try
        while durable.currentState == BspConnectionState.Connected do
          val msg = queue.take()
          msg match
            case ConnectionMessage.ProcessExited =>
              logger.warn(s"BSP process exited")
              transitionToBackoff(durable)

            case ConnectionMessage.ReloadRequested(newSpec) =>
              logger.info(s"Reload requested")
              durable.bspFile = newSpec
              durable.currentState = BspConnectionState.Reloading

            case ConnectionMessage.BspPublishDiagnostics(params) =>
              handleDiagnostics(params, durable, lspClient)

            case ConnectionMessage.DidOpen(params) =>
              triggerCompile(params.getTextDocument.getUri, buildServer, targets)

            case ConnectionMessage.DidChange(_) =>
              () // debounce later; for now compile-on-save only

            case ConnectionMessage.DidSave(params) =>
              logger.info(s"didSave: ${params.getTextDocument.getUri}")
              triggerCompile(params.getTextDocument.getUri, buildServer, targets)

            case ConnectionMessage.DidClose(_) =>
              ()

            case ConnectionMessage.Shutdown =>
              logger.info(s"Received shutdown poison pill")
              durable.currentState = BspConnectionState.Detached

            case _ =>
              ()
      finally
        destroyProcess(process)
        durable.bspProcess = None

    catch
      case e: Exception =>
        logger.error(s"Scope failure", e)
        transitionToBackoff(durable)

  private def triggerCompile(
      uri: String,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      targets: List[ch.epfl.scala.bsp4j.BuildTarget]
  ): Unit =
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

  // ---- Diagnostics ----

  private def handleDiagnostics(
      params: ch.epfl.scala.bsp4j.PublishDiagnosticsParams,
      durable: DurableRecord,
      lspClient: LanguageClient
  ): Unit =
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

  private def bspDiagToLsp(bsp: ch.epfl.scala.bsp4j.Diagnostic): Diagnostic =
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

  private def convertSeverity(bspSev: ch.epfl.scala.bsp4j.DiagnosticSeverity): DiagnosticSeverity =
    import ch.epfl.scala.bsp4j.DiagnosticSeverity as B
    import DiagnosticSeverity as L
    if bspSev == B.ERROR then L.Error
    else if bspSev == B.WARNING then L.Warning
    else if bspSev == B.INFORMATION then L.Information
    else if bspSev == B.HINT then L.Hint
    else L.Error

  // Strip ANSI escape codes from compiler output
  private val AnsiPattern = "\u001b\\[[0-9;]*m".r
  private def stripAnsi(s: String): String = AnsiPattern.replaceAllIn(s, "")

  // ---- Backoff ----

  private def transitionToBackoff(durable: DurableRecord): Unit =
    // Don't overwrite Detached or Failed — shutdown() may have set Detached
    // during a Scope failure, and backoff must not resurrect the connection.
    if durable.currentState == BspConnectionState.Detached
        || durable.currentState == BspConnectionState.Failed
    then return
    durable.attemptCounter += 1
    if durable.attemptCounter >= MaxAttempts then
      durable.currentState = BspConnectionState.Failed
      logger.error(
        s"Connection ${durable.bspFile.path} reached max attempts (${durable.attemptCounter})"
      )
    else
      durable.currentState = BspConnectionState.BackoffWait
      logger.info(
        s"Connection ${durable.bspFile.path} → BackoffWait (attempt ${durable.attemptCounter})"
      )

  private def backoffSleep(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage]
  ): Unit =
    val delay = math.min(
      1000L * (1L << (durable.attemptCounter - 1)),
      MaxBackoffMs
    )
    logger.info(s"Backing off for ${delay}ms")
    val msg = queue.poll(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
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
