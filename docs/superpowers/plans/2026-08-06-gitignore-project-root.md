# Gitignore-Aware Indexing + Project-Root Resolution — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make basamake honor `.gitignore` rules when walking/discovering/watching the workspace (skipping `node_modules`, `.worktrees/`, `target/`, ...) and place `.basamake/` at a resolved project root (nearest ancestor with `.git` or an existing `.basamake/`) instead of every opened folder.

**Architecture:** A pure gitignore matcher ported from deder's `FileWatchUtils` + a new `GitIgnoreEngine` that stacks `.gitignore` layers (ancestor chain up to the git boundary, then nested dirs, then config patterns) with last-match-wins semantics. `WorkspaceIndex` (source walk), `BspDiscovery` (`.bsp` discovery, exempting gitignored `.bsp` dirs) and `BspManager` (file watcher filter, `.gitignore` reload) each build their own engine from the resolved root. `ProjectRoot.resolve` climbs from the opened folder.

**Tech Stack:** Scala 3.7.4, os-lib, munit, deder (build/test). Design spec: `docs/superpowers/specs/2026-08-06-gitignore-and-project-root-design.md`.

**Repo layout (all paths relative to worktree root):**
- `modules/navigation/src/ba/sake/basamake/navigation/indexing/GitIgnore.scala` — pattern parse/match (ported)
- `modules/navigation/src/ba/sake/basamake/navigation/indexing/GitIgnoreEngine.scala` — layer stack, boundary, cache, exemptions
- `modules/main/src/ba/sake/basamake/util/ProjectRoot.scala` — root resolution
- `modules/main/src/ba/sake/basamake/Main.scala` — resolve + log root
- `modules/main/src/ba/sake/basamake/config/BasamakeConfig.scala` — `ignorePatterns`
- `modules/navigation/src/ba/sake/basamake/navigation/indexing/WorkspaceIndex.scala` — engine-based walk skip
- `modules/main/src/ba/sake/basamake/bsp/BspDiscovery.scala` — engine-aware discovery
- `modules/main/src/ba/sake/basamake/bsp/BspManager.scala` — engine, watcher filter, `.gitignore` reload
- `modules/main/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala` — pass config patterns to WorkspaceIndex
- Tests: `GitIgnoreTest`, `GitIgnoreEngineTest`, `ProjectRootTest`, `BspDiscoveryTest`, `BspManagerWatchIgnoreTest`, `BasamakeConfigTest` (new); `WorkspaceIndexTest` (extended)
- Docs: `AGENTS.md`, `TODO.md`

**Test commands:**
- navigation tests: `deder exec -t test -m navigation-test`
- main tests: `deder exec -t test -m main-test`
- everything: `deder exec -t test`

**Style note (AGENTS.md):** braceless syntax (`:`) only for bodies ≤3 lines; longer bodies use curly braces. Follow the existing code.

---

### Task 1: Port deder gitignore matcher — `GitIgnore.scala`

**Files:**
- Create: `modules/navigation/src/ba/sake/basamake/navigation/indexing/GitIgnore.scala`
- Create: `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/GitIgnoreTest.scala`

- [ ] **Step 1: Write the failing test**

Create `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/GitIgnoreTest.scala`:

```scala
package ba.sake.basamake.navigation.indexing

import munit.FunSuite

class GitIgnoreTest extends FunSuite {

  private val root = os.temp.dir(prefix = "gitignore-")

  override def afterAll(): Unit = os.remove.all(root)

  // ========= readGitignorePatterns =========

  test("readGitignorePatterns: strips comments and empty lines") {
    val gitignore = root / ".gitignore"
    os.write(gitignore,
      """|# This is a comment
         |
         |*.class
         |# another comment
         |build/
         |""".stripMargin)
    val patterns = GitIgnore.readGitignorePatterns(gitignore)
    assertEquals(patterns, Vector("*.class", "build/"))
  }

  test("readGitignorePatterns: preserves ! prefix") {
    val gitignore = root / ".gitignore-preserves"
    os.write(gitignore,
      """|*.class
         |!Important.class
         |""".stripMargin)
    val patterns = GitIgnore.readGitignorePatterns(gitignore)
    assertEquals(patterns, Vector("*.class", "!Important.class"))
  }

  test("readGitignorePatterns: returns empty for non-existent file") {
    val patterns = GitIgnore.readGitignorePatterns(root / "nonexistent")
    assertEquals(patterns, Vector.empty)
  }

  test("readGitignorePatterns: handles file without trailing newline") {
    val gitignore = root / ".gitignore-notrail"
    os.write(gitignore, "*.class")
    val patterns = GitIgnore.readGitignorePatterns(gitignore)
    assertEquals(patterns, Vector("*.class"))
  }

  // ========= isIgnoredByGitignore =========

  test("isIgnoredByGitignore: simple glob matches filename") {
    val patterns = Seq("*.class")
    assert(GitIgnore.isIgnoredByGitignore("Foo.class", isDir = false, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("Foo.scala", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: directory-only pattern (trailing /)") {
    val patterns = Seq("build/")
    assert(GitIgnore.isIgnoredByGitignore("build", isDir = true, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("build", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: directory-only pattern does not match file with same name") {
    val patterns = Seq("logs/")
    assert(!GitIgnore.isIgnoredByGitignore("logs", isDir = false, patterns))
    assert(GitIgnore.isIgnoredByGitignore("logs", isDir = true, patterns))
  }

  test("isIgnoredByGitignore: ** glob matches nested paths") {
    val patterns = Seq("**/*.class")
    assert(GitIgnore.isIgnoredByGitignore("Foo.class", isDir = false, patterns))
    assert(GitIgnore.isIgnoredByGitignore("bar/Foo.class", isDir = false, patterns))
    assert(GitIgnore.isIgnoredByGitignore("a/b/c/Foo.class", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: **/build/ matches nested build directories") {
    val patterns = Seq("**/build/")
    assert(GitIgnore.isIgnoredByGitignore("build", isDir = true, patterns))
    assert(GitIgnore.isIgnoredByGitignore("foo/build", isDir = true, patterns))
    assert(GitIgnore.isIgnoredByGitignore("a/b/build", isDir = true, patterns))
  }

  test("isIgnoredByGitignore: leading / anchors to root") {
    val patterns = Seq("/build/")
    assert(GitIgnore.isIgnoredByGitignore("build", isDir = true, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("src/build", isDir = true, patterns))
  }

  test("isIgnoredByGitignore: negation (!) un-ignores a path") {
    val patterns = Seq("*.class", "!Important.class")
    assert(!GitIgnore.isIgnoredByGitignore("Important.class", isDir = false, patterns))
    assert(GitIgnore.isIgnoredByGitignore("Other.class", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: last matching pattern wins for negation") {
    val patterns = Seq("!Important.class", "*.class")
    assert(GitIgnore.isIgnoredByGitignore("Important.class", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: path-style pattern matches from root with boundary check") {
    val patterns = Seq("target/scala-3")
    assert(GitIgnore.isIgnoredByGitignore("target/scala-3", isDir = true, patterns))
    assert(GitIgnore.isIgnoredByGitignore("target/scala-3/classes", isDir = true, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("src/target/scala-3", isDir = true, patterns))
  }

  test("isIgnoredByGitignore: prefix match does not match sibling prefixes") {
    val patterns = Seq("build/output")
    assert(GitIgnore.isIgnoredByGitignore("build/output", isDir = false, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("build/output2.class", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: empty patterns list matches nothing") {
    val patterns = Seq.empty[String]
    assert(!GitIgnore.isIgnoredByGitignore("anything.txt", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: * matches any single directory component") {
    val patterns = Seq("foo/*/bar")
    assert(GitIgnore.isIgnoredByGitignore("foo/x/bar", isDir = false, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("foo/x/y/bar", isDir = false, patterns))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `deder exec -t test -m navigation-test`
Expected: FAIL — compilation error `object GitIgnore is not a member of package ...indexing`

- [ ] **Step 3: Write the implementation**

Create `modules/navigation/src/ba/sake/basamake/navigation/indexing/GitIgnore.scala` (ported from deder's `FileWatchUtils.scala`, same author):

```scala
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
      // No separator — match against filename
      val filename = normalizedPath.split("/").last
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `deder exec -t test -m navigation-test`
Expected: PASS — `GitIgnoreTest` green (15 tests), no other failures.

