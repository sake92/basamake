# BSP v2 — Simplified Multi-BSP Layer

**Date:** 2026-07-30
**Status:** Design (pending user review)
**Scope:** Replace `basamake_old` BSP plumbing (supervisor + queue + LockSupport + per-connection nav) with a direct-call model. Navigation stays single-source in `WorkspaceIndex`; BSP exists only to compile on save (regenerate `.semanticdb`), relay diagnostics, and keep a warm process for the user's next action. Async `initialize` indexing via virtual threads is **out of scope** — separate follow-up.

## Motivation

`basamake_old` works but is hard to maintain:
- `BspConnectionSupervisor` (595 lines) implements a 7-state machine (`Idle/Spawning/Handshaking/Connected/BackoffWait/Reloading/Failed/Detached`) plus a health-probe poller racing the dispatch loop. The "deder dies after 10 min idle" bug lives in this probe logic.
- `NavRefreshState` parks a VT per connection and uses `LockSupport.park/unpark` from three call sites to coalesce SemanticDB refresh work. Hand-rolled coalescing — hard to reason about.
- Per-connection `NavigationIndex` + `DependencySliceCache` + mutable `sourceDirsByTarget`/`dependencySourceUrisByTarget` on `BspConnectionContext` duplicate state already owned by `WorkspaceIndex`.
- `.basamake/status.json` writer spins a 1 s-loop VT for a debug aid.

User explicit goals:
1. Drop `LockSupport.park` and the 7-state machine. "Simple, simple, simple."
2. Keep process cleanup rock-solid — no lingering deder processes when LSP closes (the one part of the old code that works well).
3. Recover automatically on next user action: user returns after deder's 10 min idle timeout, opens a file → liveness check fails → kill + respawn + single retry. Safe from constant restarting.

## Decisions (locked with user)

| Decision | Choice |
|---|---|
| Navigation architecture | Keep current `WorkspaceIndex` (reads `.semanticdb`); add one `invalidate(dirs)` method |
| Concurrency | Direct calls on lsp4j `supplyAsync`. No `BlockingQueue`. No `LockSupport`. One `Object` lock per connection serializes calls |
| Multi-BSP scope | Keep `BspRouter` (148 lines, park-free) — one `BspConnection` per `.bsp/*.json` |
| Reconnect | Liveness check on user action → kill + respawn + bounded retry. No polling, no heartbeat VT |
| Poke triggers | `didOpen` → liveness only. `didSave` → liveness + compile. `definition`/`references` → liveness only (does not block nav response). `didChange`/`didClose` → nothing |
| Spawn timing | Lazy: discover `.bsp` at initialize, spawn process only on first poke |
| Diagnostics | Relay BSP `publishDiagnostics` → LSP `publishDiagnostics`. Accumulate per-target, republish union across targets |
| File watcher | Keep — observe `.bsp/*.json` for add/delete/modify → attach/detach/reload connections |
| status.json | Drop — replace with log lines. No 1 s-loop VT |
| Cooldown | After 3 rapid respawn fails: 5 s cooldown, then retry allowed. Reset counter immediately on successful handshake |
| `MaxRespawnPerCall` | 1 — one respawn attempt per user poke, never a hot loop |
| Async `initialize` indexing | Out of scope. Separate follow-up (`plans/16-async-indexing.md`) |

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  BasamakeLanguageServer (existing, slightly grown)   │
│   gotodef/refs → WorkspaceIndex (UNCHANGED except +invalidate) │
│   didOpen/didSave → BspManager.poke(uri, action)     │
└──────────────────────┬──────────────────────────────┘
                       │
        ┌──────────────▼──────────────┐
        │  BspManager                  │  ~180 lines
        │   - discover .bsp json       │
        │   - route uri → BspConnection│
        │   - file watcher → reload    │
        │   - diagnostics accumulator  │
        │   - shutdown: kill all trees │
        └───────┬──────────────┬───────┘
                │              │
   ┌────────────▼──┐   ┌───────▼────────┐
   │ BspRouter     │   │ BspConnection    │  ~120 lines
   │ (kept verbatim)│   │  + BspHandshake │  (kept, trimmed)
   └───────────────┘   └─────────────────┘
                       │  ensureConnected() / poke() / compile()
                       ▼
   ┌──────────────────────────────┐
   │  WorkspaceIndex (UNCHANGED)   │  reads .semanticdb after compile
   └──────────────────────────────┘
