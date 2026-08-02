package ba.sake.basamake.bsp

import ba.sake.tupson.JsonRW

/** Persisted BSP target metadata, written after each successful compile to
  * `.basamake/bsp/<name>_<hash>/data.json`. Read on startup by WorkspaceIndex
  * to know exactly where semanticdb files live — no walking needed.
  */
final case class BspTargetData(
    bspFile: String,  // relative path to .bsp JSON file
    targets: List[BspTargetInfo]
) derives JsonRW

final case class BspTargetInfo(
    id: String,               // BuildTargetIdentifier URI
    sourceRootDir: String,    // URI string of the source root (from BuildTarget)
    sourceDirs: List[String], // URI strings
    semanticdbDir: String     // URI string from scalacOptions, or class directory fallback
) derives JsonRW
