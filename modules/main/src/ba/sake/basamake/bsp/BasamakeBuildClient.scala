package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.*
import com.typesafe.scalalogging.StrictLogging

/** Basamake BSP client. Receives notifications from the build server and forwards them
  * to the event sink (BspManager). No queue, no BlockingQueue import. */
class BasamakeBuildClient(eventSink: BspEventSink) extends BuildClient, StrictLogging {

  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
    logger.debug(
      s"BSP DIAGNOSTICS: uri=${params.getTextDocument.getUri}, " +
      s"count=${Option(params.getDiagnostics).map(_.size).getOrElse(0)}, " +
      s"reset=${params.getReset}"
    )
    eventSink.onDiagnostics(params)
  }

  // TODO show messages, progress etc
  override def onBuildShowMessage(params: ShowMessageParams): Unit =
    logger.debug(s"BSP SHOW MSG: ${params.getMessage}")

  override def onBuildLogMessage(params: LogMessageParams): Unit =
    logger.debug(s"BSP LOG: ${params.getMessage}")

  override def onBuildTaskStart(params: TaskStartParams): Unit = {
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("?")
    logger.debug(s"BSP TASK START: $taskId ${params.getMessage}")
  }

  override def onBuildTaskProgress(params: TaskProgressParams): Unit = {
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("?")
    logger.debug(s"BSP TASK PROGRESS: $taskId ${params.getMessage}")
  }

  override def onBuildTaskFinish(params: TaskFinishParams): Unit = {
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("?")
    logger.debug(
      s"BSP TASK FINISH: $taskId status=${params.getStatus} msg=${params.getMessage}"
    )
  }

  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = {
    logger.debug(s"BSP TARGET DID CHANGE: ${params.getChanges.size()} event(s)")
    eventSink.onTargetChanged(params)
  }

  override def onRunPrintStderr(x$0: PrintParams): Unit = ()
  override def onRunPrintStdout(x$0: PrintParams): Unit = ()
}
