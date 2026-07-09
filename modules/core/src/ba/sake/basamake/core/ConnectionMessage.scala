package ba.sake.basamake.core

import ba.sake.basamake.bsp.BspConnectionFile
import org.eclipse.lsp4j.*
import ba.sake.basamake.bsp.BspConnectionSpec

/**
 * Sealed trait for the actor queue. Protocol payloads are lsp4j/bsp4j types
 * passed through directly — this is just a routing tag.
 */
sealed trait ConnectionMessage

object ConnectionMessage:
  // LSP-originated messages
  final case class DidOpen(params: DidOpenTextDocumentParams)     extends ConnectionMessage
  final case class DidChange(params: DidChangeTextDocumentParams) extends ConnectionMessage
  final case class DidSave(params: DidSaveTextDocumentParams)     extends ConnectionMessage
  final case class DidClose(params: DidCloseTextDocumentParams)   extends ConnectionMessage

  // BSP-originated messages (via BasamakeBuildClient callback)
  final case class BspPublishDiagnostics(params: ch.epfl.scala.bsp4j.PublishDiagnosticsParams)
      extends ConnectionMessage

  // Internal events
  case object ProcessExited         extends ConnectionMessage  // process.waitFor() returned → crash
  case object HandshakeCompleted    extends ConnectionMessage
  final case class HandshakeFailed(cause: Throwable) extends ConnectionMessage
  final case class ReloadRequested(newSpec: BspConnectionSpec) extends ConnectionMessage
  case object Shutdown              extends ConnectionMessage  // poison pill → unblock queue, kill BSP
