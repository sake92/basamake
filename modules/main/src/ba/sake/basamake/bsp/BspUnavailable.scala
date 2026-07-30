package ba.sake.basamake.bsp

/** Thrown by BspConnection.ensureConnected when in cooldown after repeated respawn fails.
  * Swallowed by BspManager.poke — the user save is silently skipped, the existing
  * diagnostic is kept (no spam). */
final case class BspUnavailable(msg: String) extends RuntimeException(msg)
