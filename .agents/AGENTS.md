# Basamake — Project Base (stable architecture & conventions)

## Stack

- Scala 3.7.4, **JDK 21+**; build tool: **deder** (config: `deder.pkl`, v0.20.0)
- **lsp4j 1.0.0** (LSP protocol), **bsp4j 2.2.0-M2** (BSP client protocol)
- scalameta `semanticdb-shared` + `parsers` 4.17.2 (Scala parsing), javaparser-core 3.28.2 (Java parsing)
- os-lib 0.11.9-M8 (+ `os-lib-watch` in bsp), lmdbjava 0.9.1, tupson (JSON), mainargs, logback-classic 1.5.12, munit 1.0.4

## Modules (deder module ids)

| Module id | Sources | Tests |
|-----------|---------|-------|
| `modules-core` | `modules/core/src` | `modules/core/test/src` → `modules-core-test` |
| `modules-index` | `modules/index/src` | `modules/index/test/src` → `modules-index-test` |
| `modules-bsp` | `modules/bsp/src` | `modules/bsp/test/src` → `modules-bsp-test` |
| `modules-lsp` (mainClass `ba.sake.basamake.Main`) | `modules/lsp/src` | `modules/lsp/test/src` → `modules-lsp-test` |

Test resources live at `<root>/test/resources` (committed fixtures, incl. binary source jars).

## Navigation model

Two-pass source extraction for Scala and Java:

1. **Pass 1 — Definitions** (`ScalaDefinitionsExtractor` / `JavaDefinitionsExtractor`): parse source ASTs, extract global definitions (classes, objects, methods, vals, vars) into `SymbolTable`
2. **Pass 2 — References** (`ScalaReferencesResolver` / `JavaReferencesResolver`): second AST walk, emit reference occurrences + local definitions into `ResolvedFile`

`SymbolTable` is a workspace-scoped interface (`get`/`byPath`/`add`/`removeByPath`/`keys`/`all`), implemented by `InMemorySymbolTable` (`ConcurrentHashMap[String, SymbolDefinition]` keyed by SemanticDB-style symbol; thread-safe for concurrent reads during LSP requests). Dependency/JDK symbols live in `IndexedSymbolTable` — a SEPARATE read-only lookup service (no shared interface). `WorkspaceIndex` holds both (`depsTable: Option[IndexedSymbolTable]`). `WorkspaceIndex` builds the resolver's table PER FILE (`resolverTableFor`): workspace symbols plus dep symbols scoped to the file's OWNING jar when the file lives in the dep cache (goto-def chains INTO dep sources keep resolving same-jar refs); workspace and JDK files get the JDK-scoped lookup (JDK = implicit candidate). Cross-jar dep→dep is out of scope — dep-file candidates are always just the owning jar. Package-segment refs (import prefixes, incl. semanticdb occurrences) resolve to the package object (`pkg/package.`); Java import statements emit per-segment refs (type/static-member only — Java has no package objects).

## WorkspaceIndex (`modules/index/src/.../index/indexing/WorkspaceIndex.scala`)

- On `initialize()`: walks workspace, discovers `.semanticdb` files via `SemanticdbIndexing`
- **SemanticDB preferred** (more accurate); falls back to source parsing (Pass 1 + Pass 2) when unavailable
- Ignore rules: always-skip set (`.git`, `.basamake`, `.deder`, `.metals`, `.bsp`, `.scala-build`, `target`, `out`, `.github`, `.idea`, `.vscode`, `node_modules`) + all `.gitignore` patterns (nested files included) + `ignorePatterns` from `.basamake/config.json` (optional file; last match wins). The same rules guard LSP entry points (`didOpen`/`didChange`/`didSave`, watcher-created files). Paths outside the workspace (deps/JDK via goto-def) are exempt. SemanticDB dirs come only from `data.json` (`SemanticdbDirs`), never from the walk. Directories containing a `.git` entry (dir or file) below the workspace root are nested git repositories — treated as boundaries by the walk, entry guards, BspDiscovery, the watcher, and semanticdb pairing.
- On buffer change: re-extracts occurrences for the changed file; prefers SemanticDB when buffer text matches disk, else source parse

## Dependency/JDK index cache (`~/.cache/basamake/deps`)

`IndexedSymbolTable` (via `SourceJarIndexer`) caches dependency sources + JDK `src.zip` under the XDG-compliant cache root (`$XDG_CACHE_HOME` honored; `%LOCALAPPDATA%\basamake\deps` on Windows). One directory per source, nested by maven groupId: `com_lihaoyi/upickle_3_4.0.0_<hash>/`; jars without a POM stay flat (`antlr4-runtime_4.7.2_<hash>/`).