```

Navigation stays single-source in `WorkspaceIndex`. BSP exists only to (a) compile on save to regenerate `.semanticdb`, (b) relay diagnostics, (c) keep a warm process for the user's next action.

## File map

### New files

| Path | Contents |
|---|---|
| `modules/main/src/ba/sake/basamake/bsp/BspManager.scala` | ~180 lines. Owns `ConcurrentHashMap[BspConnectionId, BspConnection]`, `BspRouter`, file watcher. Public: `initialize(workspace, lspClient)`, `poke(uri, compile: Boolean)`, `shutdown()`. Diagnostics accumulator (per `uri` → per `targetId` → diagnostics, republish union across targets). |
| `modules/main/src/ba/sake/basamake/bsp/BspConnection.scala` | ~120 lines. `process`, `buildServer`, `@volatile alive`. Methods: `ensureConnected()`, `poke()`, `compile(uri, onAfterCompile)`, `shutdown()`. One `Object` lock per connection. Storm protection: `MaxRespawnPerCall=1`, `CooldownMs=5000`, `MaxConsecutiveFails=3`. |
| `modules/main/src/ba/sake/basamake/bsp/BspUnavailable.scala` | 1 line. `final case class BspUnavailable(msg: String) extends RuntimeException(msg)` — thrown when in-cooldown, swallowed by `BspManager.poke`. |
| `modules/main-test/src/.../bsp/BspConnectionTest.scala` | Mock `BuildServer`; assert `ping` timeout → `killTree` + one respawn; `MaxRespawnPerCall` honored; cooldown kicks in after 3 rapid fails; `alive=false` flips from `process.onExit`. |
| `modules/main-test/src/.../bsp/BspHandshakeTest.scala` | Process that exits during handshake → process killed, exception propagates. |
| `modules/main-test/src/.../bsp/BspManagerShutdownTest.scala` | After `shutdown()`: `ProcessHandle.current().descendants()` empty — no lingering processes. |
| `modules/main-test/src/.../bsp/BspManagerRoutingTest.scala` | Two nested `.bsp` dirs → route to correct connection (builds on existing `BspRouter` behaviour). |

### Kept verbatim

| Path | Why |
|---|---|
| `util/ProcessUtils.scala` (terminateProcessTree, terminateProcessHandleTree) | Works — keeps tree-kill semantics. |
| `BspRouter.scala` (148 lines) | Already park-free, longest-prefix routing with `.bsp` bootstrap fallback. |
| `RoutingTable.scala` (43 lines) | Used by `BspRouter`. |
| `BspDiscovery.scala` (49 lines) | Walks workspace for `.bsp/*.json`, parses to `BspConnectionSpec`. |
| `bsp/package.scala` `BspConnectionId` opaque type + `BspConnectionSpec` case class | Keep. Drop `BspConnectionState` enum — replaced by `@volatile Boolean alive`. Drop `BspDiscoveryFile` if `BasamakeConfig.bspOverrides` trimming removes it (keep — watcher still needs it). |

### Kept and trimmed

| Path | Changes |
|---|---|
| `BspHandshake.scala` | Drop `queue` param (no queue now). Keep stderr-drain logic (plain `Thread` or VT — cosmetic). Returns `HandshakeResult` unchanged. |
| `BasamakeBuildClient.scala` | Keep `publishDiagnostics` + `buildTargetDidChange` callbacks. Drop the `queue` field — call directly into a `BspManager` callback via a small trait `BspEventSink`. ~40 lines. |
| `Main.scala` | Add `Runtime.getRuntime.addShutdownHook(new Thread(() => server.cleanup(), "basamake-shutdown-hook"))` like old `Main`. Implement `server.cleanup()` → `bspManager.shutdown()`. |
| `BasamakeLanguageServer.scala` | Add `bspManager: BspManager`. `initialize` → `bspManager.initialize(workspace, client)`. `didOpen`/`didSave` → `bspManager.poke(uri, compile = false/true)`. `definition`/`references` wrappers do **two things in parallel**: `supplyAsync` poke (fire-and-forget, `compile=false`) returns void; the synchronous `workspaceIndex` lookup returns current data. `shutdown`/`exit` → `bspManager.shutdown()`. Add `def cleanup()` called by `shutdown`/`exit` and the JVM shutdown hook. Keep current `WorkspaceIndex` plumbing unchanged. |

### Dropped

- `BspConnectionSupervisor.scala` (595 lines, 7-state machine, health probe, `consecutiveProbeFailures`, `compileInFlight`, `transitionToBackoff`, `backoffSleep`, `ConnectionGracePeriodMs`, `MaxCrashRetries` math)
- `BspConnectionState` enum — replaced by `@volatile Boolean alive`
- `NavRefreshState` + parked VT + `navRefreshPending` AtomicReference + all `LockSupport.park/unpark`
- `NavigationIndex`, `DependencySliceCache`, `DependencySourceParsing`, `extractTargetSourceDirs`/`dependencySourceUrisByTarget` — all nav lives in `WorkspaceIndex`
- `DurableRecord` — replaced by `BspConnection` volatile fields
- `ConnectionMessage` sealed trait + `BlockingQueue[ConnectionMessage]` — gone
- `.basamake/status.json` writer + 1 s-loop VT — gone. Log lines replace it.
- `BuildServerManager.scala` (511 lines) — replaced by `BspManager.scala` (~180 lines). File watcher + debounce timer kept but moved in.

Net: ~1,250 lines old plumbing → ~500 lines new. Zero `LockSupport`. Zero background polling threads except os-lib file-watcher callback (already existing, already cleaned up on `watcher.stop()`).

## BspConnection — the small object

```scala
class BspConnection(
    val spec: BspConnectionSpec,
    lspClient: LanguageClient,
    onAfterCompile: List[BuildTargetIdentifier] => Unit,
    eventSink: BspEventSink
) extends StrictLogging:
  @volatile private var process: java.lang.Process = null
  @volatile private var buildServer: BuildServer = null
  @volatile private var alive = false
  @volatile var inverseSourcesUnsupported = false

  private val lock = Object()
  private val consecutiveFails = AtomicInteger(0)
  private val lastFailMs = AtomicLong(0)

  private val MaxRespawnPerCall = 1
  private val CooldownMs = 5_000L
  private val MaxConsecutiveFails = 3
  private val PingTimeoutSec = 2L
  private val ShutdownTimeoutSec = 2L

  def ensureConnected(): Unit = lock.synchronized:
    if alive then return
    val now = System.currentTimeMillis()
    if consecutiveFails.get() >= MaxConsecutiveFails &&
       (now - lastFailMs.get()) < CooldownMs
    then throw BspUnavailable("in cooldown after repeated failures")
    spawnAndHandshake()                // BspHandshake.execute(spec) — no queue
    process.onExit().thenRun(() => alive = false)
    alive = true
    consecutiveFails.set(0)

  def poke(): Unit = lock.synchronized:
    if !alive then { ensureConnected(); return }
    try
      buildServer.workspaceBuildTargets()
        .get(PingTimeoutSec, TimeUnit.SECONDS)
    catch case e =>
      logger.warn(s"ping failed, killing ${process.pid()}: ${e.getMessage}")
      killTree(); alive = false
      ensureConnected()                // one respawn attempt, errors bubble

  def compile(uri: String): Unit = lock.synchronized:
    poke()                             // liveness first
    val targetIds = selectTargets(uri)
    if targetIds.nonEmpty then
      try
        val result = buildServer.buildTargetCompile(new CompileParams(targetIds.asJava))
          .get(spec.compileTimeoutSec, TimeUnit.SECONDS)
        if result.getStatusCode == StatusCode.OK || hasBestEffortFlag(targetIds)
        then onAfterCompile(targetIds) // → BspManager → WorkspaceIndex.invalidate(dirs)
      catch case e =>
        logger.error(s"compile failed for $uri", e)

  def shutdown(): Unit = lock.synchronized:
    alive = false
    if buildServer != null then tryGracefulShutdown()
    killTree()

  // helpers
  private def killTree(): Unit =
    if process != null && process.isAlive then ProcessUtils.terminateProcessTree(process)
  private def tryGracefulShutdown(): Unit =
    try
      buildServer.buildShutdown().get(ShutdownTimeoutSec, TimeUnit.SECONDS)
      buildServer.onBuildExit()
    catch case _: Exception => ()
  private def hasBestEffortFlag(targetIds): Boolean = /* unchanged from old */
  private def selectTargets(uri): List[BuildTargetIdentifier] = /* 3-tier: inverseSources cached / source-root match / all targets */
