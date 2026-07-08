package ba.sake.basamake.bsp

import ba.sake.basamake.core.*
import ba.sake.basamake.util.Log
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.BlockingQueue
import scala.jdk.CollectionConverters.*

object BspConnectionSupervisor:
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
    Log.info(s"Supervisor started for ${durable.spec.path}")

    while durable.currentState != ConnectionState.Failed
        && durable.currentState != ConnectionState.Detached
    do
      durable.currentState match
        case ConnectionState.Idle | ConnectionState.Reloading =>
          transitionToRunning(durable, queue, lspClient)

        case ConnectionState.BackoffWait =>
          backoffSleep(durable, queue)

        case ConnectionState.Connected =>
          Log.warn(s"Connected state at top level — triggering reload")
          durable.currentState = ConnectionState.Reloading

        case cs =>
          Log.warn(s"Unexpected top-level state $cs, resetting to Idle")
          durable.currentState = ConnectionState.Idle

    durable.currentState match
      case ConnectionState.Failed =>
        lspClient.showMessage(
          new MessageParams(
            MessageType.Error,
            s"BSP connection failed after $MaxAttempts attempts"
          )
        )
        Log.error(s"Connection ${durable.spec.path} reached Failed state")
      case _ =>
        Log.info(s"Connection ${durable.spec.path} detached")

  private def destroyProcess(process: java.lang.Process): Unit =
    if process != null && process.isAlive then
      Log.info(s"Destroying BSP process ${process.pid()}")
      process.destroyForcibly()
      try process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      catch case _: InterruptedException => ()

  // ---- State handlers ----

  private def transitionToRunning(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage],
      lspClient: LanguageClient
  ): Unit =
    durable.currentState = ConnectionState.Spawning
    Log.info(s"Spawning (attempt ${durable.attemptCounter + 1})")

    try
      val result = BspHandshake.execute(durable.spec, queue, HandshakeTimeoutSec)
      val process     = result.process
      val buildServer = result.buildServer
      val targets     = result.targets.getTargets.asScala.toList

      durable.currentState = ConnectionState.Connected
      durable.attemptCounter = 0
      Log.info(s"Connected (targets: ${targets.map(_.getId.getUri).mkString(", ")})")

      // Message loop — blocks until state changes from Connected
      try
        while durable.currentState == ConnectionState.Connected do
          val msg = queue.take()
          msg match
            case ConnectionMessage.ProcessExited =>
              Log.warn(s"BSP process exited")
              transitionToBackoff(durable)

            case ConnectionMessage.ReloadRequested(newSpec) =>
              Log.info(s"Reload requested")
              durable.spec = newSpec
              durable.currentState = ConnectionState.Reloading

            case ConnectionMessage.BspPublishDiagnostics(params) =>
              handleDiagnostics(params, durable, lspClient)

            case ConnectionMessage.DidOpen(params) =>
              triggerCompile(params.getTextDocument.getUri, buildServer, targets)

            case ConnectionMessage.DidChange(_) =>
              () // debounce later; for now compile-on-save only

            case ConnectionMessage.DidSave(params) =>
              Log.info(s"didSave: ${params.getTextDocument.getUri}")
              triggerCompile(params.getTextDocument.getUri, buildServer, targets)

            case ConnectionMessage.DidClose(_) =>
              ()

            case _ =>
              ()
      finally
        destroyProcess(process)

    catch
      case e: Exception =>
        Log.error(s"Scope failure", e)
        transitionToBackoff(durable)

  private def triggerCompile(
      uri: String,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      targets: List[ch.epfl.scala.bsp4j.BuildTarget]
  ): Unit =
    if targets.isEmpty then return
    Log.info(s"Compile triggered for $uri")
    try
      val params = new ch.epfl.scala.bsp4j.CompileParams(
        targets.map(_.getId).asJava
      )
      buildServer.buildTargetCompile(params).get()
      Log.info(s"Compile completed for $uri")
    catch
      case e: Exception =>
        Log.error(s"Compile failed for $uri", e)

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
    durable.attemptCounter += 1
    if durable.attemptCounter >= MaxAttempts then
      durable.currentState = ConnectionState.Failed
      Log.error(
        s"Connection ${durable.spec.path} reached max attempts (${durable.attemptCounter})"
      )
    else
      durable.currentState = ConnectionState.BackoffWait
      Log.info(
        s"Connection ${durable.spec.path} → BackoffWait (attempt ${durable.attemptCounter})"
      )

  private def backoffSleep(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage]
  ): Unit =
    val delay = math.min(
      1000L * (1L << (durable.attemptCounter - 1)),
      MaxBackoffMs
    )
    Log.info(s"Backing off for ${delay}ms")
    val msg = queue.poll(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
    if durable.currentState == ConnectionState.Detached then return
    msg match
      case ConnectionMessage.ReloadRequested(newSpec) =>
        durable.spec = newSpec
        durable.currentState = ConnectionState.Reloading
      case _ =>
        durable.currentState = ConnectionState.Spawning
