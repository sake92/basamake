package ba.sake.basamake.util

object ScalacOptionsUtils {

  /** Detects any SemanticDB-related compiler flag in scalac options.
    * Supports: -Xsemanticdb, -semanticdb-target, -P:semanticdb:, -Xplugin:semanticdb */
  def hasSemanticdbFlags(options: List[String]): Boolean =
    options.exists(_ == "-Xsemanticdb") ||
      options.exists(s => s == "-semanticdb-target" || s.startsWith("-semanticdb-target:")) ||
      options.exists(_.startsWith("-P:semanticdb:")) ||
      options.exists(_ == "-Xplugin:semanticdb")

  /** Extracts custom SemanticDB output paths from scalac options.
    * Scala 3 uses space-separated and Scala 2 is colon-separated). */
  def semanticdbTargetPath(options: List[String]): Option[os.Path] = {
    val scala3 = options.sliding(2).collect {
      case Seq("-semanticdb-target", path) if !path.startsWith("-") => os.Path(path)
    }.toList
    val scala2 = options.collect {
      case s if s.startsWith("-P:semanticdb:targetroot:") =>
        os.Path(s.stripPrefix("-P:semanticdb:targetroot:"))
    }
    (scala3 ++ scala2).headOption
  }

  /** Checks if -Ybest-effort flag is present (allows indexing when compilation had errors). */
  def hasBestEffortFlag(options: List[String]): Boolean =
    options.contains("-Ybest-effort")
}
