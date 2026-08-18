package ba.sake.basamake.util

object ScalacOptionsUtils {

  /** Extracts the source root (`-sourceroot`) from scalac options.
    * Scala 3 accepts both `-sourceroot <dir>` and `-sourceroot:<dir>` (verified
    * on 3.7/3.8); Scala 2 semanticdb uses `-P:semanticdb:sourceroot:<dir>`.
    * None when not specified — callers must choose a sensible fallback
    * (NOT os.pwd: the LSP process cwd is unrelated to the build's source root). */
  def sourceRootDir(options: List[String]): Option[os.Path] = {
    val scala3Space = options.sliding(2).collect {
      case Seq("-sourceroot", path) if !path.startsWith("-") => os.Path(path)
    }.toList
    val scala3Colon = options.collect {
      case s if s.startsWith("-sourceroot:") =>
        os.Path(s.stripPrefix("-sourceroot:"))
    }
    val scala2 = options.collect {
      case s if s.startsWith("-P:semanticdb:sourceroot:") =>
        os.Path(s.stripPrefix("-P:semanticdb:sourceroot:"))
    }
    (scala3Space ++ scala3Colon ++ scala2).headOption
  }

  /** Extracts custom SemanticDB output paths from scalac options.
    * Scala 3 accepts both `-semanticdb-target <dir>` and `-semanticdb-target:<dir>`;
    * Scala 2 uses `-P:semanticdb:targetroot:<dir>`. */
  def semanticdbTargetPath(options: List[String]): Option[os.Path] = {
    val scala3Space = options.sliding(2).collect {
      case Seq("-semanticdb-target", path) if !path.startsWith("-") => os.Path(path)
    }.toList
    val scala3Colon = options.collect {
      case s if s.startsWith("-semanticdb-target:") =>
        os.Path(s.stripPrefix("-semanticdb-target:"))
    }
    val scala2 = options.collect {
      case s if s.startsWith("-P:semanticdb:targetroot:") =>
        os.Path(s.stripPrefix("-P:semanticdb:targetroot:"))
    }
    (scala3Space ++ scala3Colon ++ scala2).headOption
  }

  /** Checks if -Ybest-effort flag is present (allows indexing when compilation had errors). */
  def hasBestEffortFlag(options: List[String]): Boolean =
    options.contains("-Ybest-effort")
}
