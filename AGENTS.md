# AGENTS.md — Basamake LSP Server

## Libraries
- os-lib for file paths
- scalameta for Scala source parsing (definitions + references)
- javaparser for Java source parsing (definitions + references)
- lsp4j 0.24.0 for LSP protocol

## Build & Test

```bash
# Compile
deder exec

# Run tests (munit)
deder exec -t test

# Build fat JAR
deder exec -t assembly -m modules-main

# Clean build state (when things get stuck)
deder clean && deder exec
```

**Deder server** keeps socket files in `.deder/`. Run `deder shutdown` when finished working, especially after using git worktrees — stale server processes block new connections and linger in worktree directories. Also run before branch switches or deleting the project.

## JDK

Requires **JDK 21+**. Scala 3.7.4.

## Code Style

**Avoid braceless syntax for bodies longer than 3 lines.** Short bodies (≤3 lines) can keep the colon. Longer bodies must use curly braces `{}`.

```scala
// Good — short body, colon is fine
case class Position(line: Int, char: Int):
  def offset: Int = line + char

// Bad — longer body, should use curlies
object WorkspaceIndex:
  def initialize(workspacePath: os.Path): Unit = {
    // lines 1-5...
    // lines 6-10...
  }

// Good — curlies for longer body
object WorkspaceIndex {
  def initialize(workspacePath: os.Path): Unit = {
    // lines 1-5...
    // lines 6-10...
  }
}
```

## Smoke Test

The python smoke scripts (`examples/hello/smoke_test.py`, `bsp_smoke_test.py`,
root `test_sbt_gotodef.py`) were deleted. Their coverage now lives in Scala tests:

- `LspTransportTest` (modules/main-test) — drives the real `BasamakeLanguageServer`
  through the JSON-RPC transport (initialize → didOpen → definition → shutdown)
  against a fixture copied to `<repo>/tmp/`
- `BasamakeLanguageServerTest` — LSP handler behavior (rename, watched files, stale semanticdb)
- `WorkspaceIndexTest` — navigation with committed real-semanticdb fixtures

## stdout Is Sacred

The LSP transport rides on stdout. **Nothing else may write to it.** Three guards:

1. Logback console appender targets `System.err` — explicitly: `consoleAppender.setTarget("System.err")`
2. Before passing stdout to `LSPLauncher`, wrap in auto-flush: `new PrintStream(System.out, true, "UTF-8")` — lsp4j output buffers otherwise
3. Logback root logger calls `detachAndStopAllAppenders()` at startup to kill any default stdout appender

**How to verify:** `strace -e write -f java -jar ...` — check that fd 1 (stdout) only has JSON-RPC, never text.

## LSP Stack

**lsp4j 0.24.0** for editor protocol. No bsp4j — navigation runs entirely in-process.

### Capabilities

Server advertises: `DefinitionProvider`, `ReferencesProvider`, `DocumentSymbolProvider`.
`documentSymbol` returns empty for v1 — deferred to follow-up.

### Text document sync

`TextDocumentSyncKind.Full` — `didChange` receives the whole document each keystroke.
`didOpen`/`didChange`/`didSave`/`didClose` delegated to `WorkspaceIndex`.

## Architecture

### Navigation model

Two-pass source extraction for Scala and Java:

1. **Pass 1 — Definitions** (`ScalaDefinitionsExtractor` / `JavaDefinitionsExtractor`): parse source ASTs, extract global definitions (classes, objects, methods, vals, vars) into `SymbolTable`
2. **Pass 2 — References** (`ScalaReferencesResolver` / `JavaReferencesResolver`): second AST walk, emit reference occurrences + local definitions into `ResolvedFile`

`SymbolTable` is a `ConcurrentHashMap[String, SymbolDefinition]` — keyed by SemanticDB-style symbol. Thread-safe for concurrent reads during LSP requests.

### WorkspaceIndex

Core index in `modules/main`. On `initialize()`:
- Walks workspace, discovers `.semanticdb` files via `SemanticdbIndexing`
- When SemanticDB available: uses `.semanticdb` for definitions + references (preferred — more accurate)
- When SemanticDB unavailable: falls back to source parsing (Pass 1 + Pass 2)
- Skips directories: `.git`, `.basamake`, `.metals`, `.bsp`, `node_modules`

On open buffer change (`onDidChange`):
- Re-extracts occurrences for the changed file
- Prefers SemanticDB when text matches disk, falls back to source parse

### LSP handlers

`BasamakeLanguageServer` implements `LanguageClientAware`, `LanguageServer`, `TextDocumentService`, `WorkspaceService`:
- `initialize()`: builds workspace index, returns capabilities
- `definition()`: `CompletableFuture.supplyAsync` → `workspaceIndex.gotoDefinitions(path, line, char)`
- `references()`: `CompletableFuture.supplyAsync` → `workspaceIndex.references(path, line, char, includeDecl)`
- `documentSymbol()`: returns empty (v1)
- Text doc lifecycle: `didOpen`/`didChange`/`didSave`/`didClose` → delegates to `WorkspaceIndex`

All work runs on `supplyAsync` — no blocking on lsp4j threads.

### Concurrency

`Main.run()` blocks on `future.get()` (returns when stdin EOF). No virtual threads, no actor model, no process supervision.

### BSP lifecycle (v2)

