# Lazy Multi-BSP Architecture — Design Spec

## Problem

Basamake currently starts every discovered BSP server eagerly at LSP init time. This is fragile
(laptop sleep, hung processes) and memory-wasteful (each BSP is a 500MB-2GB JVM). A workspace
with 5+ subprojects blows through memory. Additionally, routing is naive — it falls back to
the first connection in the map when the routing table misses, which is wrong for multi-BSP
setups.

## Goals

1. **Lazy start**: BSP processes spawn only when a file routed to them is opened/edited. No
   process = no memory.
2. **Correct routing**: Two-phase routing (bootstrap heuristic → ground truth from BSP) with
   per-directory caching. No per-file maps, no tries.
3. **Health detection**: Before dispatching any message to a running BSP, verify it's
   responsive (reactive probe, not polling). Catch hung-but-alive processes.
4. **Simple backoff**: No exponential retry loops. Handshake fails → immediate `Failed`. Crash
   recovery → retry once, then `Failed`. User triggers recovery by touching `.bsp/*.json`.
5. **Transparent buffering**: Requests arriving during BSP spawn+handshake queue naturally
   on the `BlockingQueue` and flush when the BSP reaches `Connected`.

## Routing Architecture

### Two routing layers, tried in order: primary → bootstrap → nothing

```
BspRouter.route(uri):
  1. RoutingTable.lookup(uri)        ← primary: BSP's buildTarget/sources directory prefixes
  2. if miss → BootstrapCache.lookup(uri.parent)  ← fallback: per-directory .bsp ancestor cache
  3. if miss → walk up filesystem to find nearest .bsp/, populate cache for all visited dirs
  4. if still miss → None (no BSP for this file)
```

**Primary routing (ground truth)**: After a BSP completes handshake and announces its source
directories via `buildTarget/sources`, those directories are registered in the `RoutingTable`
(longest-prefix match). This layer is always tried first.

**Bootstrap fallback (heuristic)**: Before a BSP has started (no ground truth available), or
when the routing table has no entry for a URI, walk up from the file's parent directory to
find the nearest ancestor containing a `.bsp/` folder. Result is cached per-directory in a
`HashMap[Path, Option[Set[BspConnectionId]]]`.

**Strict ordering**: primary first, then bootstrap. If both miss, return `None` (no BSP for
this file — status bar indicator, no error).

### Bootstrap cache design

Per-directory `HashMap`, not per-file. Memory: O(directories queried), typically 200-500
entries for a large project.

```
File: /ws/a/b/c/file.scala queried
  → walk: c/ → b/ → a/ → /ws/ (has .bsp/)
  → cache entries: c/→{sbt}, b/→{sbt}, a/→{sbt}, /ws/→{sbt}
  → next file in c/ or b/ or a/ → instant cache hit, no walk
```

**Invalidation**: flush entire cache when the file watcher detects `.bsp/` directory
creation, deletion, or `.json` content change.

### New component: BspRouter

```scala
class BspRouter:
  private val routingTable: RoutingTable       // ground truth (existing)
  private val bootstrapCache: mutable.HashMap[Path, Option[Set[BspConnectionId]]]
  private val bspRoots: Map[Path, Set[BspConnectionId]] // .bsp dir → connections

  def route(uri: String): Option[BspConnectionId]
  def registerGroundTruth(connId: BspConnectionId, sourceDirs: List[String]): Unit
  def registerBspRoot(bspDir: Path, connIds: Set[BspConnectionId]): Unit
  def unregisterBspRoot(bspDir: Path): Unit
  def invalidateBootstrapCache(): Unit
```

Owned by `BuildServerManager`. Manager calls `route(uri)` to find the target connection for
any LSP document event.

---

## BSP Lifecycle

### State machine (unchanged states, changed transitions)

```
Idle ──(any LSP cmd)──→ Spawning ──→ Handshaking ──→ Connected
                            │              │              │
                            │   fail       │   fail       │  health probe fails
                            ▼              ▼              ▼
                          Failed         Failed       BackoffWait
                                                         │
                                                    (retry once)
                                                         │
                                                         ▼
                                                       Failed
```

