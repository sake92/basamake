# Basamake — Agent Instructions

## Stack

- Scala 3.7.4, **JDK 21+**; build tool: **deder** (config `deder.pkl`)
- LSP protocol: **lsp4j 1.0.0**; BSP client protocol: **bsp4j 2.2.0-M2**
- Parsing: scalameta (Scala), javaparser (Java); os-lib paths, lmdbjava, tupson, logback, munit
- Deep architecture (navigation model, dep/JDK LMDB cache, BSP lifecycle): **`.agents/AGENTS.md`**

## Commands

| Task | Command |
|------|---------|
| Compile | `deder exec` |
| Test navigation module | `deder exec -t test -m modules-navigation-test` |
| Test main module | `deder exec -t test -m modules-main-test` |
| All tests | `deder exec -t test` |
| Fat JAR (for VS Code) | `deder exec -t assembly -m modules-main` → `.deder/out/modules-main/assembly/out.jar` |
| Clean build state | `deder clean && deder exec` |

## Git hygiene

- **Never `git add -f` (force-add) anything that is gitignored** — `docs/superpowers/` included. Gitignored files stay untracked; if a gitignored file is already tracked, leave it alone (don't stage, don't commit, don't unstage)
- Only stage and commit intended, non-ignored changes

## Deder hygiene

- Run `deder shutdown` when finished working — stale server processes block new connections and linger in git worktree dirs; also run it before branch switches or deleting the project
- Kill stale basamake/deder processes: `pkill -9 -f "deder bsp"; pkill -9 -f "basamake.*jar"`

## Code style

- **Avoid braceless syntax for bodies longer than 3 lines.** Short bodies (≤3 lines) may keep the colon; longer bodies must use curly braces `{}`
- os-lib for file paths; no hardcoded absolute home paths — use `System.getProperty("java.home")`, `os.home`, or `os.pwd`-relative paths

## stdout is sacred

- The LSP transport rides on stdout; nothing else may write to it. Logging is file-only (`.basamake/logs/basamake.log`); lsp4j gets an auto-flush wrapper (`PrintStream(System.out, true, "UTF-8")`) since its output buffers otherwise
- Verify with `strace -e write -f java -jar ...` — fd 1 must carry only JSON-RPC, never text

## Tests

- Layout: `modules/<m>/test/src/...`; module ids `modules-navigation-test` / `modules-main-test`; munit
- `modules-navigation-test`: extractors + resolvers (Pass 1/Pass 2), import/scope, indexing (`WorkspaceIndexTest`, GitIgnore*, LmdbSerializer, SourceJarIndexer, DepsGotoDef)
- `modules-main-test`: real JSON-RPC transport (`LspTransportTest`), server behavior (`BasamakeLanguageServerTest`), BSP lifecycle, config
- Integration tests copy fixtures to `<repo>/tmp/<test>-<timestamp>/` first; never write into `test/resources`; no `.semanticdb` committed — `SemanticdbFixture` compiles a tmp copy with `scala-cli compile --server=false --semanticdb`
- No build-tool shell-outs in tests except scala-cli inside tmp copies

## VS Code extension (dev)

- Sibling repo `../basamake-vscode/` (symlinked to `~/.vscode/extensions/basamake.local`); no `.vsix` needed
- Rebuild the fat JAR → copy into the extension dir → **Reload Window** (`Ctrl+Shift+P` → Developer: Reload Window)
- VS Code may accumulate zombie basamake processes — kill them manually (`jps -vlm`)
- Registers `.scala`/`.sbt` associations; with Metals installed, VS Code prompts which LSP to use

## External references

| Need | File |
|------|------|
| Architecture (navigation, cache, BSP, logging) | `.agents/AGENTS.md` |
| SemanticDB spec + consumer notes | `agents/semanticdb.md` |
| Dev setup / contributing | `CONTRIBUTING.md` |

## Key files

| File | Why |
|------|-----|
| `modules/main/src/ba/sake/basamake/Main.scala` | JVM entry, project-root resolution, stdout/lsp4j wiring |
| `modules/main/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala` | LSP handlers (definition, references, text doc lifecycle) |
| `modules/navigation/src/ba/sake/basamake/navigation/indexing/WorkspaceIndex.scala` | Core index — goto-def, references, buffer state, SemanticDB fallback |
| `modules/navigation/src/ba/sake/basamake/navigation/indexing/SemanticdbIndexing.scala` | `.semanticdb` file parser |
| `modules/navigation/src/ba/sake/basamake/navigation/SymbolTable.scala` | Symbol table (ConcurrentHashMap) |
| `modules/navigation/src/ba/sake/basamake/navigation/SymbolUtils.scala` | SemanticDB symbol encoding |
| `modules/navigation/src/ba/sake/basamake/navigation/scalasrc/ScalaDefinitionsExtractor.scala` | Pass 1: Scala def extraction |
| `modules/navigation/src/ba/sake/basamake/navigation/scalasrc/ScalaReferencesResolver.scala` | Pass 2: Scala ref resolution |
| `modules/navigation/src/ba/sake/basamake/navigation/javasrc/JavaDefinitionsExtractor.scala` | Pass 1: Java def extraction |
| `modules/navigation/src/ba/sake/basamake/navigation/javasrc/JavaReferencesResolver.scala` | Pass 2: Java ref resolution |
| `modules/main/src/ba/sake/basamake/bsp/BspManager.scala` | Owns connections, router, watcher, diagnostics, shutdown |
| `modules/main/src/ba/sake/basamake/bsp/BspConnection.scala` | One BSP process — `@volatile alive`, `spawnLock`, `spawning` flag, pending-compile queue |
| `modules/main/src/ba/sake/basamake/bsp/BspHandshake.scala` | Spawn + handshake, eventSink-based build client |
| `modules/main/src/ba/sake/basamake/bsp/BspRouter.scala` | Two-phase URI routing (ground-truth + bootstrap heuristic) |
| `modules/navigation/src/ba/sake/basamake/navigation/indexing/SourceJarIndexer.scala` | Dep/JDK sources cache (LMDB index, lazy unpack) |
| `modules/navigation/src/ba/sake/basamake/navigation/indexing/IndexedSymbolTable.scala` | Read-only dep/JDK symbol lookups |
| `examples/hello/` | Manual-test project (VS Code extension; per-machine `.bsp` via README flow) |
