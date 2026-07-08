package ba.sake.basamake.bsp

/** Typed wrapper for a BSP connection identifier (derived from .bsp/<name>.json filename). */
opaque type BspConnectionId = String

object BspConnectionId:
  def apply(value: String): BspConnectionId = value
  extension (id: BspConnectionId) def value: String = id
