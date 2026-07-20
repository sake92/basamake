package ba.sake.basamake.core

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import ba.sake.basamake.bsp.{BspConnectionSpec, BspConnectionState}
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Diagnostic

/**
 * Owned by the manager — survives connection scope teardown.
 * attemptCounter MUST be here (not in the ephemeral scope) so crash→backoff→crash
 * doesn't reset it and cause a hot-loop.
 */
final case class DurableRecord(
    bspFile: AtomicReference[BspConnectionSpec],
    attemptCounter: AtomicInteger,
    /** file URI → (target → diagnostics) */
    lastKnownDiagnostics: AtomicReference[Map[String, Map[BuildTargetIdentifier, List[Diagnostic]]]],
    @volatile var currentState: BspConnectionState,
    /** inverseSources perma-failed for this connection lifetime — cached to avoid 5s stall per didSave */
    @volatile var inverseSourcesUnsupported: Boolean = false
)