`BspManager` discovers `.bsp/*.json` at `initialize()` but spawns no processes (lazy). The first
`poke(uri, compile)` LSP-side — from `didOpen`/`didSave`/`definition`/`references` — calls
`BspConnection.ensureConnected()` which spawns + handshakes. `spawnLock` serializes
`spawnAndHandshake` (preventing concurrent process starts). A volatile `spawning` flag lets
fast-path callers detect an in-progress spawn: pokes return immediately (no-op), compiles
are queued in a `CopyOnWriteArrayList` (dedup via `addIfAbsent`) and drained after spawn
succeeds. On spawn failure the queue is cleared; no cooldown, no retry limits, no
`BspUnavailable` — every user action is a fresh attempt.

## Logging

Configured programmatically in `LoggingUtils.configureFileLogging()` — no `logback.xml` on classpath. One appender:

- **File → `.basamake/logs/basamake.log`** in the workspace root

Called from `Main.run()` with the workspace path. Reconfiguration-safe (detaches old FILE appender if re-invoked).

## VS Code Extension

Separate directory: `../basamake-vscode/` (sibling to basamake repo). Symlinked into `~/.vscode/extensions/basamake.local`. No `.vsix` needed for dev.

**To update:** copy the fat JAR into the extension dir, then **Reload Window** in VS Code (`Ctrl+Shift+P` → "Developer: Reload Window"). VS Code may accumulate zombie basamake processes — kill them manually if you see stale entries in `jps -vlm`.

The extension registers `.scala`/`.sbt` file associations. If you also have Metals installed, VS Code prompts which LSP to use.

## Tests

Two test modules:

**modules/main-test** (`deder exec -t test -m main-test`):
- `WorkspaceIndexTest` — 27 integration tests covering goto-def, references, cross-file, cross-language (Scala↔Java), no-packages, packages, nested scopes, sbt+semanticdb, scalacli

**modules/navigation-test** (`deder exec -t test -m navigation-test`):
- `ScalaDefinitionsExtractorTest` — Pass 1 Scala definition extraction
- `ScalaReferencesResolverTest` — Pass 2 Scala reference resolution
- `ImportScopeTest` — Import scope parsing
- `ScopeStackTest` — Scope stack traversal
- `JavaDefinitionsExtractorTest` — Pass 1 Java definition extraction
- `JavaReferencesResolverTest` — Pass 2 Java reference resolution
- `JavaLangSymbolsTest` — java.lang.* default-import symbols
- `SymbolUtilsTest` — SemanticDB symbol encoding
- `SymbolTableTest` — SymbolTable concurrency

Tests use fixture source files under `test/resources/examples/`. No real build tool needed.

## Test Hygiene

- No hardcoded absolute home paths (`/home/<user>`) in tests, scripts, or source — use
  `System.getProperty("java.home")`, `os.home`, or paths relative to `os.pwd`.
- Fixtures live under `test/resources/` (including committed binary data: the commons-net
  sources jar, and the sbt fixture's `target/scala-3.8.4/meta` semanticdb files — generated
  once, never by tests).
- Tests never shell out to build tools and never write into fixture folders: they copy
  fixtures to `<repo>/tmp/<test>-<timestamp>/` first; any sbt/deder shell-out happens there.

## SemanticDB Reference

Spec summary + basamake consumer notes: **`agents/semanticdb.md`** — symbol format, descriptor suffixes, occurrences, SUID encoding, TextDocument layout, Scala 2 vs 3 differences.

## Key Files for Agents

| File | Why |
|------|-----|
| `modules/main/src/ba/sake/basamake/Main.scala` | JVM entry, Logback file config, stdout/lsp4j wiring |
| `modules/main/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala` | LSP handlers (definition, references, text doc lifecycle) |
| `modules/main/src/ba/sake/basamake/lsp/index/WorkspaceIndex.scala` | Core index — goto-def, references, buffer state, SemanticDB fallback |
| `modules/main/src/ba/sake/basamake/lsp/index/SemanticdbIndexing.scala` | SemanticDB `.semanticdb` file parser |
| `modules/navigation/src/ba/sake/basamake/navigation/SymbolTable.scala` | Global symbol table (ConcurrentHashMap) |
| `modules/navigation/src/ba/sake/basamake/navigation/SymbolUtils.scala` | SemanticDB symbol encoding |
| `modules/navigation/src/ba/sake/basamake/navigation/scalasrc/ScalaDefinitionsExtractor.scala` | Pass 1: Scala def extraction |
| `modules/navigation/src/ba/sake/basamake/navigation/scalasrc/ScalaReferencesResolver.scala` | Pass 2: Scala ref resolution |
| `modules/navigation/src/ba/sake/basamake/navigation/javasrc/JavaDefinitionsExtractor.scala` | Pass 1: Java def extraction |
| `modules/navigation/src/ba/sake/basamake/navigation/javasrc/JavaReferencesResolver.scala` | Pass 2: Java ref resolution |
| `modules/main/src/ba/sake/basamake/bsp/BspManager.scala` | Owns connections, router, watcher, diagnostics accumulator, shutdown |
| `modules/main/src/ba/sake/basamake/bsp/BspConnection.scala` | One BSP process — `@volatile alive`, `spawnLock`, `spawning` flag, pending-compile queue |
| `modules/main/src/ba/sake/basamake/bsp/BspHandshake.scala` | Spawn + handshake, queue-free, eventSink-based build client |
| `modules/main/src/ba/sake/basamake/bsp/BspRouter.scala` | Two-phase URI routing (ground-truth + bootstrap heuristic) |
| `examples/hello/` | Test project (manual testing via vscode extension; `.bsp` configs generated per machine via README flow) |
