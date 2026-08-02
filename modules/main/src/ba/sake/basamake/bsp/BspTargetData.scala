package ba.sake.basamake.bsp

import ba.sake.tupson.JsonRW

given JsonRW[os.Path] with {
  def parse(path: String, jValue: org.typelevel.jawn.ast.JValue):os.Path = jValue match {
     case org.typelevel.jawn.ast.JString(s) => os.Path(s)
     case _ => throw new IllegalArgumentException(s"Expected JString for os.Path, got ${jValue}")
  }
  def write(path: os.Path): org.typelevel.jawn.ast.JValue = org.typelevel.jawn.ast.JString(path.toString())
}

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
    sourceRootDir: os.Path,    // URI string of the source root (from BuildTarget)
    //sourceDirs: List[os.Path], // URI strings
    semanticdbDir: os.Path     // URI string from scalacOptions, or class directory fallback
) derives JsonRW

