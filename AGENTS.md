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
| Test index module | `deder exec -t test -m modules-index-test` |
| Test lsp module | `deder exec -t test -m modules-lsp-test` |
| All tests | `deder exec -t test` |
| Fat JAR (for VS Code) | `deder exec -t assembly -m modules-lsp` → `.deder/out/modules-lsp/assembly/out.jar` |
| Clean build state | `deder clean && deder exec` |
| Build docs | `./scripts/build-docs.sh` |
| Serve docs (dev) | `./scripts/build-docs.sh serve` → http://localhost:5555 |

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

- Layout: `modules/<m>/test/src/...`; module ids `modules-core-test` / `modules-index-test` / `modules-bsp-test` / `modules-lsp-test`; munit
- `modules-index-test`: extractors + resolvers (Pass 1/Pass 2), import/scope, indexing (`WorkspaceIndexTest`, GitIgnore*, LmdbSerializer, SourceJarIndexer, DepsGotoDef)
- `modules-lsp-test`: `LspIntegrationTest` is THE primary suite — real JSON-RPC transport + real BSP + real compile via `LspTestClient` (scenarios: cold start, warm workflow w/ refs+hover, broken→fixed→broken, watcher file create/delete, BSP kill/respawn, multi-BSP routing); direct-server tests (`BasamakeLanguageServerTest`) keep hover-content, semanticdb-fallback, progress, and .sbt paths; `modules-bsp-test`: BSP lifecycle, config
- Integration tests copy fixtures to `<repo>/tmp/<test>-<timestamp>/` first; never write into `test/resources`; no `.semanticdb` committed — `SemanticdbFixture` compiles a tmp copy with `scala-cli compile --server=false --semanticdb`
- No build-tool shell-outs in tests except scala-cli inside tmp copies

## VS Code extension (dev)

- Sibling repo `../basamake-vscode/` (symlinked to `~/.vscode/extensions/basamake.local`); no `.vsix` needed
- Rebuild the fat JAR → copy into the extension dir → **Reload Window** (`Ctrl+Shift+P` → Developer: Reload Window)
- VS Code may accumulate zombie basamake processes — kill them manually (`jps -vlm`)
- Registers `.scala`/`.sbt` associations; with Metals installed, VS Code prompts which LSP to use

## Docs (flatmark SSG)

- User docs: `docs/content/*.md` — YAML frontmatter (`title`, `description`; `pagination: enabled: false` on index); root-relative links ending in `.html`
- Build: `scripts/build-docs.sh` (build) / `scripts/build-docs.sh serve`; output `docs/_site/` (gitignored)
- Theme: default `sake92/flatmark-themes` (cloned to gitignored `docs/.flatmark-cache/themes`); project overrides: `docs/_layouts/base.html`, `docs/static/`, `docs/_config.yaml`
- Docs stay **user-facing and basic** (mechanisms, not internals) — deep architecture lives in `.agents/AGENTS.md`
- CI: `.github/workflows/ghpages.yml` builds with `FLATMARK_BASE_URL` and deploys `docs/_site` to GitHub Pages

## External references

| Need | File |
|------|------|
| Architecture (navigation, cache, BSP, logging) | `.agents/AGENTS.md` |
| SemanticDB spec + consumer notes | `agents/semanticdb.md` |
| Dev setup / contributing | `CONTRIBUTING.md` |

## Key files

| File | Why |
|------|-----|
| `modules/lsp/src/ba/sake/basamake/Main.scala` | JVM entry, project-root resolution, stdout/lsp4j wiring |
| `modules/lsp/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala` | LSP handlers (definition, references, text doc lifecycle) |
| `modules/index/src/ba/sake/basamake/index/indexing/WorkspaceIndex.scala` | Core index — goto-def, references, buffer state, SemanticDB fallback |
| `modules/index/src/ba/sake/basamake/index/indexing/SemanticdbIndexing.scala` | `.semanticdb` file parser |
| `modules/index/src/ba/sake/basamake/index/SymbolTable.scala` | Symbol table (ConcurrentHashMap) |
| `modules/index/src/ba/sake/basamake/index/SymbolUtils.scala` | SemanticDB symbol encoding |
| `modules/index/src/ba/sake/basamake/index/scalasrc/ScalaDefinitionsExtractor.scala` | Pass 1: Scala def extraction |
| `modules/index/src/ba/sake/basamake/index/scalasrc/ScalaReferencesResolver.scala` | Pass 2: Scala ref resolution |
| `modules/index/src/ba/sake/basamake/index/javasrc/JavaDefinitionsExtractor.scala` | Pass 1: Java def extraction |
| `modules/index/src/ba/sake/basamake/index/javasrc/JavaReferencesResolver.scala` | Pass 2: Java ref resolution |
| `modules/bsp/src/ba/sake/basamake/bsp/BspManager.scala` | Owns connections, router, watcher, diagnostics, shutdown |
| `modules/bsp/src/ba/sake/basamake/bsp/BspConnection.scala` | One BSP process — `@volatile alive`, `spawnLock`, `spawning` flag, pending-compile queue |
| `modules/bsp/src/ba/sake/basamake/bsp/BspHandshake.scala` | Spawn + handshake, eventSink-based build client |
| `modules/bsp/src/ba/sake/basamake/bsp/BspRouter.scala` | Two-phase URI routing (ground-truth + bootstrap heuristic) |
| `modules/index/src/ba/sake/basamake/index/indexing/SourceJarIndexer.scala` | Dep/JDK sources cache (LMDB index, lazy unpack) |
| `modules/index/src/ba/sake/basamake/index/indexing/IndexedSymbolTable.scala` | Read-only dep/JDK symbol lookups |
| `examples/hello/` | Manual-test project (VS Code extension; per-machine `.bsp` via README flow) |
| `docs/content/` | User-facing docs (flatmark SSG; build via `scripts/build-docs.sh`) |
