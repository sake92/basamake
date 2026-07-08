package ba.sake.basamake.bsp

import ba.sake.basamake.core.ConnectionMessage
import ba.sake.basamake.util.Log
import ch.epfl.scala.bsp4j.*
import java.util.concurrent.BlockingQueue

/** Our BSP client — receives notifications from the build server. */
class OurBuildClient(queue: BlockingQueue[ConnectionMessage]) extends BuildClient:

  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit =
    Log.info(
      s"BSP DIAGNOSTICS: uri=${params.getTextDocument.getUri}, " +
      s"count=${Option(params.getDiagnostics).map(_.size).getOrElse(0)}, " +
      s"reset=${params.getReset}"
    )
    queue.offer(ConnectionMessage.BspPublishDiagnostics(params))

  override def onBuildShowMessage(params: ShowMessageParams): Unit =
    Log.info(s"BSP SHOW MSG: ${params.getMessage}")

  override def onBuildLogMessage(params: LogMessageParams): Unit =
    Log.info(s"BSP LOG: ${params.getMessage}")

  override def onBuildTaskStart(params: TaskStartParams): Unit =
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("?")
    Log.info(s"BSP TASK START: $taskId ${params.getMessage}")

  override def onBuildTaskProgress(params: TaskProgressParams): Unit =
    Log.info(s"BSP TASK PROGRESS: ${params.getTaskId.getId} ${params.getMessage}")

  override def onBuildTaskFinish(params: TaskFinishParams): Unit =
    val taskId = Option(params.getTaskId).map(_.getId).getOrElse("?")
    Log.info(
      s"BSP TASK FINISH: $taskId status=${params.getStatus} " +
      s"msg=${params.getMessage}"
    )

  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit =
    Log.info(s"BSP TARGET DID CHANGE")
