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

  /** Extracts SemanticDB's Java compiler-plugin output path. `javac` receives
    * the plugin invocation as one option, for example
    * `-Xplugin:semanticdb -sourceroot:/workspace -targetroot:/semanticdb`.
    * Accept a standalone `-targetroot:` too because BSP servers may tokenize
    * compiler options differently. */
  def javacSemanticdbTargetPath(options: List[String]): Option[os.Path] =
    javacPluginOption(options, "-targetroot:")

  /** Extracts the Java SemanticDB plugin's optional source root. */
  def javacSourceRootDir(options: List[String]): Option[os.Path] =
    javacPluginOption(options, "-sourceroot:")

  private def javacPluginOption(options: List[String], prefix: String): Option[os.Path] =
    options.iterator.flatMap { option =>
      option.split("\\s+").iterator.collect {
        case token if token.startsWith(prefix) && token.length > prefix.length =>
          try Some(os.Path(token.stripPrefix(prefix)))
          catch { case _: Exception => None }
      }
    }.flatten.nextOption

  /** Checks if -Ybest-effort flag is present (allows indexing when compilation had errors). */
  def hasBestEffortFlag(options: List[String]): Boolean =
    options.contains("-Ybest-effort")
}
