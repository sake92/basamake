package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.{DidChangeBuildTarget, PublishDiagnosticsParams}
import ba.sake.basamake.navigation.indexing.SemanticdbDirs

/** Sink for BSP-originated notifications. Implemented by BspManager (or a per-connection
  * delegate) — replaces the old BlockingQueue[ConnectionMessage] plumbing. */
trait BspEventSink {
  def onDiagnostics(params: PublishDiagnosticsParams): Unit
  def onTargetChanged(params: DidChangeBuildTarget): Unit
  def onShowMessage(params: org.eclipse.lsp4j.MessageParams): Unit = () // default no-op
}

/** After-compile hook. Implemented by BspManager — fired by BspConnection.compile under
  * the connection's lock, forwards per-target (sourceRootDir, semanticdbDir) pairs
  * to WorkspaceIndex.invalidate. */
trait BspAfterCompileSink {
  def onAfterCompile(roots: List[SemanticdbDirs]): Unit
}
