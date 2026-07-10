# AGENTS.md — Basamake LSP Server

## Libraries
- use os-lib for file paths, file watching

## Build & Test

```bash
# Compile
deder exec

# Run tests (munit)
deder exec -t test -m core-test

# Build fat JAR → copy to VS Code extension
deder exec -t assembly -m core
cp .deder/out/core/assembly/out.jar ../basamake-vscode/basamake.jar

# Clean build state (when things get stuck)
deder clean && deder exec
```

**Zombie killer** — stale `deder bsp` processes block new connections. Before relaunching:

```bash
pkill -9 -f "deder bsp"; pkill -9 -f "basamake.*jar"; sleep 1
```

The code also auto-kills stale `deder bsp` processes on handshake start (`BspHandshake.killStaleBspProcesses`).

**Deder server** keeps socket files in `.deder/`. Run `deder shutdown` before branch switches or deleting the project.

## JDK

Requires **JDK 24+** (JEP 491 unpins VTs on `synchronized`). Current env has JDK 21 — works for dev but must be 24 for production. Scala 3.7.4.

## Code Style

**Prefer curly braces `{}` over colon-syntax `:` for class/object/def bodies** longer than ~10 lines. Short bodies can keep the colon.

```scala
// Good — short body, colon is fine
object BspConnectionId:
  def apply(value: String): BspConnectionId = value
  extension (id: BspConnectionId) def value: String = id

// Good — longer body, use curlies
object BspDiscovery extends StrictLogging {
  def discover(workspaceRoot: Path): List[BspConnectionFile] = {
    // ... 20+ lines
  }
}
```

## Smoke Test

```bash
cd examples/hello
deder clean        # clear cache so Deder actually recompiles
python3 smoke_test.py
```

Checks full LSP flow: initialize → BSP handshake → compile → diagnostics forwarded.

## stdout Is Sacred

The LSP transport rides on stdout. **Nothing else may write to it.** Two guards:

1. Logback console appender targets `System.err` — explicitly: `consoleAppender.setTarget("System.err")`
2. Before passing stdout to `LSPLauncher`, wrap in auto-flush: `new PrintStream(System.out, true, "UTF-8")` — lsp4j output buffers otherwise
3. Logback root logger calls `detachAndStopAllAppenders()` at startup to kill any default stdout appender

**How to verify:** `strace -e write -f java -jar ...` — check that fd 1 (stdout) only has JSON-RPC, never text.

## LSP / BSP Stack

**lsp4j 0.24.0** for editor protocol, **bsp4j 2.1.1** for build-server protocol.

### bsp4j gotcha

In bsp4j 2.1.1, there is **no** `ch.epfl.scala.bsp4j.Launcher` class (removed since 2.0.0). Use lsp4j's launcher instead:

```scala
new org.eclipse.lsp4j.jsonrpc.Launcher.Builder[BuildServer]()
  .setRemoteInterface(classOf[BuildServer])
  .setLocalService(buildClient)
  .setInput(process.getInputStream)
  .setOutput(process.getOutputStream)
  .create()
```

Build tool info is parsed from `.bsp/*.json` using **bare regex** — no JSON library. The JSON arrays are trivial enough this is fine.

### Compile triggering

Currently **compile-on-save only** (`didSave` triggers `buildTargetCompile`). `didChange` is a no-op — debounce pipeline (ox channels) is deferred to later. Edit on keystroke won't recompile.

### ANSI in diagnostics

Build tools emit ANSI color codes in compile error messages. `BspConnectionSupervisor` strips them with `"\u001b\\[[0-9;]*m".r` before forwarding to LSP. LSP clients choke on raw ANSI.

## Architecture Notes

### Concurrency model

- One VT per BSP connection supervisor, started via `Thread.ofVirtual().start(...)`
- Actor model by hand: each connection has a `BlockingQueue[ConnectionMessage]`
- LSP handlers drop messages into the queue, return instantly — no work on lsp4j threads
- `LockSupport.park()` keeps the main JVM thread alive after `launcher.startListening()` starts async message processing
- `exit()` calls `System.exit(0)` to kill everything including parked threads

### State machine

`BspConnectionState` enum: `Idle → Spawning → Handshaking → Connected → BackoffWait → Failed/Detached`. Crash (reader fork EOF) and Reload (`.json` changed) are distinct paths. Backoff is exponential with 30s cap, 10 max attempts. `DurableRecord` owns `attemptCounter` and `lastKnownDiagnostics` — they survive scope teardown.

### Process cleanup

Two layers:
1. `BspConnectionSupervisor.destroyProcess()` in `finally` block — kills the BSP process when transitionRunning exits
2. `BspHandshake.killStaleBspProcesses()` scans OS process handles for orphan `deder bsp` before spawning new ones

## Logging

Configured programmatically in `Main.configureLogging()` — no `logback.xml` on classpath. Two appenders:

- **Console → stderr** (VS Code captures this in the Output panel)
- **File → `.basamake/logs/basamake.log`** in the workspace root

After LSP `initialize`, `reconfigureFileLogging()` adjusts the file path to the actual workspace (since the CLI `--workspace` arg may be absent when launched from VS Code).

## VS Code Extension

Separate directory: `../basamake-vscode/` (sibling to basamake repo). Symlinked into `~/.vscode/extensions/basamake.local`. No `.vsix` needed for dev.

**To update:** copy the fat JAR into the extension dir, then **Reload Window** in VS Code (`Ctrl+Shift+P` → "Developer: Reload Window"). VS Code may accumulate zombie basamake processes — kill them manually if you see stale entries in `jps -vlm`.

The extension registers `.scala`/`.sbt` file associations. If you also have Metals installed, VS Code prompts which LSP to use.

## Tests

Tests live in `modules/core/test/src/ba/sake/basamake/` — Deder's `core-test` module with `moduleDeps { core }` and munit 1.0.4. Two suites:

- `DiagnosticsAccumulatorTest` — pure-function BSP→LSP diagnostic accumulation (reset semantics, multi-target union, clearUri)
- `StateMachineTest` — backoff counter, delay calculation, supervisor loop conditions

Tests don't require a real BSP process. Run with `deder exec -t test -m core-test`.

## Key Files for Agents

| File | Why |
|------|-----|
| `plans/00-overview.md` | Architecture, principles, milestone map |
| `plans/01-milestone-diagnostics.md` | Current milestone — what's built and why |
| `modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala` | State machine, message dispatch, diagnostics handling |
| `modules/core/src/ba/sake/basamake/bsp/BspHandshake.scala` | BSP process spawn, JSON-RPC handshake, stale process killer |
| `modules/core/src/ba/sake/basamake/Main.scala` | JVM entry, Logback config, stdout/lsp4j wiring |
| `modules/core/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala` | LSP handlers, workspace extraction from initialize.rootUri |
| `examples/hello/` | Test project with deliberate compile errors + smoke_test.py |