- [ ] **Step 5: Commit**

```bash
git add modules/navigation/src/ba/sake/basamake/navigation/indexing/GitIgnore.scala modules/navigation/test/src/ba/sake/basamake/navigation/indexing/GitIgnoreTest.scala
git commit -m "Port gitignore matcher from deder FileWatchUtils"
```

---

### Task 2: `GitIgnoreEngine` — layer stack, git boundary, cache, exemptions

**Files:**
- Create: `modules/navigation/src/ba/sake/basamake/navigation/indexing/GitIgnoreEngine.scala`
- Create: `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/GitIgnoreEngineTest.scala`

- [ ] **Step 1: Write the failing test**

Create `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/GitIgnoreEngineTest.scala`:

```scala
package ba.sake.basamake.navigation.indexing

import munit.FunSuite

class GitIgnoreEngineTest extends FunSuite {

  private def withRoot[T](f: os.Path => T): T = {
    val root = os.temp.dir(prefix = "gignore-")
    try f(root)
    finally os.remove.all(root)
  }

  test("root .gitignore: dir pattern prunes subtree") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "build/\n")
      val engine = new GitIgnoreEngine(root)
      assert(engine.isIgnored(root / "build", isDir = true))
      assert(engine.isIgnored(root / "build" / "out" / "x.scala", isDir = false))
      assert(!engine.isIgnored(root / "src", isDir = true))
      assert(!engine.isIgnored(root / "src" / "Main.scala", isDir = false))
    }
  }

  test("nested .gitignore applies relative to its own dir") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.class\n")
      os.write(root / "sub" / ".gitignore", "!Important.class\n/deep/\n")
      val engine = new GitIgnoreEngine(root)
      // root pattern matches any depth via filename
      assert(engine.isIgnored(root / "Other.class", isDir = false))
      assert(engine.isIgnored(root / "sub" / "Other.class", isDir = false))
      // nested negation wins over root pattern
      assert(!engine.isIgnored(root / "sub" / "Important.class", isDir = false))
      // nested anchored dir pattern applies only below sub/
      assert(engine.isIgnored(root / "sub" / "deep", isDir = true))
      assert(!engine.isIgnored(root / "deep", isDir = true))
      assert(!engine.isIgnored(root / "sub" / "x" / "deep", isDir = true))
    }
  }

  test("ancestor chain: repo-root .gitignore above the walk root is honored") {
    withRoot { base =>
      os.makeDir.all(base / ".git")
      os.write(base / ".gitignore", "ignored/\n")
      val root = base / "proj"
      os.makeDir.all(root / "src")
      val engine = new GitIgnoreEngine(root)
      assert(engine.isIgnored(root / "ignored", isDir = true))
      assert(!engine.isIgnored(root / "src", isDir = true))
    }
  }

  test("no .git in ancestor chain: only the walk root's own .gitignore is honored") {
    withRoot { base =>
      os.write(base / ".gitignore", "ignored/\n")
      val root = base / "proj"
      os.makeDir.all(root / "src")
      os.write(root / ".gitignore", "src/\n")
      val engine = new GitIgnoreEngine(root)
      // base/.gitignore must NOT apply (no git boundary)
      assert(!engine.isIgnored(root / "ignored", isDir = true))
      assert(engine.isIgnored(root / "src", isDir = true))
    }
  }

  test("worktree: .git as a file stops the ancestor chain") {
    withRoot { base =>
      os.makeDir.all(base / ".git")
      os.write(base / ".gitignore", "ignored/\n")
      val root = base / "wt"
      os.makeDir.all(root / "src")
      os.write(root / ".git", "gitdir: /main/.git/worktrees/wt\n")
      val engine = new GitIgnoreEngine(root)
      // boundary is the worktree itself — base/.gitignore must NOT apply
      assert(!engine.isIgnored(root / "ignored", isDir = true))
      assert(!engine.isIgnored(root / "src", isDir = true))
    }
  }

  test("no gitignore anywhere: nothing is ignored") {
    withRoot { root =>
      os.makeDir.all(root / "src")
      val engine = new GitIgnoreEngine(root)
      assert(!engine.isIgnored(root / "src" / "Main.scala", isDir = false))
      assert(!engine.isIgnored(root / "node_modules", isDir = true))
    }
  }

  test("extraRootPatterns merge: config patterns override gitignore (last match wins)") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.log\n")
      val engine = new GitIgnoreEngine(root, extraRootPatterns = Vector("!important.log"))
      assert(engine.isIgnored(root / "debug.log", isDir = false))
      assert(!engine.isIgnored(root / "important.log", isDir = false))
    }
  }

  test("exemptLastNames: exempted names are never ignored") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".bsp/\n")
      val exempt = new GitIgnoreEngine(root, exemptLastNames = Set(".bsp"))
      assert(!exempt.isIgnored(root / ".bsp", isDir = true))
      assert(!exempt.isIgnored(root / ".bsp" / "sbt.json", isDir = false))
      val strict = new GitIgnoreEngine(root)
      assert(strict.isIgnored(root / ".bsp", isDir = true))
    }
  }

  test("reload: re-parses base layers after .gitignore change") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.class\n")
      val engine = new GitIgnoreEngine(root)
      assert(engine.isIgnored(root / "Foo.class", isDir = false))
      assert(!engine.isIgnored(root / "Foo.scala", isDir = false))
      os.write(root / ".gitignore", "*.scala\n")
      engine.reload()
      assert(!engine.isIgnored(root / "Foo.class", isDir = false))
      assert(engine.isIgnored(root / "Foo.scala", isDir = false))
    }
  }

  test("file under an ignored dir is ignored (ancestor pruning)") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".worktrees/\n")
      val engine = new GitIgnoreEngine(root)
      assert(engine.isIgnored(root / ".worktrees" / "wt" / "Foo.scala", isDir = false))
      assert(engine.isIgnored(root / ".worktrees" / "wt", isDir = true))
    }
  }

  test("paths outside the root are ignored (safe default)") {
    withRoot { root =>
      val engine = new GitIgnoreEngine(root)
      assert(engine.isIgnored(root / os.up / "elsewhere" / "x.scala", isDir = false))
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `deder exec -t test -m navigation-test`
Expected: FAIL — compilation error `object GitIgnoreEngine is not a member of package ...indexing`

- [ ] **Step 3: Write the implementation**

Create `modules/navigation/src/ba/sake/basamake/navigation/indexing/GitIgnoreEngine.scala`:

```scala
package ba.sake.basamake.navigation.indexing

