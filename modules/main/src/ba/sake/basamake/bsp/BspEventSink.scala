package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.{DidChangeBuildTarget, PublishDiagnosticsParams}

/** Sink for BSP-originated notifications. Implemented by BspManager (or a per-connection
  * delegate) — replaces the old BlockingQueue[ConnectionMessage] plumbing. */
trait BspEventSink {
  def onDiagnostics(params: PublishDiagnosticsParams): Unit
  def onTargetChanged(params: DidChangeBuildTarget): Unit
}
