package ba.sake.basamake.bsp

import ba.sake.tupson.JsonRW

/** Typed wrapper for a BSP connection identifier (derived from .bsp/_name_.json filename). */
opaque type BspConnectionId = String

object BspConnectionId:
  def apply(value: String): BspConnectionId = value
  extension (id: BspConnectionId) def value: String = id

/** .bsp JSON file */
private[bsp] case class BspDiscoveryFile(name: String, argv: List[String]) derives JsonRW

final case class BspConnectionSpec(
    content: BspDiscoveryFile,
    path: os.Path,
    compileTimeoutSec: Long = 600
) {
  val workingDir: os.Path = path / os.up / os.up
}
