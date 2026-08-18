package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, DidChangeBuildTarget, PublishDiagnosticsParams, TaskFinishParams, TaskProgressParams, TaskStartParams}
import ba.sake.basamake.index.indexing.SemanticdbDirs

/** Single explicit event interface between BSP connections and the manager.
  * Connection-scoped events (diagnostics, task notifications, target changes)
  * carry the owning connection id so the manager attributes them directly —
  * no per-connection forwarding wrappers. BspManager is the sole implementation;
  * BspConnection and BasamakeBuildClient are the callers. */
trait BspEvents {
  def onDiagnostics(params: PublishDiagnosticsParams, connId: BspConnectionId): Unit
  def onTargetChanged(params: DidChangeBuildTarget, connId: BspConnectionId): Unit = ()
  def onShowMessage(params: org.eclipse.lsp4j.MessageParams): Unit = ()

  // BSP task notifications — forwarded from BasamakeBuildClient
  def onTaskStart(params: TaskStartParams, connId: BspConnectionId): Unit = ()
  def onTaskProgress(params: TaskProgressParams, connId: BspConnectionId): Unit = ()
  def onTaskFinish(params: TaskFinishParams, connId: BspConnectionId): Unit = ()

  // Connection lifecycle — fired by BspConnection.ensureConnected()
  def onConnectionStarted(spec: BspConnectionSpec): Unit = ()
  def onConnectionSucceeded(spec: BspConnectionSpec, targetCount: Int): Unit = ()
  def onConnectionFailed(spec: BspConnectionSpec, error: String): Unit = ()

  // After-compile hook — fired by BspConnection under its connection lock;
  // forwards per-target (sourceRootDir, semanticdbDir) pairs to WorkspaceIndex.invalidate.
  def onAfterCompile(roots: List[SemanticdbDirs]): Unit = ()

  // After-handshake dependency-source hook — receiver registers the targets
  // (cached jars only, nothing indexed eagerly).
  def onDependencySources(depsByTarget: Map[BuildTargetIdentifier, List[os.Path]]): Unit = ()
}