**Idle** is now a real waiting state. No process is alive. The supervisor VT blocks on
`queue.take()`. Any `DidOpen`, `DidSave`, or `DidChange` message triggers transition to
`Spawning`.

**Spawning + Handshaking** are merged in implementation (handshake is a blocking sequence
inside `transitionToRunning`, as today). During this time, messages arriving on the queue
are buffered naturally — the `BlockingQueue` holds them.

**Crash path** (Connected → BackoffWait): Retry once after 1 second. If it fails again,
transition to `Failed`. The user recovers by modifying `.bsp/*.json` (file watcher sends
`ReloadRequested` to the queue, which resets the attempt counter and transitions to
`Spawning`).

**Reload path** (Connected → Reloading → Spawning): User-driven, triggered by `.json`
content change. Immediate, unconditional. No backoff.

### Lazy start mechanism

```
1. LSP init → BuildServerManager.initialize()
   → BspDiscovery.discover() finds all .bsp/*.json
   → for each file not disabled by overrides:
       create DurableRecord + BlockingQueue + spawn supervisor VT
       state = Idle (NO process spawned)
   → register each .bsp root in BspRouter

2. textDocument/didOpen("file:///ws/src/Foo.scala")
   → LSP handler calls manager.route(uri)
   → BspRouter.route():
       routingTable.lookup() → miss (BSP not started)
       bootstrapCache lookup → hit → returns connId
   → manager sends DidOpen message to connId's queue

3. Supervisor VT (in Idle, blocked on queue.take())
   → receives DidOpen message
   → state → Spawning
   → save triggerMessage = DidOpen
   → BspHandshake.execute(): spawn process + buildInitialize + workspaceBuildTargets + buildTargetSources
   → state → Connected
   → onRoutingReady callback → routingTable.update(connId, sourceDirs)
   → dispatch(triggerMessage)   ← process the message that triggered the spawn
   → enter message loop: while Connected, dispatch messages from queue
```

### Message dispatch in Connected

```scala
while state == Connected:
  msg = queue.poll(HEALTH_TTL)  // block with timeout
  if msg == null:
    // No message within HEALTH_TTL — BSP might be hung
    probeHealth()
    if probe fails → transitionToBackoff()
  else if now - lastSuccessfulResponse > HEALTH_TTL:
    probeHealth()
    if probe fails → transitionToBackoff(), re-queue msg  // retry after restart
    else → dispatch(msg), lastSuccessfulResponse = now
  else:
    dispatch(msg)
```

**Message types and dispatched actions:**
- `DidOpen`, `DidSave` → trigger compile (debounced for DidSave)
- `DidChange` → debounced compile trigger (currently no-op, will be debounced later)
- `DidClose` → no-op (for now)
- `BspPublishDiagnostics` → accumulate + forward to LSP client
- `ReloadRequested(newSpec)` → transition to Reloading
- `Shutdown` → transition to Detached

**Liveness detection**: process death is detected by the health probe failing (timeout or
RPC exception), or by a dispatch RPC call throwing (e.g., `buildTargetCompile` throws on
broken pipe). No separate `ProcessExited` message or reader-fork is needed — the probe and
dispatch exception handling cover all cases. On detection, transition to `BackoffWait` and
re-queue the message for retry after restart.

---

## Health Checking

### Reactive probe, not polling

Health checks fire only when dispatching a message (user action), not on a timer.

**Algorithm:**
1. Before dispatching each message, check: `now - lastSuccessfulResponse > HEALTH_TTL`
2. If stale, send `workspaceBuildTargets` (cheap RPC) with 3-second timeout
3. If probe succeeds: update `lastSuccessfulResponse`, dispatch message
4. If probe fails (timeout/exception): mark state `Dead`, transition to `BackoffWait`,
   re-queue the original message so it replays after restart

**When does `lastSuccessfulResponse` get updated?**
- After any successful BSP RPC call in the dispatch loop (compile result, buildTargets response)
- NOT updated by `BspPublishDiagnostics` (BSP pushes diagnostics asynchronously; not a
  request-response we control)

