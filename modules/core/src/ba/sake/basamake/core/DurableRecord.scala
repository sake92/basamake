package ba.sake.basamake.core

import ba.sake.basamake.bsp.{BspConnectionSpec, BspConnectionState}
import org.eclipse.lsp4j.Diagnostic

/**
 * Owned by the manager — survives connection scope teardown.
 * attemptCounter MUST be here (not in the ephemeral scope) so crash→backoff→crash
 * doesn't reset it and cause a hot-loop.
 */
final case class DurableRecord(
    var bspFile: BspConnectionSpec,
    var attemptCounter: Int,
    var lastKnownDiagnostics: Map[String, Map[String, List[Diagnostic]]],
    @volatile var currentState: BspConnectionState,
    @volatile var bspProcess: Option[java.lang.Process] = None
)
