# Gitignore-aware indexing + project-root resolution — design

Date: 2026-08-06
Status: approved by user (design phase)

## Problem

1. **Source walk ignores too little.** `WorkspaceIndex.initialize` uses a hardcoded skip
   list (`Set(".git", ".basamake", ".metals", ".bsp", "node_modules")`). It misses
   `.worktrees/`, `tmp/`, `.deder/`, `.scala-build/`, `.idea/`, `.vscode/`, `out/` — and
   even `target/`, meaning generated sources in `target/src_managed` / `.scala-build`
   can pollute the symbol table. User-specific ignore intent lives in `.gitignore`
   files and should be honored.

2. **`.basamake/` is created in every opened folder.** Opening `examples/hello/sbt`
   creates a fresh `.basamake` there instead of reusing `examples/hello/.basamake`
   (which already holds sbt's `data.json` with semanticdb dirs → instant indexing).
   Conversely `.worktrees/<branch>/` should get its **own** `.basamake` (it has its
   own `.git` file and build layout).

### Key insight

SemanticDB dirs come **only** from `.basamake/bsp/<name>_<hash>/data.json`
(`loadSemanticdbRootsFromDataJson`), never from the workspace walk. So skipping
`target/` and friends in the walk is safe. The mirror case: `.bsp/` dirs are
gitignored (`.bsp/` is in this repo's `.gitignore`) but are **essential** for
BspDiscovery — the ignore logic must exempt them.

## Design

### 1. Project-root resolution

`resolveProjectRoot(openedDir)`, runs in `Main.run` before anything else
(logging, server construction, config load):

```
cur = openedDir
loop:
  if cur contains .git (dir or file)   → return cur   // git root (worktree = .git file)
  if cur contains .basamake/ (dir)     → return cur   // explicit workspace marker
  if cur is filesystem root            → return openedDir
  cur = cur.parent
```

Behavior:
- `examples/hello/sbt` → `.basamake` at `examples/hello` → root = `examples/hello`
  (reuses sbt `data.json`, no recompile needed)
- `.worktrees/<branch>` → own `.git` *file* → root = the worktree itself, fresh `.basamake`
- Fresh clone, no `.basamake` anywhere → git root → one `.basamake` per repo
- Non-git folder → opened folder (status quo)

Everything downstream uses the resolved root: logs (`LoggingUtils`), config
(`BasamakeConfig`), data.json (`BspConnection.writeTargetData`,
`loadSemanticdbRootsFromDataJson`), source walk, `.bsp` discovery, file watcher.

The VS Code extension is **unchanged** — it keeps passing the opened folder;
climbing happens server-side (CLI users benefit too). Log the resolution:
`Opened <x> → project root <y> (marker: .basamake/.git)`.

Variable names (`workspacePath` / `workspaceRoot`) are **kept as-is**; the meaning
"resolved project root" is documented in AGENTS.md and code comments. No rename
churn.

### 2. Gitignore engine

**Ported from deder** (`/home/sake/projects/sake92/deder/server/src/ba/sake/deder/FileWatchUtils.scala`,
same author, MIT/Apache-licensed per project convention; note "ported from deder
FileWatchUtils" in the file header):

- `readGitignorePatterns(file)` — trim, strip comments/blank lines, keep `!`
- `isIgnoredByGitignore(relativePath, isDir, patterns)` — last-match-wins, `!` negation
- `globMatch` / `globToRegex` / `simpleGlobMatch` — `*`, `**`, `?`, leading `/` anchor,
  trailing `/` dir-only, path-prefix with boundary check

**New on top (basamake-specific):**

- `GitIgnoreEngine(root)`:
  - Computes the **git boundary**: first ancestor of `root` (incl. `root`) containing
    `.git` (dir or file). If found, loads `.gitignore` from `root` up to the boundary
    (top-down order — ancestor rules first). If no `.git` found, only `root`'s own
    `.gitignore`.
  - **Layer stack**: `rules(dir) = rules(parent) ++ (dir/.gitignore if exists)`,
    memoized in a map (each `.gitignore` parsed once). A path is matched against each
    layer's patterns **relative to that layer's base dir** (nested `.gitignore` rules
    apply to their own subtree), accumulating last-match-wins across all layers.
  - `isIgnored(path, isDir): Boolean` — used by walk pruning, discovery, watcher.
  - **Exemption set** (e.g. `{".bsp"}`) for consumers that need gitignored things.

### 3. Integration map

| Consumer | Change |
|---|---|
| `WorkspaceIndex.initialize` | `skip = alwaysSkip ∪ engine.isIgnored`; alwaysSkip = `{.git, .basamake, .deder, .metals, .bsp, .scala-build, target, out, .github, .idea, .vscode, node_modules}` (deder's `ignoredDirNames` + `.basamake` + `.deder` + `node_modules`; applied at any depth — these never contain project sources). Semanticdb still only from data.json. |
| `BspDiscovery.discover` | Walk prunes `engine.isIgnored(dir)` **except dirs named `.bsp`** (gitignored but essential). `.worktrees/<b>/.bsp` never discovered because `.worktrees` prunes first. |
| `FileChangeWatcher` / `BspManager.watchIgnored` | Replace hardcoded top-level `target/out/.deder/.metals` checks with engine + `.bsp` exemption (watcher must still see `.bsp` changes) + keep `.basamake/logs` special case. |

### 4. User-configurable ignore patterns

`BasamakeConfig` gains `ignorePatterns: List[String] = Nil` (gitignore syntax,
root-relative), merged **after** gitignore layers (last match wins) — mirrors
deder's pkl `watchIgnore`. Lives in `.basamake/config.json` at the resolved root.

### 5. Reload on `.gitignore` change

`BspManager.onFileChanged` already receives all change events; when a changed path's
last segment is `.gitignore`, rebuild the engine (invalidate rules cache). Index
refresh is not re-triggered (index only refreshes via didSave/invalidate) — the
reload affects the watcher filter and future walks.

### Edge cases / non-goals (v1)

- `.git/info/exclude` and global `core.excludesFile` — not read
- Nested git repos (submodules) inside the walk — parent rules keep applying past the
  boundary (git stops there); low impact, nested repos are usually opened as own roots
- Multi-root VS Code windows — still `folders[0]` only (existing TODO)
- Ignored dir → subtree pruned entirely; negation cannot resurrect files under an
  excluded dir (matches git: "It is not possible to re-include a file if a parent
  directory of that file is excluded")
- `readGitignorePatterns` trims lines (git trims trailing whitespace only) — inherited
  from deder, acceptable

## Files touched

New:
- `modules/navigation/src/ba/sake/basamake/navigation/indexing/GitIgnore.scala` (ported matcher)
- `modules/navigation/src/ba/sake/basamake/navigation/indexing/GitIgnoreEngine.scala` (layers/boundary/cache)
- `modules/main/src/ba/sake/basamake/util/ProjectRoot.scala` (resolveProjectRoot)
- Tests: `GitIgnoreTest.scala` (ported deder suite), `GitIgnoreEngineTest.scala`, `ProjectRootTest.scala`

Modified:
- `WorkspaceIndex.scala` — engine-based skip
- `BspDiscovery.scala` — engine-aware discovery, `.bsp` exemption
- `BspManager.scala` — engine construction, watcher filter rewrite, `.gitignore` reload
- `BasamakeConfig.scala` — `ignorePatterns`
- `Main.scala` — resolve + log project root
- `AGENTS.md`, `TODO.md` (line 6) — docs
- `WorkspaceIndexTest` / `BspDiscoveryTest` — integration fixtures (`.gitignore` with
  `node_modules/`, `.worktrees/`, `target/` generated scala → not indexed;
  `!keep.scala` → indexed; gitignored `.bsp` still discovered; `.worktrees/.bsp` not)

## Testing strategy

- **navigation-test**: `GitIgnoreTest` (ported pattern-semantics suite),
  `GitIgnoreEngineTest` (nested gitignores, boundary chain, dir pruning, negation,
  `.bsp` exemption, memoized rules), `WorkspaceIndexTest` additions (gitignore fixtures)
- **main-test**: `ProjectRootTest` (all climb cases incl. `.git` file = worktree),
  `BspDiscoveryTest` additions, `BspManager` watcher-ignore test

## Acceptance criteria

1. Opening `examples/hello/sbt` resolves root to `examples/hello` and reuses its data.json
2. A fresh worktree under `.worktrees/<b>` resolves to itself (own `.basamake`)
3. `node_modules/`, `.worktrees/`, `target/` (with generated scala), `.scala-build/` are
   not indexed when gitignored; `!`-negated files still are
4. Gitignored `.bsp` dirs are still discovered; `.worktrees/<b>/.bsp` is not
5. `ignorePatterns` in config.json extends/negates gitignore rules
6. Editing `.gitignore` reloads the engine (watcher behavior changes immediately)
