package ba.sake.basamake.bsp

import ba.sake.tupson.JsonRW


/** Tupson-parsed BSP connection spec from .bsp JSON files.
  * The `name` field is for BSP protocol display; buildToolName comes from the filename. */
private case class BspDiscoveryFile(name: String, argv: List[String]) derives JsonRW

final case class BspConnectionSpec(
    content: BspDiscoveryFile,
    path: os.Path,
    workingDir: os.Path,
    debounceMs: Long = 500
)
