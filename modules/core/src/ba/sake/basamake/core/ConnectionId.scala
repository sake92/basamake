package ba.sake.basamake.core

/** Typed wrapper for a BSP connection identifier (derived from .bsp/<name>.json filename). */
opaque type ConnectionId = String

object ConnectionId:
  def apply(value: String): ConnectionId = value
  extension (id: ConnectionId) def value: String = id
