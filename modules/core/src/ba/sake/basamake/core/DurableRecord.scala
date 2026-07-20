package ba.sake.basamake.core

import ba.sake.basamake.bsp.{BspConnectionSpec, BspConnectionState}
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Diagnostic

/**
 * Owned by the manager — survives connection scope teardown.
 * attemptCounter MUST be here (not in the ephemeral scope) so crash→backoff→crash
 * doesn't reset it and cause a hot-loop.
 */
// TODO check if thread safety is ok
final case class DurableRecord(
    var bspFile: BspConnectionSpec,
    var attemptCounter: Int,
    /** file URI → (target → diagnostics) */
    var lastKnownDiagnostics: Map[String, Map[BuildTargetIdentifier, List[Diagnostic]]],
    @volatile var currentState: BspConnectionState
)
