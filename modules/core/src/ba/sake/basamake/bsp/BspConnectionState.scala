package ba.sake.basamake.bsp

enum BspConnectionState:
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
