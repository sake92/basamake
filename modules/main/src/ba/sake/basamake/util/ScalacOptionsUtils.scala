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
    * Supports Scala 3 (colon- and space-separated) and Scala 2 (colon- and space-separated). */
  def semanticdbTargetPaths(options: List[String]): List[os.Path] = {
    // Scala 3: -semanticdb-target:<path> (colon-separated)
    val scala3 = options.collect {
      case s if s.startsWith("-semanticdb-target:") =>
        os.Path(s.stripPrefix("-semanticdb-target:"))
    }
    // Scala 2: -P:semanticdb:targetroot:<path> (colon-separated)
    val scala2 = options.collect {
      case s if s.startsWith("-P:semanticdb:targetroot:") =>
        os.Path(s.stripPrefix("-P:semanticdb:targetroot:"))
    }
    // Space-separated forms: flag followed by path in next element
    val space3 = options.sliding(2).collect {
      case Seq("-semanticdb-target", path) if !path.startsWith("-") => os.Path(path)
    }.toList
    val space2 = options.sliding(2).collect {
      case Seq("-P:semanticdb:targetroot", path) if !path.startsWith("-") => os.Path(path)
    }.toList
    scala3 ++ scala2 ++ space3 ++ space2
  }

  /** Checks if -Ybest-effort flag is present (allows indexing when compilation had errors). */
  def hasBestEffortFlag(options: List[String]): Boolean =
    options.contains("-Ybest-effort")
}