**Configuration:**
- `HEALTH_TTL`: 30 seconds (hardcoded for now)
- Health probe timeout: 3 seconds (hardcoded for now)
- No user-facing config yet (add to `BasamakeConfig` later if needed)

### Rationale for reactive over polling

- Polling wastes BSP CPU for idle connections (no files open → no messages → no check)
- Laptop sleep: when the user returns and opens/saves a file, the first message dispatch
  triggers the health check. If the BSP hung during sleep, the probe times out and restart
  kicks in. The user sees at most one message delay (probe timeout + restart), not a frozen
  editor.
- If the BSP is actively receiving messages (user editing), the TTL never expires (messages
  arrive faster than 30s) → zero health probes → zero overhead.

### Simpler alternative considered and rejected

"Assume Running is healthy, detect failures naturally when a request is sent" (ChatGPT).
Rejected because a hung BSP causes the request to hang too — the user's editor freezes until
the TCP/pipe timeout (often 30-120s). The reactive probe with 3s timeout catches hangs fast.

---

## Backoff Policy (Simplified)

### Before (M1/M2)
Exponential: 1s, 2s, 4s, 8s, 16s, 30s... max 10 attempts. Over-engineered for a process
that either works or doesn't.

### After (this design)
- **Handshake fails** (never reached Connected): → `Failed` immediately. Config or build
  tool is broken. The user needs to fix it, not watch it fail 10 times.
- **Connected → crash** (process was running, then died): → retry once after 1 second.
  If it crashes again → `Failed`. Transient recoveries are rare; if the BSP can't stay
  alive after two attempts, something is wrong.
- **User-driven reload**: touching `.bsp/*.json` → watcher sends `ReloadRequested` →
  reset attempt counter → spawn unconditionally. This is the escape hatch for "it's
  working now, try again."

**Attempt counter reset**: on successful entry to `Connected` (attemptCounter = 0).

**Removed**: exponential delay calculation, max attempts (was 10, now effectively 1 retry
for crash path, 0 for handshake-fail path), `backoffSleep()` with interruptible poll.

---

## Request Buffering During Spawn

When a `DidOpen` (or any LSP command) triggers idle-to-spawning transition, the handshake
takes 2–5 seconds (buildInitialize + workspaceBuildTargets + buildTargetSources are blocking
RPC calls). During this window, the language client may send additional messages (DidChange
keystrokes, LSP initialized notification, etc.).

**Buffering is automatic**: the `BlockingQueue` already holds unprocessed messages. The
supervisor does not drain the queue during spawning/handshaking (it's executing a blocking
handshake sequence). When the handshake completes:

1. First, dispatch the `triggerMessage` (the one that was consumed from the queue to trigger
   the spawn)
2. Then, enter the normal message loop: `while Connected, queue.poll()/queue.take()` drains
   and dispatches remaining messages in FIFO order

**Coalescing during buffering**: high-frequency notifications like `DidChange` should be
coalesced (only keep latest per document). This is a future optimization — for now, all
messages are dispatched in order, and `DidChange` is a no-op anyway (compile-on-save).

**Edge case**: if the queue is full (unlikely with `LinkedBlockingQueue` default unbounded
capacity), the LSP handler's `queue.offer()` returns false. Current code ignores this return
value. For correctness, switch to `queue.put()` (blocks until space available — VT blocking
is cheap).

---

## File Watcher Integration

### Cache invalidation

When the file watcher detects:
- `.bsp/` directory created → register new bsp root in BspRouter, invalidate bootstrap cache
- `.bsp/` directory deleted → unregister bsp root, invalidate cache, detach connections
- `.bsp/*.json` content changed → reload connection (existing behavior, trigger `ReloadRequested`)

The bootstrap cache is entirely flushed on any `.bsp/` change. This is safe (no stale
pointers) and cheap (cache is rebuilt on next query, one directory walk per newly-opened file).

### No per-file watcher

We only watch `.bsp/` directories (for membership changes) and `.json` files (for content
changes). We do not watch source files — that's a future concern (didChangeWatchedFiles).

---

## Multi-Root Workspace Support

### LSP spec: workspaceFolders