```

### No queue. No park. No state machine.

- `lock.synchronized` serializes per-connection calls. One user save at a time is acceptable — `didSave` has no return value, lsp4j does not pipeline two saves for the same connection.
- JVM/Scala `synchronized` is **reentrant** — `compile` does `lock.synchronized { poke(); ... }` and `poke` itself does `lock.synchronized { ... }` on the same `lock`; the same thread re-enters its own monitor without blocking. `ensureConnected` called from within `poke` is the same — reentrant. No self-deadlock.
- `process.onExit().thenRun(() => alive = false)` is the only async piece. Its callback body never re-enters `lock` — it just flips a `@volatile` flag. The next caller to enter `poke`/`compile` re-spawns. No re-queue logic, no race.
- `lock.synchronized` is the explicit, bounded replacement for the implicit supervisor-VT serialization. It cannot deadlock because:
  1. `onExit` callback never calls back into `lock`.
  2. `onAfterCompile` (called inside `compile` under `lock`) calls `WorkspaceIndex.invalidate` which uses its own separate inner lock — lock-order is always `[BspConnection.lock]` then `[WorkspaceIndex.this]`, never reverse (WorkspaceIndex never calls back into BspConnection while holding its lock).

### Connection state beyond `alive`

`BspConnection` also keeps, populated at handshake and used for compile targeting + `invalidate` wiring:
- `@volatile var sourceDirsByTarget: Map[BuildTargetIdentifier, List[String]]` — from `SourcesResult`, used by `selectTargets` source-root fallback.
- `def sourceDirs: List[String]` — flattened `sourceDirsByTarget.values.flatten`, passed to `BspManager`'s `onAfterCompile` callback which forwards to `WorkspaceIndex.invalidate(dirs)`.

These are `@volatile var` (write-once per handshake, read on `compile`). Reconnect just overwrites them. No AtomicReference wrapper needed — single writer is the lock holder; readers see the latest value on next `synchronized` entry. No `DurableRecord` analogue.

### Storm protection

- `MaxRespawnPerCall = 1` — a failing deder gets ONE respawn attempt per user action. Never a hot loop.
- `consecutiveFails >= 3 && within 5s cooldown` → `ensureConnected` throws `BspUnavailable` instead of hammering deder. **No backoff math library** (`Math.pow` etc.) — flat constants.
- `consecutiveFails.set(0)` happens immediately on a successful handshake. Simple. (User approved.)
- Cooldown is per-connection: a broken connection does not block other workspaces' connections.

### Poke triggers (per user pick)

| LSP event | Manager action | BSP action | Blocks nav response? |
|---|---|---|---|
| `didOpen(uri)` | `poke(uri, compile = false)` | ensureConnected only | no — poke on its own `supplyAsync` |
| `didSave(uri)` | `poke(uri, compile = true)` | ensureConnected + compile | no |
| `definition(uri,pos)` | `poke(uri, compile = false)` (fire-and-forget) + synchronous `workspaceIndex.gotoDefinitions` | liveness only | nav returns current disk data |
| `references(uri,pos)` | same pattern | liveness only | nav returns current disk data |
| `didChange`/`didClose` | nothing | nothing | n/a |

**`definition`/`references` poke runs on a separate `CompletableFuture.supplyAsync` (fire-and-forget) and does NOT block the nav response.** Nav reads `WorkspaceIndex` synchronously inside its own `supplyAsync` and returns current data. The poke just makes sure deder is warm for the *next* action. If deder is dead, the user's next save will have a warm server ready.

### Automatic recovery flow

1. deder dies (idle 10 min) → `process.onExit().thenRun` → `alive = false`. No thread work, no polling.
2. User returns, opens file → `didOpen` → `poke()` → `!alive` → `ensureConnected()` → fresh spawn + handshake. Single attempt (`MaxRespawnPerCall = 1`).
3. If deder is still broken (not transient):
   - spawn fails → `consecutiveFails++`, `lastFailMs = now`, log + publish error diagnostic for the saved file
   - next save within cooldown (`< 5s` and `>= 3 fails`) → skip spawn, keep diagnostic (do not spam)
   - next save past cooldown → fresh spawn attempt again
4. Recovery is guaranteed as long as deder can start. No manual LSP restart needed. Permanent failure path only when deder genuinely cannot start — user sees squiggles, fixes env, next save retries. (User approved UX.)

## Lifecycle guarantees (hard requirement)

1. `Main` adds `Runtime.getRuntime.addShutdownHook(new Thread(() => server.cleanup(), "basamake-shutdown-hook"))` mirroring old `Main`.
2. `server.cleanup()` → `bspManager.shutdown()`. Idempotent via `AtomicBoolean shuttingDown`.
3. `BspManager.shutdown()`:
   - stop file watcher first (so no new connections spawned mid-shutdown)
   - cancel debounce timer
   - for each `BspConnection`: `shutdown()` (graceful 2 s `buildShutdown` → `destroyProcess` via `ProcessUtils.terminateProcessTree`)
   - `ProcessUtils.terminateProcessHandleTree(ProcessHandle.current())` as last-resort to kill anything still lingering
4. LSP `shutdown`/`exit` handlers call `bspManager.shutdown()` (same path; double-shutdown cheap due to idempotent flag).
5. Per-process guarantee: `process.onExit().thenRun(() => alive = false)` means even an unexpected exit doesn't orphan state — next `poke` respawns cleanly. `killTree` always called in `shutdown` and on liveness-failure, so a dead-but-undetected deder gets `destroyForcibly`'d before respawn.

## WorkspaceIndex integration

Add **one method**:

```scala
def invalidate(sourceDirs: List[String]): Unit = synchronized:
  // re-read .semanticdb for files under these dirs; re-populate SymbolTable slice for those files
  // mirrored after the existing onDidSave path but scoped to sourceDirs instead of a single path