- **Register targets, sprinkle metadata, index on demand.** BSP handshakes (`BspConnection.notifyDependencySources`) and the data.json warm start only REGISTER per-target dependency source jars (`IndexedSymbolTable.registerTarget` — paths recorded, nothing parsed). `registerTarget` also sprinkles the package-only `metadata.json` for each jar in the background (single-flight, persisted): packages are derived from the sibling CLASSES jar (`SourceJarIndexer.classesJarOf`/`packagesOfClassesJar` — coursier keeps `foo_3-1.0.0-sources.jar` next to `foo_3-1.0.0.jar`; zip directory listing, no decompression/parsing) and written with `indexed = false`. Full indexing happens ONLY when a lookup targets a jar: `get(symbol, candidates)` package-filters candidates via metadata, and a matching-but-uncached jar is indexed IN THE BACKGROUND (single-flight per fingerprint, globally bounded — see below) while the lookup misses fast; `SourceJarIndexer.index` cache-hit check requires `meta.indexed`. Source files are extracted on first lookup hit (`ensureEntryExtracted`), so untouched deps stay lean
- **Dependency sources never go empty.** BSP servers intermittently return empty `buildTargetDependencySources` results (e.g. deder-bsp pre-build). `BspConnection.mergeDeps` accepts fresh lists only when non-empty; the handshake seeds the merge from the connection's persisted data.json, and `buildTargetDidChange` (intercepted via a sink wrapper in `BspConnection.apply`, since the shared BspManager sink can't know the owning connection) triggers a refresh for CHANGED/CREATED targets that re-fires the sink + rewrites data.json only when something changed. `writeTargetData` is defensive (`getOrElse` fallbacks) — a target missing from scalacOptions must not kill persistence
- **Lookups are candidate-scoped LMDB point queries — no fallback.** `LmdbSerializer.get`: open env per call (serialized per index path — concurrent read-only Env objects on one path trip lmdbjava's reader-slot management), B-tree lookup, deserialize one entry. `get(symbol, candidates)` iterates the current file's BSP target jars (+ the JDK `src.zip` as an implicit candidate — a dependency of every target that BSP never lists): each jar is package-filtered via metadata.json (packages ALWAYS come from the real classes jar — zip folder listing — both in the sprinkled `indexed = false` state and after a full index), a matching jar without an index is indexed in the background (a cold lookup misses fast), then one exact LMDB point query per matching jar; the first hit wins, a miss ends the lookup with `None`. There is NO global package route, no recency/version ranking, no remembered candidate state, no fallback search — a jar outside the target's dependency set is never consulted, even if it shares the package. A jar with no metadata (no classes-jar sibling) is unfilterable: it gets indexed in the background on first lookup. A failed query logs a warning (once per fingerprint per session) and returns `None` — there is NO corruption detection/recovery: index publication is atomic (tmp + rename), so only `CacheMetadata.isValid` (format-version bump or source staleness) ever triggers a reindex; the user deletes the cache dir to rebuild manually. **Dep files get owning-jar candidates**: `candidatesForPath` derives the fingerprint from an extracted file's cache path (`<cacheRoot>/<fingerprint>/src/<entry>`) and returns the owning jar (recovered from metadata.json when the jar isn't registered this session) — lookups from inside scala-library 3.8.4 stay in 3.8.4, **and the reference resolvers parsing that file use the same owning-jar candidates** (`WorkspaceIndex.resolverTableFor`). JDK paths return nothing (`candidatesForPath`); the implicit JDK candidate in `get` covers them. `byPath`/`all`/`keys` do not exist on `IndexedSymbolTable` — dep/JDK symbols resolve by symbol only
- **Index writes are streamed** (`LmdbSerializer.streamingSave` + `SymbolSink`): extractors put each definition into LMDB immediately while parsing — no in-memory symbol table is ever built (a JDK index would be 570k `SymbolDefinition`s ≈ 500MB of heap). `save(table, path)` is a thin wrapper for tests. Each `index.lmdb/` holds `data.mdb` + `lock.mdb`; `MapSize` is 1GB (JDK index ~570k symbols). Value format versioned by `CacheMetadata.FormatVersion` (mismatch reindexes): symbol is the key only, shortName derived at read, paths stored src-relative (`java.base/java/lang/Object.java`). JDK index ~120MB
- **Background indexing, bounded — no queue.** Each cold lookup spawns one background virtual thread per jar it needs (single-flight per fingerprint via `IndexedSymbolTable`'s in-progress set: a duplicate trigger is a no-op, a failed index unmarks so a later lookup retries); the lookup itself MISSES FAST — goto-def never blocks on parsing again. A global `Semaphore` caps concurrent indexes at `max(1, min(CPUs-1, 4))`: one CPU stays free for the build server, and beyond 4 jar parsing is memory-bandwidth bound, not CPU bound. `SourceJarIndexer`'s per-fingerprint `ReentrantLock` still serializes CROSS-SERVER races on the shared cache dir (parks virtual threads, no carrier pinning). The JDK uses the same background path: `ensureJdkIndexed` (started at `initialize()`, unconditionally — no BSP dependency) indexes `src.zip` in the background, and a COLD JDK is never indexed by a lookup — a `java.*` lookup before the background index finishes is a fast transient miss
- **Indexing progress** — the index module emits `IndexingProgressListener` events (per-phase `Workspace`/`Dependencies`/`Jdk`, done/total counts); `IndexingProgressReporter` (lsp) forwards them to the LSP client as `window/workDoneProgress` items (throttled to 100ms per phase, gated on the client's `window.workDoneProgress` capability, fail-safe: a rejected `createProgress` — the client's handler only exists after the initialize handshake — is retried after a 5s cooldown; only a broken transport disables the reporter). Workspace indexing + the JDK index run on background threads launched by `initialize()` (nothing progress-related may fire on the initialize thread); a failed index sends a terminal `(1,1,"... failed")` event so the phase ends; `isWorkspaceIndexingDone` exposes readiness for tests
- Cache-dir fingerprints embed the maven groupId from the sibling POM (direct `<project>` child only); filename-derived flat names when no POM
- `SourceJarIndexer.cacheRoot` is a `@volatile var` — tests override it to `./tmp/deps-cache-*` (trait `TestCacheRoot`); never write into the real home cache
- **Known limitation:** scalameta (Scala 3 and 2.13 dialects) cannot parse a few dotty compiler sources (e.g. `dotty/tools/dotc/ast/Desugar.scala`) — those definitions are skipped from the dep index. `scala/util/Try.scala`-style sources parse fine via the Scala 2.13 fallback

## Project root resolution

`.basamake/` (logs, config, data.json, source walk, `.bsp` discovery) lives at the project root, resolved in `Main.run` by climbing from the opened folder to the first ancestor containing `.git` (dir or file — a file marks a git worktree) or an existing `.basamake/` dir; non-git folders fall back to the opened folder. `.bsp` dirs are usually gitignored but are exempted from ignore checks in BspDiscovery and the file watcher.

## LSP handlers (`modules/lsp/src/.../lsp/BasamakeLanguageServer.scala`)

`BasamakeLanguageServer` implements `LanguageClientAware`, `LanguageServer`, `TextDocumentService`, `WorkspaceService`:

- `initialize()`: builds the workspace index, returns capabilities (`DefinitionProvider`, `ReferencesProvider`, `DocumentSymbolProvider`; `TextDocumentSyncKind.Full` — `didChange` receives the whole document)
- `definition()`/`references()`/`hover()`: `CompletableFuture.supplyAsync(..., navigationExecutor)` → `workspaceIndex.gotoDefinitions`/`references`/`HoverProvider` (incl. `includeDecl`) — the executor is a virtual-thread-per-task executor so navigation work never blocks a common-pool or lsp4j thread (dep indexing itself is background — a cold jar misses fast and indexes on its own thread)
- `documentSymbol()`: returns empty (v1 — deferred follow-up)
- Text doc lifecycle `didOpen`/`didChange`/`didSave`/`didClose` → delegates to `WorkspaceIndex`
- All request work off the lsp4j message thread

## Concurrency & lifecycle

`Main.run()` blocks on `future.get()` (returns on stdin EOF). A JVM shutdown hook calls `server.cleanup()` (SIGTERM/SIGINT/VS Code close) so deder processes don't outlive the LSP server. `cleanup()` also shuts down `navigationExecutor`. Concurrency uses virtual threads throughout (request handlers, `poke`, JDK indexing, metadata sprinkling); blocking primitives are `ReentrantLock`/`ConcurrentHashMap` — never `synchronized` on long critical sections (virtual threads must park, not pin carriers).

## BSP lifecycle (`modules/bsp/src/.../bsp/`)

`BspManager` discovers `.bsp/*.json` lazily via `BspDiscovery` + `WatchFilter`; first LSP-side poke calls `BspConnection.ensureConnected()` (spawnLock = ReentrantLock; volatile `spawning` flag; pending-compile queue in CopyOnWriteArrayList drained after spawn). Events flow through ONE `BspEvents` interface; connection-scoped events carry `BspConnectionId`; `buildTargetDidChange` on a connection triggers a dependencySources refresh on that same connection (routed by the manager). `BspWatcher` owns the file watcher + .bsp debounce; `WatchFilter` owns the gitignore engine (exempts `.bsp`); manager handles .bsp attach/detach batches.

## Logging & stdout

- `LoggingUtils.configureFileLogging(projectRoot)` — programmatic (no `logback.xml`); **one FILE appender → `.basamake/logs/basamake.log`**, no console appender. Reconfiguration-safe (detaches the old FILE appender). Called from `Main.run()` with the workspace path
- **stdout is sacred**: the LSP transport rides on stdout. File-only logging + the auto-flush wrapper (`PrintStream(System.out, true, "UTF-8")` passed to `LSPLauncher`, since lsp4j output buffers otherwise) keep fd 1 JSON-RPC-only. Verify: `strace -e write -f java -jar ...` — fd 1 must never carry text

## SemanticDB reference

Spec summary + basamake consumer notes: **`agents/semanticdb.md`** — symbol format, descriptor suffixes, occurrences, SUID encoding, TextDocument layout, Scala 2 vs 3 differences.
