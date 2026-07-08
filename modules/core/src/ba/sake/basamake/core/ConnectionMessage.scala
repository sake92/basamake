package ba.sake.basamake.core

import org.eclipse.lsp4j.*

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

  // BSP-originated messages (via OurBuildClient callback)
  final case class BspPublishDiagnostics(params: ch.epfl.scala.bsp4j.PublishDiagnosticsParams)
      extends ConnectionMessage

  // Internal events
  case object ProcessExited         extends ConnectionMessage  // process.waitFor() returned → crash
  case object HandshakeCompleted    extends ConnectionMessage
  final case class HandshakeFailed(cause: Throwable) extends ConnectionMessage
  final case class ReloadRequested(newSpec: ConnectionSpec) extends ConnectionMessage