The LSP `initialize` request may provide multiple `workspaceFolders`. Each folder has its own
root URI.

### Discovery per folder

For each workspace folder:
1. Run `BspDiscovery.discover(folderRoot)` independently
2. Register discovered `.bsp` roots in BspRouter with their folder scope

### Walking bounded by folder

When walking up from a file to find the nearest `.bsp/`, stop at the enclosing workspace
folder root. Never walk above it onto unrelated filesystem ancestors.

### What's NOT in scope

- Cross-folder BSP sharing (e.g., a shared build tool serving files from two workspace folders)
- This is a rare edge case; if it arises, user can configure it explicitly via overrides

---

## Component Changes

### New files

| File | Purpose |
|------|---------|
| `routing/BspRouter.scala` | Two-phase routing (RoutingTable + bootstrap cache) |

### Modified files

| File | Change |
|------|--------|
| `BspConnectionSupervisor.scala` | Idle as waiting state (queue.take before spawn); save+dispatch triggerMessage after Connected; health probe in dispatch loop; simplified backoff (no exponential, retry once) |
| `BspConnectionState.scala` | Update Idle description; BackoffWait state stays but now means "sleep 1s, then one retry" instead of exponential loop |
| `BuildServerManager.scala` | Uses BspRouter instead of inline routing + RoutingTable; lazy attach (VT spawn, BSP does NOT start); register .bsp roots in router |
| `BasamakeLanguageServer.scala` | Wire BspRouter into didOpen/didSave/didChange handlers |
| `watcher/BspFileWatcher.scala` | Cache invalidation callbacks on .bsp changes |
| `BspConnectionId.scala` | No change |
| `BspHandshake.scala` | No change |
| `BspDiscovery.scala` | No change |
| `BasamakeBuildClient.scala` | No change |
| `DurableRecord.scala` | No change (attemptCounter semantics change but field stays) |
| `DiagnosticsAccumulator.scala` | No change |
| `ConnectionMessage.scala` | No change |

### Removed / simplified

| What | Why |
|------|-----|
| Exponential backoff delay calculation | Replaced with single 1s sleep for crash path |
| `backoffSleep()` with interruptible poll | Simplified to: `Thread.sleep(1000)`, check attempt counter, if ≤1 → Spawning else Failed |
| Max attempt counter (10) | Now: 0 retries for handshake fail, 1 retry for crash |
| `routingTable` field in `BuildServerManager` | Moved to `BspRouter` |
| `route()` method in `BuildServerManager` | Delegates to `BspRouter.route()` |

---

## Testing Strategy

### Unit tests (new)

1. **BspRouter bootstrap cache**: cache hit, cache miss → walk, cache invalidation, empty
   workspace
2. **BspRouter two-phase routing**: ground truth wins over heuristic, heuristic fallback when
   routing table misses
3. **Lazy start state machine**: supervisor stays Idle until first message, transitions on
   LSP commands, route-to-queue-before-spawn
4. **Simplified backoff**: handshake fail → immediate Failed, crash → 1 retry → Failed

### Integration test (manual, via smoke test)

1. Start LSP with 2+ .bsp roots, no files open → verify no BSP processes spawned (`jps`)
2. Open file from root A → verify only A's BSP spawns, B stays idle
3. Open file from root B → verify B's BSP spawns
4. Kill A's BSP process → verify restart (once) on next file operation
5. Touch A's .bsp/*.json → verify reload
6. Diagnostics from correct BSP for each file

### Existing tests preserved

All M1/M2 tests continue to pass: DiagnosticsAccumulator, StateMachine, BspDiscovery,
RoutingTable, BuildServerManager overrides.

---

## Non-Goals

- Idle BSP eviction (kill BSP after all files closed). Deferred — once started, BSP stays
  alive until LSP shutdown.
- Debounced DidChange compilation. Still compile-on-save only.
- Ox structured concurrency migration. Still raw VTs.
- BSP cancellation (`$/cancelRequest`). Still no remote cancel signal.
- Configurable health TTL / probe timeout. Hardcoded for now.
- Coalescing of DidChange during spawn. All messages dispatched in order for now.