import scala.collection.mutable

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
      val parent = cur / os.up
      if parent == cur then return Vector(start)
      cur = parent
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
  * Used from a single thread per instance (watcher thread / synchronized initialize),
  * so the rules cache needs no synchronization. */
final class GitIgnoreEngine(
    root: os.Path,
    extraRootPatterns: Vector[String] = Vector.empty,
    exemptLastNames: Set[String] = Set.empty
) {

  private type Layer = (os.Path, Vector[String])

  private var baseLayers: Vector[Layer] = computeBaseLayers()

  private val rulesCache = mutable.Map.empty[os.Path, Vector[Layer]]

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
  private def rulesFor(dir: os.Path): Vector[Layer] =
    rulesCache.getOrElseUpdate(dir, {
      if dir == root then baseLayers
      else if dir.startsWith(root) then rulesFor(dir / os.up) ++ ownLayer(dir)
      else baseLayers
    })

  private def ownLayer(dir: os.Path): Vector[Layer] = {
    val f = dir / ".gitignore"
    if os.isFile(f) then Vector(dir -> GitIgnore.readGitignorePatterns(f)) else Vector.empty
  }

  /** True when `path` is ignored by the accumulated rules (last match wins across
    * all layers). A path is also ignored when ANY ancestor dir (up to, excluding,
    * the root) is ignored — git cannot re-include a file under an excluded dir.
    * Exempted names (and exempted ancestor dirs) are never ignored.
    * Paths outside the root are treated as ignored (safe for watcher filters). */
  def isIgnored(path: os.Path, isDir: Boolean): Boolean = {
    if exemptLastNames.contains(path.last) then return false
    if !path.startsWith(root) then return true
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
  def reload(): Unit = {
    rulesCache.clear()
    baseLayers = computeBaseLayers()
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `deder exec -t test -m navigation-test`
Expected: PASS — `GitIgnoreEngineTest` green (10 tests), `GitIgnoreTest` still green.

- [ ] **Step 5: Commit**

```bash
git add modules/navigation/src/ba/sake/basamake/navigation/indexing/GitIgnoreEngine.scala modules/navigation/test/src/ba/sake/basamake/navigation/indexing/GitIgnoreEngineTest.scala
git commit -m "Add GitIgnoreEngine: layered gitignore rules with git boundary + cache"
```

---

### Task 3: `ProjectRoot.resolve` + `Main` wiring

**Files:**
- Create: `modules/main/src/ba/sake/basamake/util/ProjectRoot.scala`
- Create: `modules/main/test/src/ba/sake/basamake/util/ProjectRootTest.scala`
- Modify: `modules/main/src/ba/sake/basamake/Main.scala` (lines 1-43)

- [ ] **Step 1: Write the failing test**

Create `modules/main/test/src/ba/sake/basamake/util/ProjectRootTest.scala`:

```scala
package ba.sake.basamake.util

import munit.FunSuite

class ProjectRootTest extends FunSuite {

  private def withRoot[T](f: os.Path => T): T = {
    val root = os.temp.dir(prefix = "proot-")
    try f(root)
    finally os.remove.all(root)
  }

  test("existing .basamake in an ancestor → that ancestor (sbt subfolder case)") {
    withRoot { root =>
      os.makeDir.all(root / "examples" / "hello" / ".basamake")
      os.makeDir.all(root / "examples" / "hello" / "sbt")
      val opened = root / "examples" / "hello" / "sbt"
      assertEquals(ProjectRoot.resolve(opened), root / "examples" / "hello")
    }
  }

  test(".git dir → git root") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / "sub" / "deep")
      assertEquals(ProjectRoot.resolve(root / "sub" / "deep"), root)
    }
  }

  test(".git file (worktree) → the worktree itself, not the main repo") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / ".worktrees" / "feat" / "src")
      os.write(root / ".worktrees" / "feat" / ".git", "gitdir: /main/.git/worktrees/feat\n")
      assertEquals(ProjectRoot.resolve(root / ".worktrees" / "feat" / "src"),
        root / ".worktrees" / "feat")
    }
  }

  test("fresh repo without .basamake → git root") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / "modules" / "main" / "src")
      assertEquals(ProjectRoot.resolve(root / "modules" / "main" / "src"), root)
    }
  }

  test("non-git folder without marker → opened folder") {
    withRoot { root =>
      os.makeDir.all(root / "a" / "b")
      assertEquals(ProjectRoot.resolve(root / "a" / "b"), root / "a" / "b")
    }
  }

  test("opened folder with both .git and .basamake → itself") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / ".basamake")
      assertEquals(ProjectRoot.resolve(root), root)
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `deder exec -t test -m main-test`
Expected: FAIL — compilation error `object ProjectRoot is not a member of package ...util`

- [ ] **Step 3: Write the implementation**

Create `modules/main/src/ba/sake/basamake/util/ProjectRoot.scala`:

```scala
package ba.sake.basamake.util

/** Project-root resolution: basamake's `.basamake/` (logs, config, data.json, source
  * walk, .bsp discovery) lives at the first ancestor of the opened folder that is
  * either a git root (`.git` dir or file — a file marks a git worktree) or an
  * existing workspace marker (`.basamake/` dir). Non-git folders without a marker
  * fall back to the opened folder itself. */
object ProjectRoot {

  def resolve(openedDir: os.Path): os.Path = {
    var cur = openedDir
    while true do {
      if os.exists(cur / ".git") then return cur
      if os.isDir(cur / ".basamake") then return cur
      val parent = cur / os.up
      if parent == cur then return openedDir
      cur = parent
    }
    openedDir
  }
}
```

Modify `modules/main/src/ba/sake/basamake/Main.scala`:

Current lines 21-29:
```scala
    LoggingUtils.configureFileLogging(os.Path(workspace))
    logger.info(s"Basamake LSP server starting in workspace: $workspace")
    logger.info(s"Java: ${System.getProperty("java.version")}")

    val autoFlushOut = PrintStream(System.out, true, "UTF-8")
    val server = BasamakeLanguageServer(os.Path(workspace))
```

Replace with:
```scala
    val openedDir = os.Path(workspace)
    val projectRoot = ProjectRoot.resolve(openedDir)
    val marker =
      if os.exists(projectRoot / ".git") then ".git"
      else if os.isDir(projectRoot / ".basamake") then ".basamake"
      else "fallback (opened folder)"
    LoggingUtils.configureFileLogging(projectRoot)
    logger.info(s"Basamake LSP server starting; opened: $openedDir, project root: $projectRoot (marker: $marker)")
    logger.info(s"Java: ${System.getProperty("java.version")}")

    val autoFlushOut = PrintStream(System.out, true, "UTF-8")
    val server = BasamakeLanguageServer(projectRoot)
```

Also add the import at the top (after the existing `ba.sake.basamake.util.LoggingUtils` import):
```scala
import ba.sake.basamake.util.{LoggingUtils, ProjectRoot}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `deder exec -t test -m main-test`
Expected: PASS — `ProjectRootTest` green (6 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/main/src/ba/sake/basamake/util/ProjectRoot.scala modules/main/test/src/ba/sake/basamake/util/ProjectRootTest.scala modules/main/src/ba/sake/basamake/Main.scala
git commit -m "Resolve project root (nearest .git or existing .basamake) in Main"
```

---

### Task 4: `BasamakeConfig.ignorePatterns`

**Files:**
- Modify: `modules/main/src/ba/sake/basamake/config/BasamakeConfig.scala` (lines 5-7)
- Create: `modules/main/test/src/ba/sake/basamake/config/BasamakeConfigTest.scala`

- [ ] **Step 1: Write the failing test**

Create `modules/main/test/src/ba/sake/basamake/config/BasamakeConfigTest.scala`:

```scala
package ba.sake.basamake.config

import munit.FunSuite

class BasamakeConfigTest extends FunSuite {

  private val root = os.temp.dir(prefix = "bconfig-")

  override def afterAll(): Unit = os.remove.all(root)

  test("load: parses ignorePatterns from config.json") {
    val proj = root / "proj"
    os.makeDir.all(proj / ".basamake")
    os.write(proj / ".basamake" / "config.json",
      """{"ignorePatterns": ["node_modules/", "!node_modules/keep.scala"]}""")
    val cfg = BasamakeConfig.load(proj)
    assertEquals(cfg.ignorePatterns, List("node_modules/", "!node_modules/keep.scala"))
    assertEquals(cfg.bspOverrides, Nil)
  }

  test("load: missing config file → defaults") {
    val proj = root / "empty"
    os.makeDir.all(proj)
    val cfg = BasamakeConfig.load(proj)
    assertEquals(cfg.ignorePatterns, Nil)
    assertEquals(cfg.bspOverrides, Nil)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `deder exec -t test -m main-test`
Expected: FAIL — compilation error: `Found: List[String("node_modules/", ...)] Required: Nil` (case class has no `ignorePatterns` field yet).

- [ ] **Step 3: Write the implementation**

Modify `modules/main/src/ba/sake/basamake/config/BasamakeConfig.scala` — current:

```scala
final case class BasamakeConfig(
    bspOverrides: List[BspOverride] = Nil
) derives JsonRW
```

Replace with:

```scala
final case class BasamakeConfig(
    bspOverrides: List[BspOverride] = Nil,
    /** Extra ignore patterns in gitignore syntax, relative to the project root.
      * Merged AFTER .gitignore rules — last match wins, so they can override or
      * negate .gitignore entries. Mirrors deder's watchIgnore. */
    ignorePatterns: List[String] = Nil
) derives JsonRW
```

- [ ] **Step 4: Run test to verify it passes**

Run: `deder exec -t test -m main-test`
Expected: PASS — `BasamakeConfigTest` green (2 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/main/src/ba/sake/basamake/config/BasamakeConfig.scala modules/main/test/src/ba/sake/basamake/config/BasamakeConfigTest.scala
git commit -m "Add ignorePatterns to BasamakeConfig (gitignore syntax, merged last)"
```

---

### Task 5: `WorkspaceIndex` gitignore-aware walk

**Files:**
- Modify: `modules/navigation/src/ba/sake/basamake/navigation/indexing/WorkspaceIndex.scala` (lines 10, 26-41)
- Modify: `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/WorkspaceIndexTest.scala` (append tests)
- Modify: `modules/main/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala` (lines 20-22)

- [ ] **Step 1: Write the failing tests**

Append to `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/WorkspaceIndexTest.scala` (inside the class, after the last test):

```scala
  // ═══════════════════════════════════════════════════════════════
  // gitignore-aware source walk
  // ═══════════════════════════════════════════════════════════════

  test("gitignore: node_modules/.worktrees/target are not indexed") {
    val root = os.temp.dir(prefix = "ws-gitignore-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "node_modules/\n.worktrees/\ntarget/\n")
      os.write(root / "src" / "Main.scala", "class RealMain\n")
      os.write(root / "node_modules" / "dep" / "Dep.scala", "class NodeDep\n")
      os.write(root / ".worktrees" / "wt" / "Other.scala", "class WorktreeOther\n")
      os.write(root / "target" / "gen" / "Gen.scala", "class GeneratedThing\n")
      val (_, st) = freshIndexAt(root)
      assert(st.get("_empty_/RealMain.").isDefined, "src/Main.scala should be indexed")
      assert(st.get("_empty_/NodeDep.").isEmpty, "node_modules should be skipped")
      assert(st.get("_empty_/WorktreeOther.").isEmpty, ".worktrees should be skipped")
      assert(st.get("_empty_/GeneratedThing.").isEmpty, "target should be skipped")
    } finally os.remove.all(root)
  }

  test("gitignore: negation re-includes a file") {
    val root = os.temp.dir(prefix = "ws-gitignore-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.generated.scala\n!keep.generated.scala\n")
      os.write(root / "a.generated.scala", "class GenA\n")
      os.write(root / "keep.generated.scala", "class KeepGen\n")
      val (_, st) = freshIndexAt(root)
      assert(st.get("_empty_/GenA.").isEmpty, "*.generated.scala should be skipped")
      assert(st.get("_empty_/KeepGen.").isDefined, "!keep.generated.scala should be re-included")
    } finally os.remove.all(root)
  }

  test("gitignore: nested .gitignore applies relative to its own dir") {
    val root = os.temp.dir(prefix = "ws-gitignore-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / "src" / ".gitignore", "build/\n")
      os.write(root / "src" / "Main.scala", "class SubMain\n")
      os.write(root / "src" / "build" / "B.scala", "class SubBuild\n")
      os.write(root / "build" / "RootB.scala", "class RootBuild\n")
      val (_, st) = freshIndexAt(root)
      assert(st.get("_empty_/SubMain.").isDefined, "src/Main.scala should be indexed")
      assert(st.get("_empty_/SubBuild.").isEmpty, "src/build should be skipped (nested rule)")
      assert(st.get("_empty_/RootBuild.").isDefined, "root build/ must NOT be skipped by nested rule")
    } finally os.remove.all(root)
  }

  test("gitignore: ignorePatterns constructor param is honored") {
    val root = os.temp.dir(prefix = "ws-gitignore-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / "src" / "Main.scala", "class RealMain\n")
      os.write(root / "src" / "Gen.scala", "class GenByConfig\n")
      val st = new SymbolTable
      val idx = new WorkspaceIndex(root, st, ignorePatterns = Vector("src/Gen.scala"))
      idx.initialize(List.empty)
      assert(st.get("_empty_/RealMain.").isDefined)
      assert(st.get("_empty_/GenByConfig.").isEmpty, "config pattern should skip src/Gen.scala")
    } finally os.remove.all(root)
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `deder exec -t test -m navigation-test`
Expected: FAIL — `gitignore: node_modules/...` test fails: `NodeDep` IS in the symbol table (hardcoded skip list doesn't cover `.worktrees`/`target`, and the new `ignorePatterns` param doesn't compile).

- [ ] **Step 3: Write the implementation**

Modify `modules/navigation/src/ba/sake/basamake/navigation/indexing/WorkspaceIndex.scala`:

Current line 10:
```scala
class WorkspaceIndex(workspacePath: os.Path, symbolTable: SymbolTable) extends StrictLogging {
```
Replace with:
```scala
class WorkspaceIndex(workspacePath: os.Path, symbolTable: SymbolTable, ignorePatterns: Vector[String] = Vector.empty) extends StrictLogging {
```

Current lines 27-33:
```scala
    logger.info(s"Initializing workspace index at $workspacePath")
    val skipDirNames = Set(".git", ".basamake", ".metals", ".bsp", "node_modules")
    val relevantExtensions = Set("scala", "java")
    def skip(p: os.Path): Boolean =
      if os.isDir(p) then skipDirNames.contains(p.last)
      else if os.isFile(p) then !relevantExtensions.contains(p.ext)
      else true
```
Replace with:
```scala
    logger.info(s"Initializing workspace index at $workspacePath")
    val engine = new GitIgnoreEngine(workspacePath, ignorePatterns)
    val relevantExtensions = Set("scala", "java")
    def skip(p: os.Path): Boolean =
      if os.isDir(p) then GitIgnoreEngine.alwaysSkipDirNames.contains(p.last) || engine.isIgnored(p, isDir = true)
      else if os.isFile(p) then !relevantExtensions.contains(p.ext) || engine.isIgnored(p, isDir = false)
      else true
```

Modify `modules/main/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala`:

Current line 20-22:
```scala
  private val symbolTable = new SymbolTable
  private val workspaceIndex = new WorkspaceIndex(workspacePath, symbolTable)
  private val bspManager = BspManager(workspacePath, workspaceIndex)
```
Replace with:
```scala
  private val symbolTable = new SymbolTable
  private val workspaceIndex = new WorkspaceIndex(
    workspacePath,
    symbolTable,
    BasamakeConfig.load(workspacePath).ignorePatterns.toVector
  )
  private val bspManager = BspManager(workspacePath, workspaceIndex)
```

Add the import (next to the existing `ba.sake.basamake.bsp.{BspManager, BspTargetData}` import on line 13):
```scala
import ba.sake.basamake.config.BasamakeConfig
```

- [ ] **Step 4: Run test to verify it passes**

Run: `deder exec -t test -m navigation-test` then `deder exec -t test -m main-test`
Expected: PASS — 4 new WorkspaceIndexTest tests green; all existing tests still green.

- [ ] **Step 5: Commit**

```bash
git add modules/navigation/src/ba/sake/basamake/navigation/indexing/WorkspaceIndex.scala modules/navigation/test/src/ba/sake/basamake/navigation/indexing/WorkspaceIndexTest.scala modules/main/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala
git commit -m "Make source walk gitignore-aware (engine + config patterns)"
```

---

### Task 6: `BspDiscovery` gitignore-aware discovery

**Files:**
- Modify: `modules/main/src/ba/sake/basamake/bsp/BspDiscovery.scala` (lines 10-31)
- Create: `modules/main/test/src/ba/sake/basamake/bsp/BspDiscoveryTest.scala`

- [ ] **Step 1: Write the failing test**

Create `modules/main/test/src/ba/sake/basamake/bsp/BspDiscoveryTest.scala`:

```scala
package ba.sake.basamake.bsp

import munit.FunSuite
import ba.sake.basamake.navigation.indexing.GitIgnoreEngine

class BspDiscoveryTest extends FunSuite {

  private def engine(root: os.Path): GitIgnoreEngine =
    new GitIgnoreEngine(root, exemptLastNames = Set(".bsp"))

  private val sbtJson =
    """{"name":"sbt","version":"1","bspVersion":"2.1.0","languages":["scala"],"argv":["true"]}"""

  test("gitignored .bsp dir is still discovered") {
    val root = os.temp.dir(prefix = "bsp-disc-")
    try {
      os.write(root / ".gitignore", ".bsp/\n")
      os.makeDir.all(root / ".bsp")
      os.write(root / ".bsp" / "sbt.json", sbtJson)
      val specs = BspDiscovery.discover(root, engine(root))
      assertEquals(specs.map(_.content.name), List("sbt"))
    } finally os.remove.all(root)
  }

  test(".bsp inside gitignored .worktrees is not discovered") {
    val root = os.temp.dir(prefix = "bsp-disc-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".worktrees/\n")
      os.makeDir.all(root / ".bsp")
      os.write(root / ".bsp" / "sbt.json", sbtJson)
      os.makeDir.all(root / ".worktrees" / "wt" / ".bsp")
      os.write(root / ".worktrees" / "wt" / ".bsp" / "sbt.json", sbtJson)
      val specs = BspDiscovery.discover(root, engine(root))
      assertEquals(specs.map(_.content.name), List("sbt"))
    } finally os.remove.all(root)
  }

  test(".bsp inside node_modules is not discovered") {
    val root = os.temp.dir(prefix = "bsp-disc-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "node_modules/\n")
      os.makeDir.all(root / ".bsp")
      os.write(root / ".bsp" / "sbt.json", sbtJson)
      os.makeDir.all(root / "node_modules" / "pkg" / ".bsp")
      os.write(root / "node_modules" / "pkg" / ".bsp" / "sbt.json", sbtJson)
      val specs = BspDiscovery.discover(root, engine(root))
      assertEquals(specs.map(_.content.name), List("sbt"))
    } finally os.remove.all(root)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `deder exec -t test -m main-test`
Expected: FAIL — compilation error: `discover` now takes 2 arguments (after step 3's signature is referenced by the test; the current 1-arg version makes the test fail to compile).

- [ ] **Step 3: Write the implementation**

Modify `modules/main/src/ba/sake/basamake/bsp/BspDiscovery.scala`:

Current lines 8-31:
```scala
  /** Discover ALL .bsp JSON files recursively under workspace root.
    */
  def discover(workspaceRoot: os.Path): List[BspConnectionSpec] = {
    val jsonFiles = findBspJsonFiles(workspaceRoot)
    if jsonFiles.isEmpty then
      logger.warn(s"No .bsp directories found under $workspaceRoot")
    jsonFiles.toList.sortBy(_.toString).flatMap(parseBspSpec(_, workspaceRoot))
  }

  /** Parse a single .bsp JSON file. Public for the file watcher. */
  def parseSingleSpec(jsonPath: os.Path, workspaceRoot: os.Path): Option[BspConnectionSpec] =
    parseBspSpec(jsonPath, workspaceRoot)

  private def findBspJsonFiles(workspaceRoot: os.Path): Set[os.Path] =
    findBspDirs(workspaceRoot).flatMap { bspDir =>
      logger.debug(s"Searching for .bsp JSON files in $bspDir")
      os.list(bspDir).filter(p => p.last.endsWith(".json"))
    }
    .toSet

  private def findBspDirs(root: os.Path): List[os.Path] =
    os.walk(root, maxDepth = 10)
      .filter(p => os.isDir(p) && p.last == ".bsp")
      .toList
```

Replace with:
```scala
  /** Discover ALL .bsp JSON files recursively under workspace root.
    * Gitignored directories are pruned, EXCEPT `.bsp` dirs themselves — they are
    * typically gitignored but essential. Pass an engine built with
    * `exemptLastNames = Set(".bsp")`. */
  def discover(workspaceRoot: os.Path, engine: GitIgnoreEngine): List[BspConnectionSpec] = {
    val jsonFiles = findBspJsonFiles(workspaceRoot, engine)
    if jsonFiles.isEmpty then
      logger.warn(s"No .bsp directories found under $workspaceRoot")
    jsonFiles.toList.sortBy(_.toString).flatMap(parseBspSpec(_, workspaceRoot))
  }

  /** Parse a single .bsp JSON file. Public for the file watcher. */
  def parseSingleSpec(jsonPath: os.Path, workspaceRoot: os.Path): Option[BspConnectionSpec] =
    parseBspSpec(jsonPath, workspaceRoot)

  private def findBspJsonFiles(workspaceRoot: os.Path, engine: GitIgnoreEngine): Set[os.Path] =
    findBspDirs(workspaceRoot, engine).flatMap { bspDir =>
      logger.debug(s"Searching for .bsp JSON files in $bspDir")
      os.list(bspDir).filter(p => p.last.endsWith(".json"))
    }
    .toSet

  private def findBspDirs(root: os.Path, engine: GitIgnoreEngine): List[os.Path] =
    os.walk(root, maxDepth = 10, skip = p => os.isDir(p) && engine.isIgnored(p, isDir = true))
      .filter(p => os.isDir(p) && p.last == ".bsp")
      .toList
```

Add the import at the top (after `com.typesafe.scalalogging.StrictLogging`):
```scala
import ba.sake.basamake.navigation.indexing.GitIgnoreEngine
```

- [ ] **Step 4: Update the `BspManager` call sites so the module compiles**

Temporary inline engines (Task 7 replaces them with the `ignoreEngine` field). In `modules/main/src/ba/sake/basamake/bsp/BspManager.scala`:

- Line 47 (`initialize`):
```scala
    val discovered = BspDiscovery.discover(workspaceRoot, new GitIgnoreEngine(workspaceRoot, exemptLastNames = Set(".bsp")))
```
- Line 240 (`handleBspChanges`):
```scala
    val current = BspDiscovery.discover(workspaceRoot, new GitIgnoreEngine(workspaceRoot, exemptLastNames = Set(".bsp"))).map(_.path).toSet
```
- Line 373 (`initializeForTestingOnlyDiscover`):
```scala
    val discovered = BspDiscovery.discover(workspaceRoot, new GitIgnoreEngine(workspaceRoot, exemptLastNames = Set(".bsp")))
```

Add the import next to `ba.sake.basamake.navigation.indexing.{WorkspaceIndex, SemanticdbDirs}`:
```scala
import ba.sake.basamake.navigation.indexing.GitIgnoreEngine
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `deder exec -t test -m main-test`
Expected: PASS — `BspDiscoveryTest` green (3 tests), all existing BspManager tests still green.

- [ ] **Step 6: Commit**

```bash
git add modules/main/src/ba/sake/basamake/bsp/BspDiscovery.scala modules/main/test/src/ba/sake/basamake/bsp/BspDiscoveryTest.scala modules/main/src/ba/sake/basamake/bsp/BspManager.scala
git commit -m "Make .bsp discovery gitignore-aware (exempting .bsp dirs)"
```

---

### Task 7: `BspManager` — engine, watcher filter, `.gitignore` reload

**Files:**
- Modify: `modules/main/src/ba/sake/basamake/bsp/BspManager.scala`
- Create: `modules/main/test/src/ba/sake/basamake/bsp/BspManagerWatchIgnoreTest.scala`

- [ ] **Step 1: Write the failing test**

Create `modules/main/test/src/ba/sake/basamake/bsp/BspManagerWatchIgnoreTest.scala`:

```scala
package ba.sake.basamake.bsp

import munit.FunSuite

class BspManagerWatchIgnoreTest extends FunSuite {

  test("watchIgnored: .bsp paths are never ignored (even when gitignored)") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".bsp/\n")
      os.makeDir.all(root / ".bsp")
      val mgr = BspManager.forTesting(root)
      mgr.initializeForTestingOnlyDiscover()
      assert(!mgr.watchIgnored(root / ".bsp" / "sbt.json"),
        ".bsp changes must still reach the watcher")
    } finally os.remove.all(root)
  }

  test("watchIgnored: gitignored .worktrees and generated dirs are ignored") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".worktrees/\n*.class\n")
      os.makeDir.all(root / ".worktrees" / "wt")
      val mgr = BspManager.forTesting(root)
      mgr.initializeForTestingOnlyDiscover()
      assert(mgr.watchIgnored(root / ".worktrees" / "wt" / "Foo.scala"))
      assert(mgr.watchIgnored(root / "out" / "Foo.class"))
      assert(!mgr.watchIgnored(root / "src" / "Main.scala"))
    } finally os.remove.all(root)
  }

  test("watchIgnored: .basamake/logs and paths outside the root are ignored") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      val mgr = BspManager.forTesting(root)
      mgr.initializeForTestingOnlyDiscover()
      assert(mgr.watchIgnored(root / ".basamake" / "logs" / "basamake.log"))
      assert(mgr.watchIgnored(root / os.up / "elsewhere" / "x.scala"))
    } finally os.remove.all(root)
  }

  test("onFileChanged: .gitignore edit reloads the engine") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.class\n")
      os.makeDir.all(root / "src")
      val mgr = BspManager.forTesting(root)
      mgr.initializeForTestingOnlyDiscover()
      assert(!mgr.watchIgnored(root / "src" / "Main.scala"))
      // user adds a pattern that ignores scala sources
      os.write(root / ".gitignore", "*.scala\n")
      mgr.onFileChanged(Set(root / ".gitignore"))
      assert(mgr.watchIgnored(root / "src" / "Main.scala"),
        "engine should reload after .gitignore change")
    } finally os.remove.all(root)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `deder exec -t test -m main-test`
Expected: FAIL — compilation error: `watchIgnored` is `private` (must become `private[bsp]`), and the `ignoreEngine` field does not exist yet.

- [ ] **Step 3: Write the implementation**

Modify `modules/main/src/ba/sake/basamake/bsp/BspManager.scala`:

a) Add the engine field after line 27 (`private var watcher: FileChangeWatcher = uninitialized`):

```scala
  /** Gitignore engine for the file watcher. Built in initialize(); rebuilt on
    * .gitignore changes. Exempts .bsp so watcher events for build servers pass. */
  @volatile private var ignoreEngine: Option[GitIgnoreEngine] = None
```

Add the import next to `ba.sake.basamake.navigation.indexing.{WorkspaceIndex, SemanticdbDirs}`:
```scala
import ba.sake.basamake.navigation.indexing.GitIgnoreEngine
```

b) `initialize` (after Task 6 it reads:):
```scala
  def initialize(workspaceRoot: os.Path, lspClient: LanguageClient): Unit = {
    this.client = lspClient
    val discovered = BspDiscovery.discover(workspaceRoot, new GitIgnoreEngine(workspaceRoot, exemptLastNames = Set(".bsp")))
    knownBspFiles = discovered.map(_.path).toSet
    for (spec <- discovered) applyOverrides(spec).foreach(attachConnection)

    watcher = FileChangeWatcher(workspaceRoot, onFileChanged, !watchIgnored(_))
    watcher.start()
    logger.debug(s"File watcher started for workspace $workspaceRoot")
  }
```
Replace with:
```scala
  def initialize(workspaceRoot: os.Path, lspClient: LanguageClient): Unit = {
    this.client = lspClient
    ignoreEngine = Some(newEngine())
    val discovered = BspDiscovery.discover(workspaceRoot, ignoreEngine.get)
    knownBspFiles = discovered.map(_.path).toSet
    for (spec <- discovered) applyOverrides(spec).foreach(attachConnection)

    watcher = FileChangeWatcher(workspaceRoot, onFileChanged, !watchIgnored(_))
    watcher.start()
    logger.debug(s"File watcher started for workspace $workspaceRoot")
  }

  private def newEngine(): GitIgnoreEngine =
    new GitIgnoreEngine(workspaceRoot, config.ignorePatterns.toVector, exemptLastNames = Set(".bsp"))
```

c) `onFileChanged` (current lines 202-209):
```scala
  private[bsp] def onFileChanged(changedPaths: Set[os.Path]): Unit = {
    val watched = changedPaths.filterNot(watchIgnored)
    val changedBspFiles = watched.filter(_.segments.toSeq.contains(".bsp"))
    if (changedBspFiles.nonEmpty) {
      logger.info(s"Detected .bsp change(s): ${changedBspFiles.mkString(", ")}")
      enqueueBspChangeBatch(changedBspFiles)
    }
  }
```
Replace with:
```scala
  private[bsp] def onFileChanged(changedPaths: Set[os.Path]): Unit = {
    val watched = changedPaths.filterNot(watchIgnored)
    val changedBspFiles = watched.filter(_.segments.toSeq.contains(".bsp"))
    val gitignoreChanges = watched.filter(_.last == ".gitignore")
    if (gitignoreChanges.nonEmpty) {
      logger.info(s"Detected .gitignore change(s): ${gitignoreChanges.mkString(", ")} — reloading ignore engine")
      ignoreEngine = Some(newEngine())
    }
    if (changedBspFiles.nonEmpty) {
      logger.info(s"Detected .bsp change(s): ${changedBspFiles.mkString(", ")}")
      enqueueBspChangeBatch(changedBspFiles)
    }
  }
```

d) `handleBspChanges` (after Task 6 it reads:):
```scala
    val current = BspDiscovery.discover(workspaceRoot, new GitIgnoreEngine(workspaceRoot, exemptLastNames = Set(".bsp"))).map(_.path).toSet
```
Replace with:
```scala
    val current = BspDiscovery.discover(workspaceRoot, ignoreEngine.getOrElse(newEngine())).map(_.path).toSet
```

e) `watchIgnored` (current lines 315-328):
```scala
  private def watchIgnored(path: os.Path): Boolean = {
    val relOpt = try Some(path.relativeTo(workspaceRoot)) catch { case _: Exception => None }
    relOpt match {
      case None => true
      case Some(rel) if rel.segments.isEmpty => false
      case Some(rel) =>
        val segs = rel.segments.toSeq
        segs.sliding(2).exists(_.toSeq == Seq(".basamake", "logs")) ||
          segs.head == "target" ||
          segs.head == "out" ||
          segs.head == ".deder" ||
          segs.head == ".metals"
    }
  }
```
Replace with:
```scala
  private[bsp] def watchIgnored(path: os.Path): Boolean = {
    val relOpt = try Some(path.relativeTo(workspaceRoot)) catch { case _: Exception => None }
    relOpt match {
      case None => true
      case Some(rel) if rel.segments.isEmpty => false
      case Some(rel) =>
        if (rel.segments.toSeq.sliding(2).exists(_.toSeq == Seq(".basamake", "logs"))) true
        else ignoreEngine match {
          case Some(engine) => engine.isIgnored(path, os.isDir(path))
          // pre-initialize (tests): keep the legacy hardcoded top-level list
          case None =>
            val segs = rel.segments.toSeq
            segs.head == "target" || segs.head == "out" ||
            segs.head == ".deder" || segs.head == ".metals"
        }
    }
  }
```

f) `initializeForTestingOnlyDiscover` (after Task 6 it reads:):
```scala
  private[bsp] def initializeForTestingOnlyDiscover(): Unit = {
    val discovered = BspDiscovery.discover(workspaceRoot, new GitIgnoreEngine(workspaceRoot, exemptLastNames = Set(".bsp")))
    knownBspFiles = discovered.map(_.path).toSet
    for (spec <- discovered) applyOverrides(spec).foreach(attachConnection)
  }
```
Replace with:
```scala
  private[bsp] def initializeForTestingOnlyDiscover(): Unit = {
    ignoreEngine = Some(newEngine())
    val discovered = BspDiscovery.discover(workspaceRoot, ignoreEngine.get)
    knownBspFiles = discovered.map(_.path).toSet
    for (spec <- discovered) applyOverrides(spec).foreach(attachConnection)
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `deder exec -t test -m main-test`
Expected: PASS — `BspManagerWatchIgnoreTest` green (4 tests); all existing BspManager/BspDiscovery tests still green.

- [ ] **Step 5: Commit**

```bash
git add modules/main/src/ba/sake/basamake/bsp/BspManager.scala modules/main/test/src/ba/sake/basamake/bsp/BspManagerWatchIgnoreTest.scala
git commit -m "Wire gitignore engine into BspManager watcher + reload on .gitignore change"
```

---

### Task 8: Docs + full-suite verification

**Files:**
- Modify: `AGENTS.md`
- Modify: `TODO.md`

- [ ] **Step 1: Update AGENTS.md**

a) In the "WorkspaceIndex" architecture section, replace:
```
- Skips directories: `.git`, `.basamake`, `.metals`, `.bsp`, `node_modules`
```
with:
```
- Skips directories: always-skip set (`.git`, `.basamake`, `.deder`, `.metals`, `.bsp`,
  `.scala-build`, `target`, `out`, `.github`, `.idea`, `.vscode`, `node_modules`) plus
  everything matched by `.gitignore` rules (nested `.gitignore` files included, plus
  `ignorePatterns` from `.basamake/config.json` — last match wins). SemanticDB dirs
  come only from `data.json`, never from the walk
```

b) Add a new "Project root resolution" subsection after the WorkspaceIndex section:
```markdown
### Project root resolution

`.basamake/` (logs, config, data.json, source walk, `.bsp` discovery) lives at the
**project root**, resolved in `Main.run` by climbing from the opened folder to the
first ancestor containing `.git` (dir or file — a file marks a git worktree) or an
existing `.basamake/` dir. Non-git folders without a marker fall back to the opened
folder. So `examples/hello/sbt` reuses `examples/hello/.basamake`, while each
`.worktrees/<branch>` gets its own. `.bsp` dirs are gitignored in most repos but are
exempted from ignore checks in BspDiscovery and the file watcher.
```

c) In the "Logging" section, change:
```
- **File → `.basamake/logs/basamake.log`** in the workspace root
```
to:
```
- **File → `.basamake/logs/basamake.log`** in the project root (see Project root resolution)
```

- [ ] **Step 2: Update TODO.md**

Replace line 6:
```
- ignore .gitignore-d folders, hmm we need target/ for semanticdb files, but ignore .worktrees/ ..???
```
with:
```
- [x] ignore .gitignore-d folders (engine + config ignorePatterns; semanticdb still from data.json)
```

- [ ] **Step 3: Run the full test suite**

Run: `deder exec -t test`
Expected: PASS — all suites green (baseline 235 tests + ~40 new ones).

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md TODO.md
git commit -m "Document gitignore-aware indexing + project-root resolution"
```

---

### Task 9: Final verification + merge prep

- [ ] **Step 1: Full clean-slate test run**

Run: `deder clean && deder exec -t test`
Expected: PASS — everything green from scratch.

- [ ] **Step 2: Verify acceptance criteria from the spec**

1. Opening `examples/hello/sbt` resolves root to `examples/hello` — covered by `ProjectRootTest "existing .basamake in an ancestor"` ✓
2. Fresh worktree under `.worktrees/<b>` resolves to itself — covered by `ProjectRootTest ".git file (worktree)"` ✓
3. `node_modules/`, `.worktrees/`, `target/` with generated scala, not indexed; `!`-negated files still indexed — covered by `WorkspaceIndexTest` gitignore tests ✓
4. Gitignored `.bsp` dirs still discovered; `.worktrees/<b>/.bsp` not — covered by `BspDiscoveryTest` + `BspManagerWatchIgnoreTest` ✓
5. `ignorePatterns` in config.json extends/negates — covered by `GitIgnoreEngineTest "extraRootPatterns"` + `BasamakeConfigTest` + `WorkspaceIndexTest "ignorePatterns constructor param"` ✓
6. Editing `.gitignore` reloads the engine — covered by `GitIgnoreEngineTest "reload"` + `BspManagerWatchIgnoreTest "onFileChanged: .gitignore edit reloads"` ✓

- [ ] **Step 3: Report**

Summarize the diff, test counts, and hand over for review (superpowers:requesting-code-review) before merging to `main`.
