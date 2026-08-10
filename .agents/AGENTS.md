# Basamake — Project Base (stable architecture & conventions)

## Stack

- Scala 3.7.4, **JDK 21+**; build tool: **deder** (config: `deder.pkl`, v0.20.0)
- **lsp4j 1.0.0** (LSP protocol), **bsp4j 2.2.0-M2** (BSP client protocol)
- scalameta `semanticdb-shared` + `parsers` 4.17.2 (Scala parsing), javaparser-core 3.28.2 (Java parsing)
- os-lib 0.11.9-M8 (+ `os-lib-watch` in main), lmdbjava 0.9.1, tupson (JSON), mainargs, logback-classic 1.5.12, munit 1.0.4

## Modules (deder module ids)

| Module id | Sources | Tests |
|-----------|---------|-------|
| `modules-navigation` | `modules/navigation/src` | `modules/navigation/test/src` → `modules-navigation-test` |
| `modules-main` (mainClass `ba.sake.basamake.Main`) | `modules/main/src` | `modules/main/test/src` → `modules-main-test` |

Test resources live at `<root>/test/resources` (committed fixtures, incl. binary source jars).

## Navigation model

Two-pass source extraction for Scala and Java:

1. **Pass 1 — Definitions** (`ScalaDefinitionsExtractor` / `JavaDefinitionsExtractor`): parse source ASTs, extract global definitions (classes, objects, methods, vals, vars) into `SymbolTable`
2. **Pass 2 — References** (`ScalaReferencesResolver` / `JavaReferencesResolver`): second AST walk, emit reference occurrences + local definitions into `ResolvedFile`

`SymbolTable` is a `ConcurrentHashMap[String, SymbolDefinition]` keyed by SemanticDB-style symbol; thread-safe for concurrent reads during LSP requests. `CompositeSymbolTable` combines workspace + dep/JDK tables.

## WorkspaceIndex (`modules/navigation/src/.../navigation/indexing/WorkspaceIndex.scala`)

- On `initialize()`: walks workspace, discovers `.semanticdb` files via `SemanticdbIndexing`
- **SemanticDB preferred** (more accurate); falls back to source parsing (Pass 1 + Pass 2) when unavailable
- Ignore rules: always-skip set (`.git`, `.basamake`, `.deder`, `.metals`, `.bsp`, `.scala-build`, `target`, `out`, `.github`, `.idea`, `.vscode`, `node_modules`) + all `.gitignore` patterns (nested files included) + `ignorePatterns` from `.basamake/config.json` (optional file; last match wins). The same rules guard LSP entry points (`didOpen`/`didChange`/`didSave`, watcher-created files). Paths outside the workspace (deps/JDK via goto-def) are exempt. SemanticDB dirs come only from `data.json` (`SemanticdbDirs`), never from the walk
- On buffer change: re-extracts occurrences for the changed file; prefers SemanticDB when buffer text matches disk, else source parse

## Dependency/JDK index cache (`~/.cache/basamake/deps`)

`IndexedSymbolTable` (via `SourceJarIndexer`) caches dependency sources + JDK `src.zip` under the XDG-compliant cache root (`$XDG_CACHE_HOME` honored; `%LOCALAPPDATA%\basamake\deps` on Windows). One directory per source, nested by maven groupId: `com_lihaoyi/upickle_3_4.0.0_<hash>/`; jars without a POM stay flat (`antlr4-runtime_4.7.2_<hash>/`).

