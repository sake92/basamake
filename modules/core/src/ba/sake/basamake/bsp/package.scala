package ba.sake.basamake.bsp


import ba.sake.tupson.JsonRW

/** Typed wrapper for a BSP connection identifier (derived from .bsp/_name_.json filename). */
opaque type BspConnectionId = String

object BspConnectionId:
  def apply(value: String): BspConnectionId = value
  extension (id: BspConnectionId) def value: String = id

enum BspConnectionState {
  /** No process alive. Supervisor VT blocks on queue.take().
    * First LSP command (DidOpen/DidSave/DidChange) triggers BSP process spawn. */
  case Idle
  /** Process is being spawned + BSP handshake in progress (blocking). */
  case Spawning
  case Handshaking
  /** Steady state. Message dispatch loop active. Health probe on each dispatch. */
  case Connected
  /** Process crashed. Sleep 1s, then retry once. */
  case BackoffWait
  /** User-driven reload (`.json` changed). Immediate respawn, no backoff. */
  case Reloading
  /** Terminal — handshake failed or crash retry exhausted. */
  case Failed
  /** Connection removed (`.json` deleted or shutdown). */
  case Detached
}

/** .bsp JSON file */
private case class BspDiscoveryFile(name: String, argv: List[String]) derives JsonRW

final case class BspConnectionSpec(
    content: BspDiscoveryFile,
    path: os.Path,
    debounceMs: Long = 500,
    compileTimeoutSec: Long = 600
) {
  val workingDir: os.Path = path / os.up / os.up
}
