package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.*
import com.typesafe.scalalogging.StrictLogging

/** Basamake BSP client. Receives notifications from the build server and forwards
  * them to the BspEvents sink, stamped with the owning connection id. */
class BasamakeBuildClient(events: BspEvents, connId: BspConnectionId) extends BuildClient, StrictLogging {

  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
    logger.debug(
      s"BSP DIAGNOSTICS: uri=${params.getTextDocument.getUri}, " +
      s"count=${Option(params.getDiagnostics).map(_.size).getOrElse(0)}, " +
      s"reset=${params.getReset}"
    )
    events.onDiagnostics(params, connId)
  }

  override def onBuildShowMessage(params: ShowMessageParams): Unit = {
    logger.debug(s"BSP SHOW MSG: ${params.getMessage}")
    val lspParams = new org.eclipse.lsp4j.MessageParams(
      convertMessageType(params.getType),
      Option(params.getMessage).getOrElse("")
    )
    events.onShowMessage(lspParams)
  }

  override def onBuildLogMessage(params: LogMessageParams): Unit =
    logger.debug(s"BSP LOG: ${params.getMessage}")

  override def onBuildTaskStart(params: TaskStartParams): Unit = {
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("?")
    logger.debug(s"BSP TASK START: $taskId ${params.getMessage}")
    events.onTaskStart(params, connId)
  }

  override def onBuildTaskProgress(params: TaskProgressParams): Unit = {
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("?")
    logger.debug(s"BSP TASK PROGRESS: $taskId ${params.getMessage}")
    events.onTaskProgress(params, connId)
  }

  override def onBuildTaskFinish(params: TaskFinishParams): Unit = {
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("?")
    logger.debug(s"BSP TASK FINISH: $taskId status=${params.getStatus} msg=${params.getMessage}")
    events.onTaskFinish(params, connId)
  }

  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = {
    logger.debug(s"BSP TARGET DID CHANGE: ${params.getChanges.size()} event(s)")
    events.onTargetChanged(params, connId)
  }

  override def onRunPrintStderr(x$0: PrintParams): Unit = ()
  override def onRunPrintStdout(x$0: PrintParams): Unit = ()

  private def convertMessageType(bspType: ch.epfl.scala.bsp4j.MessageType): org.eclipse.lsp4j.MessageType =
    org.eclipse.lsp4j.MessageType.forValue(bspType.getValue)
}