```

Current `onDidSave` already re-extracts per file from disk. `invalidate` re-runs `SemanticdbIndexing` discovery for the given source dirs and re-populates the `SymbolTable` slices for files whose `.semanticdb` mtime advanced since last read. Small addition (~40 lines), no architecture change. Called from `BspConnection.compile`'s `onAfterCompile` callback which `BspManager` wires to the directory list (extracted once at handshake, kept on the connection).

## Diagnostics relay

`BasamakeBuildClient` implements `BuildClient`. On `onBuildPublishDiagnostics(params)`:
- Per `(uri, targetId)`: if `params.getReset` replace, else append.
- Accumulate in `BspManager`'s diagnostics map: `uri → targetId → List[Diagnostic]`.
- Republish **union across all targets** via `lspClient.publishDiagnostics(new PublishDiagnosticsParams(uri, union))`.

On connection detach (`.bsp` deleted or shutdown): publish empty diagnostics for all `uri`s owned by the connection. (Mirrors old `detachConnection`.)

## BspConfig overrides

Minimal carry-over from `BasamakeConfig.bspOverrides`:
- `enabled: Boolean` (default true) — drop connection if false
- `compileTimeoutSec: Long` (default inherited from spec)
- Drop `debounceMs` override (use a single flat 500 ms constant for file-watcher debounce in `BspManager`).

## Build config change

`deder.pkl` (main module): uncomment `ch.epfl.scala:bsp4j:2.2.0-M2`.
Keep `com.softwaremill.ox::core` out of dependencies — we are not using it.

## Test strategy

**Unit / integration tests in `modules/main-test`:**

1. `BspConnectionTest` — mock `BuildServer`:
   - `ping` future times out → `killTree` called + `ensureConnected` retried once + `MaxRespawnPerCall` honored (no infinite loop)
   - simulate 3 rapid respawn fails → 4th call within 5 s throws `BspUnavailable`
   - simulate 3 fails + wait 5 s + call → spawn attempted again (cooldown lifted)
   - successful handshake resets `consecutiveFails` to 0 immediately
   - `process.onExit` callback triggers `alive = false`
2. `BspHandshakeTest` — real process that exits during handshake → process killed via `ProcessUtils`, exception propagates, no `Process` handle leaked.
3. `BspManagerRoutingTest` — two nested `.bsp` dirs → `BspRouter.route(uri)` selects correct connection.
4. `BspManagerShutdownTest` — after `shutdown()`: assert `ProcessHandle.current().descendants().count() == 0` (no lingering processes). Tests the user's non-lingering requirement directly.
5. `BspManagerDiagnosticsTest` — two targets emit diagnostics for same uri; assert union published; `reset=true` clears only that target's slice.

Existing `WorkspaceIndexTest` (27 tests) must remain green — the only `WorkspaceIndex` change is additive (`invalidate`).

## Testing the user flow (smoke)

`examples/hello/smoke_test.py` exists for the current LSP flow. Extend or add `examples/hello/bsp_smoke_test.py`:
- Start LSP → no deder process running (`pgrep -f deder` empty).
- Send `didOpen` → `pgrep -f deder` shows a single process.
- Send `didSave` → compile triggered → `.semanticdb` files regenerated → `definition` request returns the updated location.
- Kill deder process externally (`pkill -f deder`) → send `didOpen` → new deder process spawned within ~2 s.
- Close LSP (stdin EOF) → `pgrep -f deder` empty again within ~5 s.

This directly exercises the lazy-spawn + recovery + cleanup guarantees.

## Out of scope

- Async (virtual-thread) `WorkspaceIndex.initialize` indexing — separate follow-up patch `plans/16-async-indexing.md`. v1 BSP ships without it; `initialize` stays synchronous but is now fast because we dropped the per-connection nav indexing the old design did.
- `completion`/`hover`/`rename` — still return empty / null (same as today).
- Multi-root workspaces — still single-root only (same as today).
- Notifications (`window/showMessage` other than the connection-failure error).
- `documentSymbol` — still returns empty (same as today).