- **Register targets, index lazily.** BSP handshakes (`BspConnection.notifyDependencySources`) and the data.json warm start only REGISTER per-target dependency sources (`IndexedSymbolTable.registerTarget` — cached jars become routable, nothing is parsed). A target's UNCACHED jars are indexed in the background (single-flight per fingerprint) via a **priority job queue** — the JDK always first (enqueued at priority 0 during initialize), then `org.scala-lang` jars (scala-library, scala3-library, scala3-compiler, ...), then everything else — when one of its files is opened/poked (`BspManager.ensureDepsIndexedFor` from `didOpen`/`didSave`/`definition`/`references`) or when a lookup misses (`IndexedSymbolTable.get(symbol, candidates)` queues the jar itself — the request returns empty, the retry resolves). Source files are extracted on first lookup hit (`ensureEntryExtracted`), so untouched deps stay lean
- **Dependency sources never go empty.** BSP servers intermittently return empty `buildTargetDependencySources` results (e.g. deder-bsp pre-build). `BspConnection.mergeDeps` accepts fresh lists only when non-empty; the handshake seeds the merge from the connection's persisted data.json, and `buildTargetDidChange` (intercepted via a sink wrapper in `BspConnection.apply`, since the shared BspManager sink can't know the owning connection) triggers a refresh for CHANGED/CREATED targets that re-fires the sink + rewrites data.json only when something changed. `writeTargetData` is defensive (`getOrElse` fallbacks) — a target missing from scalacOptions must not kill persistence
- **Lookups are candidate-scoped LMDB point queries.** `LmdbSerializer.get`: open env per call, B-tree lookup, deserialize one entry. `get(symbol, candidates)` queries ONLY the current file's target jars (package pre-filtered via metadata.json) — precise across same-package collisions, no queries against unrelated targets — falling back to the global package-route map (`route`, built from registered jars' metadata) on a miss (covers the JDK, which is never a target dep). `byPath`/`all`/`keys` are intentionally empty on `IndexedSymbolTable` — dep/JDK symbols resolve by symbol only
- **Index writes are streamed** (`LmdbSerializer.streamingSave` + `SymbolSink`): extractors put each definition into LMDB immediately while parsing — no in-memory symbol table is ever built (a JDK index would be 570k `SymbolDefinition`s ≈ 500MB of heap). `save(table, path)` is a thin wrapper for tests. Each `index.lmdb/` holds `data.mdb` + `lock.mdb`; `MapSize` is 1GB (JDK index ~570k symbols). Value format v1 (`CacheMetadata.FormatVersion`; mismatch reindexes): symbol is the key only, shortName derived at read, paths stored src-relative (`java.base/java/lang/Object.java`). JDK index ~120MB
- **Background indexing is bounded + prioritized** — 2 worker threads pull from a `PriorityBlockingQueue` (JDK = 0, scala-lang = 1, rest = 2); parsing ~90 source jars concurrently used to spike committed heap past 1GB. Index writes are streamed into LMDB (see below), so cold indexing peaks around 1GB committed on a cold cache and stays low afterwards; idle memory is left to G1's own ergonomics
- **Indexing progress** — the navigation module emits `IndexingProgressListener` events (per-phase `Workspace`/`Dependencies`/`Jdk`, done/total counts); `IndexingProgressReporter` (main) forwards them to the LSP client as `window/workDoneProgress` items (throttled to 100ms per phase, gated on the client's `window.workDoneProgress` capability, fail-safe: a rejected `createProgress` — the client's handler only exists after the initialize handshake — is retried after a 5s cooldown; only a broken transport disables the reporter). Workspace indexing + the JDK enqueue run on background threads launched by `initialize()` (nothing progress-related may fire on the initialize thread); `isWorkspaceIndexingDone` exposes readiness for tests
- Cache-dir fingerprints embed the maven groupId from the sibling POM (direct `<project>` child only); filename-derived flat names when no POM
- `SourceJarIndexer.cacheRoot` is a `@volatile var` — tests override it to `./tmp/deps-cache-*` (trait `TestCacheRoot`); never write into the real home cache
- **Known limitation:** scalameta (Scala 3 and 2.13 dialects) cannot parse a few dotty compiler sources (e.g. `dotty/tools/dotc/ast/Desugar.scala`) — those definitions are skipped from the dep index. `scala/util/Try.scala`-style sources parse fine via the Scala 2.13 fallback

## Project root resolution

`.basamake/` (logs, config, data.json, source walk, `.bsp` discovery) lives at the project root, resolved in `Main.run` by climbing from the opened folder to the first ancestor containing `.git` (dir or file — a file marks a git worktree) or an existing `.basamake/` dir; non-git folders fall back to the opened folder. `.bsp` dirs are usually gitignored but are exempted from ignore checks in BspDiscovery and the file watcher.

## LSP handlers (`modules/main/src/.../lsp/BasamakeLanguageServer.scala`)

`BasamakeLanguageServer` implements `LanguageClientAware`, `LanguageServer`, `TextDocumentService`, `WorkspaceService`:

- `initialize()`: builds the workspace index, returns capabilities (`DefinitionProvider`, `ReferencesProvider`, `DocumentSymbolProvider`; `TextDocumentSyncKind.Full` — `didChange` receives the whole document)
- `definition()`/`references()`: `CompletableFuture.supplyAsync` → `workspaceIndex.gotoDefinitions`/`references` (incl. `includeDecl`)
- `documentSymbol()`: returns empty (v1 — deferred follow-up)
- Text doc lifecycle `didOpen`/`didChange`/`didSave`/`didClose` → delegates to `WorkspaceIndex`
- All work on `supplyAsync` — no blocking on lsp4j threads

## Concurrency & lifecycle

`Main.run()` blocks on `future.get()` (returns on stdin EOF). A JVM shutdown hook calls `server.cleanup()` (SIGTERM/SIGINT/VS Code close) so deder processes don't outlive the LSP server. No virtual threads, no actor model, no process supervision.

## BSP lifecycle

`BspManager` discovers `.bsp/*.json` at `initialize()` but spawns nothing (lazy). The first LSP-side `poke(uri, compile)` (from `didOpen`/`didSave`/`definition`/`references`) calls `BspConnection.ensureConnected()` which spawns + handshakes (`BspHandshake`; `BasamakeBuildClient` event sink). `spawnLock` serializes `spawnAndHandshake`; a volatile `spawning` flag lets fast-path callers detect an in-progress spawn: pokes return immediately (no-op), compiles queue in a `CopyOnWriteArrayList` (deduped via `addIfAbsent`) and drain after spawn succeeds. On spawn failure the queue clears; no cooldown, no retry limits — every user action is a fresh attempt. `BspRouter` does two-phase URI routing (ground-truth + bootstrap heuristic). Diagnostics flow BSP `PublishDiagnosticsParams` → LSP.

## Logging & stdout

- `LoggingUtils.configureFileLogging(projectRoot)` — programmatic (no `logback.xml`); **one FILE appender → `.basamake/logs/basamake.log`**, no console appender. Reconfiguration-safe (detaches the old FILE appender). Called from `Main.run()` with the workspace path
- **stdout is sacred**: the LSP transport rides on stdout. File-only logging + the auto-flush wrapper (`PrintStream(System.out, true, "UTF-8")` passed to `LSPLauncher`, since lsp4j output buffers otherwise) keep fd 1 JSON-RPC-only. Verify: `strace -e write -f java -jar ...` — fd 1 must never carry text

## SemanticDB reference

Spec summary + basamake consumer notes: **`agents/semanticdb.md`** — symbol format, descriptor suffixes, occurrences, SUID encoding, TextDocument layout, Scala 2 vs 3 differences.
