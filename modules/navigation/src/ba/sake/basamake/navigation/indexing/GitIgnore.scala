package ba.sake.basamake.navigation.indexing

/** gitignore pattern parsing + matching. Ported from deder's FileWatchUtils
  * (server/src/ba/sake/deder/FileWatchUtils.scala, same author).
  *
  * Supported pattern syntax:
  *   - `*` — matches any characters except /
  *   - `**` — matches zero or more directories
  *   - `?` — matches any single character except /
  *   - Leading `/` — anchors to the gitignore's own directory
  *   - Trailing `/` — matches directories only
  *   - `!` prefix — negation (un-ignores a path), last matching pattern wins */
object GitIgnore {

  /** Reads a .gitignore file and returns parsed patterns.
    * Strips comments (lines starting with #) and empty lines.
    * Preserves `!` prefix for negation support.
    * Returns empty Vector if the file does not exist. */
  def readGitignorePatterns(file: os.Path): Vector[String] =
    if os.exists(file) && os.isFile(file) then
      os.read.lines(file)
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("#"))
        .toVector
    else
      Vector.empty

  /** Matches a single gitignore pattern (WITHOUT the `!` prefix) against a path
    * relative to the gitignore file's own directory. */
  def matchesPattern(pattern: String, relativePath: String, isDir: Boolean): Boolean = {
    var p = pattern

    // Trailing / means match directories only
    if p.endsWith("/") then
      if !isDir then return false
      p = p.stripSuffix("/")

    // Normalize path: append / for directories so prefix matching works
    val normalizedPath = if isDir then relativePath + "/" else relativePath

    if p.contains("/") then
      // Pattern has path separator — match against full relative path
      val pClean = if p.startsWith("/") then p.stripPrefix("/") else p
      if pClean.contains("**") || pClean.contains("*") || pClean.contains("?") then
        // Strip trailing / from normalized dir paths for regex matching
        val regexPath = if normalizedPath.endsWith("/") then normalizedPath.stripSuffix("/") else normalizedPath
        globToRegex(pClean).matches(regexPath)
      else
        // Prefix match with path-boundary check so "build/output" matches
        // "build/output/" but NOT "build/output2.class"
        normalizedPath.startsWith(pClean)
        && (normalizedPath.length == pClean.length || normalizedPath.charAt(pClean.length) == '/')
    else
      // No separator — match against filename (last non-empty segment, so
      // bare patterns like "build" also match directories)
      val filename = normalizedPath.split("/").filter(_.nonEmpty).last
      simpleGlobMatch(p, filename)
  }

  /** Checks whether `relativePath` matches any pattern in the list.
    * Patterns are evaluated in order — the LAST matching pattern wins,
    * enabling proper `!` negation semantics. */
  def isIgnoredByGitignore(relativePath: String, isDir: Boolean, patterns: Seq[String]): Boolean = {
    var ignored = false
    for pattern <- patterns do
      if pattern.startsWith("!") then
        val p = pattern.stripPrefix("!")
        if matchesPattern(p, relativePath, isDir) then ignored = false
      else
        if matchesPattern(pattern, relativePath, isDir) then ignored = true
    ignored
  }

  /** Converts a glob pattern containing ** to a Regex. */
  private def globToRegex(pattern: String): scala.util.matching.Regex = {
    val sb = new StringBuilder
    sb.append("^")
    var i = 0
    while i < pattern.length do
      pattern.charAt(i) match
        case '*' =>
          if i + 1 < pattern.length && pattern.charAt(i + 1) == '*' then
            // ** followed by / should match zero or more directories
            if i + 2 < pattern.length && pattern.charAt(i + 2) == '/' then
              sb.append("(.*/)?")
              i += 2 // skip second * and the following /
              // skip the / (the i += 1 at end of loop will advance past it)
            else
              sb.append(".*")
              i += 1
          else
            sb.append("[^/]*")
        case '?' => sb.append("[^/]")
        case '.' => sb.append("\\.")
        case c   => sb.append(c)
      i += 1
    sb.append("$")
    sb.toString.r
  }

  /** Simple glob match for filename-only patterns (no / in pattern). */
  private def simpleGlobMatch(pattern: String, str: String): Boolean = {
    val regex = pattern
      .replace(".", "\\.")
      .replace("*", ".*")
      .replace("?", ".")
    str.matches(regex)
  }
}
