package ba.sake.basamake.bsp

import java.nio.file.Path
import ba.sake.tupson.JsonRW


/** Tupson-parsed BSP connection spec from .bsp JSON files.
  * The `name` field is for BSP protocol display; buildToolName comes from the filename. */
private case class BspDiscoveryFile(name: String, argv: List[String]) derives JsonRW

final case class BspConnectionSpec(
    content: BspDiscoveryFile,
    path: Path,
    workingDir: Path,
    debounceMs: Long = 500
)
