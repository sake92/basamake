package ba.sake.basamake.core

import ba.sake.basamake.bsp.{BspConnectionFile, BspConnectionState}
import org.eclipse.lsp4j.Diagnostic

/**
 * Owned by the manager — survives connection scope teardown.
 * attemptCounter MUST be here (not in the ephemeral scope) so crash→backoff→crash
 * doesn't reset it and cause a hot-loop.
 */
final case class DurableRecord(
    var bspFile: BspConnectionFile,
    var attemptCounter: Int,
    var lastKnownDiagnostics: Map[String, Map[String, List[Diagnostic]]],
    var currentState: BspConnectionState,
    var bspProcess: Option[java.lang.Process] = None
)
