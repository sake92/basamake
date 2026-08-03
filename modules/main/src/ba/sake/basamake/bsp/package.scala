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
    compileTimeoutSec: Long = 600,
    handshakeTimeoutSec: Long = 120,
    workspaceRoot: os.Path
) {
  val workingDir: os.Path = path / os.up / os.up
}

object BspConnectionSpec {
  /** Stable directory name for .basamake/bsp/ based on the .bsp file hash. */
  def dirName(spec: BspConnectionSpec): String = {
    val relPath = try spec.path.relativeTo(spec.workspaceRoot).toString
      catch { case _: Exception => spec.path.toString }
    val hash = java.security.MessageDigest.getInstance("SHA-256")
      .digest(relPath.getBytes("UTF-8"))
      .take(4).map(b => f"$b%02x").mkString
    s"${spec.content.name}_$hash"
  }
}
