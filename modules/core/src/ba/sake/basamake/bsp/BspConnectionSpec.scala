package ba.sake.basamake.bsp

import ba.sake.tupson.JsonRW


/** .bsp JSON file */
private case class BspDiscoveryFile(name: String, argv: List[String]) derives JsonRW

final case class BspConnectionSpec(
    content: BspDiscoveryFile,
    path: os.Path,
    debounceMs: Long = 500
) {
  val workingDir: os.Path = path / os.up / os.up
}
