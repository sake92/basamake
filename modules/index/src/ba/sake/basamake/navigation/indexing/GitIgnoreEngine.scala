package ba.sake.basamake.index.indexing

/** Directory names that never contain project sources — skipped by the workspace
  * walk regardless of gitignore rules (deder's ignoredDirNames + basamake extras). */
object GitIgnoreEngine {

  val alwaysSkipDirNames: Set[String] = Set(
    ".git", ".github", ".idea", ".vscode", ".metals", ".bsp", ".scala-build",
    "target", "out", ".basamake", ".deder", "node_modules"
  )

  /** Directories from `start` (inclusive) up to the first ancestor containing `.git`
    * (a dir for normal repos, a file for worktrees), ordered outermost-first.
    * If no `.git` is found in the chain: just `Vector(start)` — ancestor .gitignore
    * files above a non-git folder do not apply. */
  private[indexing] def ancestorChain(start: os.Path): Vector[os.Path] = {
    var chain = Vector.empty[os.Path]
    var cur: os.Path = start
    while true do {
      chain = cur +: chain
      if os.exists(cur / ".git") then return chain
      if cur == os.root then return Vector(start)
      cur = cur / os.up
    }
    Vector(start)
  }
}

/** Gitignore-aware ignore engine for a workspace root.
  *
  * Rules stack in git order (outermost first): `.gitignore` files from the git
  * boundary down to the walk root, the walk root's own `.gitignore`, then each
  * nested directory's `.gitignore` as the walk descends (loaded lazily + memoized).
  * Each layer's patterns match paths RELATIVE TO THAT LAYER'S DIRECTORY.
  * `extraRootPatterns` (e.g. BasamakeConfig.ignorePatterns) is appended as the last
  * layer — last match wins, so user config can override or negate .gitignore rules.
  *
  * `exemptLastNames`: path segments that are NEVER ignored (e.g. ".bsp" — gitignored
  * but required by BspDiscovery and the file watcher).
  *
  * Directories containing a `.git` entry (dir or file) below the root are
  * nested git repositories — workspace boundaries. Their contents are always
  * ignored, regardless of gitignore rules or exemptions.
  *
  * The rules cache is guarded by a reentrant lock, so concurrent `isIgnored`
  * calls (file-watcher thread + BSP debounce timer) are safe. `baseLayers` is
  * written only at construction; `reload()` takes the same lock. */
final class GitIgnoreEngine(
    root: os.Path,
    extraRootPatterns: Vector[String] = Vector.empty,
    exemptLastNames: Set[String] = Set.empty
) {

  private type Layer = (os.Path, Vector[String])

  private var baseLayers: Vector[Layer] = computeBaseLayers()

  // Plain map + reentrant lock. ConcurrentHashMap.computeIfAbsent is NOT usable
  // here: its mapping function must not modify the same map, and `rulesFor`
  // recurses via a nested lookup — when the nested key hashes to the same bin,
  // CHM throws IllegalStateException("Recursive update") (JDK ConcurrentHashMap:
  // `if (pred.next != null) throw ...`). Flaky under parallel test load.
  private val rulesLock = new Object
  private val rulesCache = new scala.collection.mutable.HashMap[os.Path, Vector[Layer]]()

  private def computeBaseLayers(): Vector[Layer] = {
    val chain = GitIgnoreEngine.ancestorChain(root)
    val gitignoreLayers = chain.flatMap { dir =>
      val f = dir / ".gitignore"
      if os.isFile(f) then Some(dir -> GitIgnore.readGitignorePatterns(f)) else None
    }
    val extraLayer =
      if extraRootPatterns.nonEmpty then Vector(root -> extraRootPatterns) else Vector.empty
    gitignoreLayers ++ extraLayer
  }

  /** Rules that apply to paths inside `dir`: base layers + nested .gitignore files
    * from the root down to `dir`. Memoized — each .gitignore parsed once. */
  private def rulesFor(dir: os.Path): Vector[Layer] = rulesLock.synchronized {
    rulesCache.getOrElseUpdate(dir, {
      if dir == root then baseLayers
      else if dir.startsWith(root) then rulesFor(dir / os.up) ++ ownLayer(dir)
      else baseLayers
    })
  }

  private def ownLayer(dir: os.Path): Vector[Layer] = {
    val f = dir / ".gitignore"
    if os.isFile(f) then Vector(dir -> GitIgnore.readGitignorePatterns(f)) else Vector.empty
  }

  /** True when `path` is inside a nested git repository: `path` itself (if a
    * dir) or any ancestor up to (excluding) `root` contains a `.git` entry — a
    * dir for normal repos, a file for worktrees/submodules. The root itself is
    * exempt: it IS the workspace. Paths outside the root return false. */
  def isInsideNestedRepo(path: os.Path): Boolean = {
    if !path.startsWith(root) then return false
    var cur = path
    while cur.startsWith(root) && cur != root do {
      if os.exists(cur / ".git") then return true
      cur = cur / os.up
    }
    false
  }

  /** True when `path` is ignored by the accumulated rules (last match wins across
    * all layers). A path is also ignored when ANY ancestor dir (up to, excluding,
    * the root) is ignored — git cannot re-include a file under an excluded dir.
    * Exempted names (and exempted ancestor dirs) are never ignored.
    * Paths outside the root are treated as ignored (safe for watcher filters). */
  def isIgnored(path: os.Path, isDir: Boolean): Boolean = {
    if !path.startsWith(root) then return true
    if isInsideNestedRepo(path) then return true
    if exemptLastNames.contains(path.last) then return false
    var cur = path / os.up
    while cur.startsWith(root) && cur != root do {
      if !exemptLastNames.contains(cur.last) && ignoredByRules(cur, isDir = true) then return true
      cur = cur / os.up
    }
    ignoredByRules(path, isDir)
  }

  private def ignoredByRules(path: os.Path, isDir: Boolean): Boolean = {
    val parent = if path == root then root else path / os.up
    var ignored = false
    for (base, patterns) <- rulesFor(parent) do
      val rel = path.relativeTo(base).toString
      for pattern <- patterns do
        if pattern.startsWith("!") then
          if GitIgnore.matchesPattern(pattern.stripPrefix("!"), rel, isDir) then ignored = false
        else
          if GitIgnore.matchesPattern(pattern, rel, isDir) then ignored = true
    ignored
  }

  /** Re-parse base layers after a `.gitignore` change; drops the nested-rules cache. */
  def reload(): Unit = rulesLock.synchronized {
    rulesCache.clear()
    baseLayers = computeBaseLayers()
  }
}
