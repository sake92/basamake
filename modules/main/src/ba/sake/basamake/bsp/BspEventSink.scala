package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.{DidChangeBuildTarget, PublishDiagnosticsParams, TaskFinishParams, TaskStartParams}
import ba.sake.basamake.navigation.indexing.SemanticdbDirs

/** Sink for BSP-originated notifications and internal basamake lifecycle events.
  * Implemented by BspManager (or a per-connection delegate). */
trait BspEventSink {
  def onDiagnostics(params: PublishDiagnosticsParams): Unit
  def onTargetChanged(params: DidChangeBuildTarget): Unit
  def onShowMessage(params: org.eclipse.lsp4j.MessageParams): Unit = () // default no-op

  // BSP task notifications — forwarded from BasamakeBuildClient
  def onTaskStart(params: TaskStartParams): Unit = ()
  def onTaskFinish(params: TaskFinishParams): Unit = ()

  // Connection lifecycle — internal basamake events fired by BspConnection.ensureConnected()
  def onConnectionStarted(spec: BspConnectionSpec): Unit = ()
  def onConnectionSucceeded(spec: BspConnectionSpec, targetCount: Int): Unit = ()
  def onConnectionFailed(spec: BspConnectionSpec, error: String): Unit = ()
}

/** After-compile hook. Implemented by BspManager — fired by BspConnection.compile under
  * the connection's lock, forwards per-target (sourceRootDir, semanticdbDir) pairs
  * to WorkspaceIndex.invalidate. */
trait BspAfterCompileSink {
  def onAfterCompile(roots: List[SemanticdbDirs]): Unit
}
