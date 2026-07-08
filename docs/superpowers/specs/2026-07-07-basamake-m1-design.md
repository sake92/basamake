# Basamake M1 — BSP Diagnostics & Connection Lifecycle (Design)

Date: 2026-07-07

## Overview

A Scala 3 language server (lsp4j) that forwards build-compiler diagnostics from a BSP build
server (bsp4j) to the editor. The real work is a rock-solid connection lifecycle: distinct
crash-reconnect and config-reload paths, exponential backoff with a cap, diagnostics preserved
through transient reconnects, and debounced compile-on-change.

The M2 `BuildServerManager` skeleton is included from the start — M1 operates as a
single-connection manager, structurally ready for N connections in M2.

## Runtime & Dependencies

- **JDK 24+** — virtual threads, with JEP 491 unpinning `synchronized` in VT contexts.
- **Scala 3** (3.x LTS or latest stable).
- **Deder** build tool (`deder.pkl`).
- **lsp4j** — LSP server interface to editor.
- **bsp4j** — BSP client interface to build server.
- **ox** — structured concurrency for scoped forks, channels, and cooperative sleep.
- **os-lib** — file watching (M2; wired but idle in M1).

## Package Structure

```
modules/core/src/ba/sake/basamake/
  core/
    ConnectionSpec.scala         # path, argv, workingDir, debounceMs, overrides
    ConnectionId.scala           # typed wrapper around BSP .json filename
    ConnectionState.scala        # enum: Idle, Spawning, Handshaking, Connected, BackoffWait, Reloading, Failed, Detached
    DurableRecord.scala          # spec, attemptCounter, lastKnownDiagnostics, currentState
  manager/
    BuildServerManager.scala     # owns durable records, channels, watcher, lifecycle
    Discovery.scala              # autodiscover .bsp/*.json or explicit config list → List[ConnectionSpec]
  bsp/
    BspConnectionSupervisor.scala  # outer state-machine loop (while state != Failed/Detached)
    BspHandshake.scala            # blocking init/targets/sources sequence on VT
    OurBuildClient.scala          # BuildClient impl; only onBuildPublishDiagnostics is non-noop
  lsp/
    BasamakeLanguageServer.scala  # lsp4j TextDocumentService + LanguageServer; handlers enqueue into actor
```

Base package: `ba.sake.basamake`.

---

## Concurrency Model

- One VT per connection supervisor (`BspConnectionSupervisor`).
- Each connection has a `BlockingQueue[ConnectionMessage]` — the actor inbox.
- LSP/BSP callbacks drop messages into the queue; the supervisor VT processes them sequentially.
- ox scopes gate ephemeral state (process, reader fork, handshake fork, debounce fork, compile
  forks). State transitions cancel the scope (forks unwind) and open a fresh one.
- `CompletableFuture.get()` is called from inside the actor VT — straight-line blocking, no
  callback chains.
- No shared locks. State lives in the `DurableRecord` owned by the manager, mutated only by the
  owning VT.

### ConnectionMessage (the actor protocol)

```scala
sealed trait ConnectionMessage
object ConnectionMessage:
  // LSP → actor (lsp4j types passed through)
  case class DidOpen(params: DidOpenTextDocumentParams)    extends ConnectionMessage
  case class DidChange(params: DidChangeTextDocumentParams) extends ConnectionMessage
  case class DidSave(params: DidSaveTextDocumentParams)    extends ConnectionMessage
  case class DidClose(params: DidCloseTextDocumentParams)  extends ConnectionMessage

  // BSP → actor (bsp4j types passed through)
  case class BspPublishDiagnostics(params: PublishDiagnosticsParams) extends ConnectionMessage

  // Internal events (our own types)
  case object ReaderForkCrashed                            extends ConnectionMessage
  case object HandshakeCompleted                           extends ConnectionMessage
  case class HandshakeFailed(cause: Throwable)             extends ConnectionMessage
  case class ReloadRequested(newSpec: ConnectionSpec)       extends ConnectionMessage
```

Protocol payloads use lsp4j/bsp4j types directly. The sealed trait is only a routing tag.

---

## LSP Layer (`BasamakeLanguageServer`)

Implements `TextDocumentService` and `LanguageServer` from lsp4j. Every handler is a one-liner:

```scala
override def didOpen(params: DidOpenTextDocumentParams): Unit =
  manager.route(params.getTextDocument.getUri).offer(DidOpen(params))
```

- `manager.route(uri)`: returns the connection's `BlockingQueue[ConnectionMessage]`. In M1 it
  always returns the single connection; in M2 it's a routing table lookup.
- Connection spawning happens in `initialized` (the post-handshake notification), per BSP
  protocol convention.
- `initialize` returns capabilities synchronously: `TextDocumentSyncKind.Full`.
- `shutdown` sets all connections to `Detached`, supervisors exit cleanly.
- Advertises `TextDocumentSyncKind.Full` so we always have current buffer contents.

---

## BuildServerManager (M1 skeleton)

```scala
class BuildServerManager(lspClient: LanguageClient):
  private val connections = mutable.Map[ConnectionId, DurableRecord]()
  private val channels    = mutable.Map[ConnectionId, BlockingQueue[ConnectionMessage]]()

  def initialize(rootDir: Path): Unit              // discovery → spawn supervisors
  def route(uri: DocumentUri): BlockingQueue[ConnectionMessage]  // .values.head in M1
  def shutdown(): Unit                             // Detached → supervisors exit
```

Discovery: for M1, expects exactly one `.bsp/*.json` in the workspace root. The manager spawns
one supervisor VT. M2 replaces the discovery and routing logic without changing the LSP layer.

The manager owns the durable record (see below). It survives connection-scope teardown.
The connection scope (ox) is ephemeral and destroyed/recreated on every transition.

---

## DurableRecord

```scala
case class DurableRecord(
  spec: ConnectionSpec,
  attemptCounter: Int,                                    // critical: survives crashes
  lastKnownDiagnostics: Map[DocumentUri, Map[String, List[Diagnostic]]],  // URI → target → diags
  currentState: ConnectionState
)
```

- **attemptCounter** increments on every crash/handshake-failure. Resets to 0 only on successful
  entry to `Connected`. Lives here so crash→Backoff→crash doesn't hot-loop.
- **lastKnownDiagnostics**: keyed `URI → targetId → List[Diagnostic]`. A file can receive
  diagnostics from multiple build targets (multimodule). Survives Backoff/Reloading so squiggles
  don't flicker during reconnect. Cleared only on clean detach (`.json` deleted).
- Both fields must outlive the ephemeral ox scope (which is destroyed on every crash).

---

## Connection State Machine

States are named by what forks are alive in the connection's ox scope. Transitioning out =
cancel the scope; transitioning in = open a new scope.

| State | Live forks in scope | Notes |
|-------|--------------------|-------|
| `Idle` | none | scope exists, empty |
| `Spawning` | process + **reader fork** | reader fork is long-lived; survives into Handshaking/Connected |
| `Handshaking` | reader fork + handshake fork | blocking init/targets/sources sequence |
| `Connected` | reader fork + **debounce fork** + transient **compile forks** | steady state |
| `BackoffWait` | sleep timer only (minimal ox scope) | failure-driven |
| `Reloading` | none (teardown) | user-driven; immediate |
| `Failed` | none | terminal until user intervention |
| `Detached` | none | terminal; connection removed |

### Transitions

```
Idle → Spawning : spawn
Spawning → Handshaking : stream up
Handshaking → Connected : handshake ok
Handshaking → BackoffWait : handshake throws/times out
Connected → BackoffWait : crash/EOF (reader fork dies)
BackoffWait → Spawning : retry, attempt n < max
BackoffWait → Failed : attempt n == max
Connected → Reloading : .json content changed (signal from manager's watcher)
Reloading → Idle → Spawning : respawn immediately with new spec
any → Detached : .json deleted (manager clean detach)
```

### Supervisor Loop

```scala
def supervise(durable: DurableRecord, queue: BlockingQueue[ConnectionMessage], lspClient: LanguageClient): Unit =
  while durable.currentState != Failed && durable.currentState != Detached do
    durable.currentState match
      case Idle | Reloading => transitionToSpawning(durable, queue)
      case Spawning         => waitForHandshakeResult(durable, queue) // blocks
      case Handshaking      => waitForHandshakeResult(durable, queue) // blocks
      case Connected        => processConnected(durable, queue, lspClient) // blocks
      case BackoffWait      => backoffSleep(durable)                 // blocks
  if durable.currentState == Failed then
    lspClient.showMessage(MessageParams(MessageType.Error, "BSP connection failed after max retries"))
```

The outer `while` loop owns the state; ox scopes are nested within each state's handler.
When a scope fails (exception/unwind), control returns to the outer loop, which examines the
state and decides the next action.

### Crash vs. Reload

They share the Spawn+Handshake tail but differ in trigger, source of truth, and policy:

| Aspect | Crash | Reload |
|--------|-------|--------|
| Trigger | Inside (reader fork EOF) | Outside (watcher, .json changed) |
| Policy | Exponential backoff up to max | Immediate, unconditional |
| Source | Same spec | New spec from rewritten .json |

---

## Backoff Policy

```
attempt 1: 1s wait
attempt 2: 2s wait
attempt 3: 4s wait
attempt 4: 8s wait
attempt 5: 16s wait
attempt 6+: 30s wait (cap)
attempt 10: Failed (terminal, MAX_ATTEMPTS = 10)
```

Backoff sleep uses a minimal ox scope (`ox.sleep()`) so a `reloadRequested` signal can cancel
the sleep mid-wait and force immediate reload.

---

## BSP Handshake

Straight-line blocking on the connection's VT:

1. Parse `.bsp/<name>.json` → extract `argv` array + `workingDirectory`.
2. Spawn process (`ProcessBuilder`), wire stdio to bsp4j `Launcher`.
3. Start **reader fork** (long-lived — begins listening for BSP notifications immediately,
   before the handshake completes, so early diagnostics are not lost).
4. `buildInitialize(...).get()` → `onBuildInitialized()`.
5. `workspaceBuildTargets().get()` → learn compile targets.
6. `buildTargetSources(...).get()` → learn file ownership per target (feeds M2 routing).

The `BuildClient` implementation (`OurBuildClient`) has one non-trivial callback:

```scala
class OurBuildClient(queue: BlockingQueue[ConnectionMessage]) extends BuildClient:
  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit =
    queue.offer(BspPublishDiagnostics(params))
  // All other callbacks: no-op ({})
```

---

## Liveness Detection

No polling, no heartbeat, no timer. The reader fork blocks on the BSP process's stdout pipe
(bsp4j `Launcher.startListening()`). When the process dies, the OS closes the pipe → EOF →
Launcher throws/returns → reader fork unwinds → `queue.offer(ReaderForkCrashed)`. That event
*is* the liveness signal.

Edge case (deferred): a process that hangs but doesn't die (zombie). For now, the user kills
it manually, which triggers the same recovery path. A configurable heartbeat is out of M1 scope.

---

## Diagnostics Forwarding & Reset Semantics

BSP streams diagnostics per (textDocument, buildTarget) with a `reset` flag:

- `reset = true` → replace that target's accumulated list for the file.
- `reset = false` → append to it.
- `empty array + reset = true` → clear the file for that target.

LSP has no append concept. We maintain `Map[URI, Map[targetId, List[Diagnostic]]]` and
re-publish the **full accumulated list** (union across all targets) every time it changes.

```scala
def handleDiagnostics(params: PublishDiagnosticsParams, durable: DurableRecord): Unit =
  val uri    = params.getTextDocument.getUri
  val target = Option(params.getBuildTarget).map(_.getUri).getOrElse("")
  val newDiags = Option(params.getDiagnostics).getOrElse(List.empty).asScala.toList
  val current  = durable.lastKnownDiagnostics.getOrElse(uri, Map.empty)

  val updated = if params.getReset then
    current + (target -> newDiags)
  else
    current + (target -> (current.getOrElse(target, Nil) ++ newDiags))

  durable.lastKnownDiagnostics += (uri -> updated)
  val allDiags = updated.values.flatten.toList.asJava
  lspClient.publishDiagnostics(PublishDiagnosticsParams(uri, allDiags))
```

Diagnostics persist through Backoff/Reloading (no flicker). Cleared only on clean detach.

---

## Debounce Pipeline (ox)

Edits from `didChange` feed into the debounce fork. The pipeline:

1. Collect target IDs from `didChange` into an ox `Channel`.
2. Debounce: wait `debounceMs` (default 500ms, configurable via `ConnectionSpec`) of quiet.
3. Drain the channel (collapse any edits that arrived during the wait window).
4. Fire `buildTargetCompile`. If a compile is already in flight, cancel the stale fork first
   (supersede).
5. On compile success: diagnostics arrive via `OurBuildClient.onBuildPublishDiagnostics` →
   the reader fork queues them → actor processes them.

The debounce fork lives in the connection's ox scope. When the scope is torn down (crash,
reload), the fork is cancelled and no stale compile completes against a dead scope.

### Cancellation (ox + BSP)

ox cancellation rides on interruption. Interrupting a VT blocked on bsp4j `CompletableFuture`
throws `InterruptedException` and unwinds our fork — but the build server may still be churning
on the compile. For M1, we accept this: the compile continues server-side but its result is
dropped (superseded by a new compile or scope teardown). Adding protocol-level
`$/cancelRequest` is deferred.

---

## Unit Test Plan

Tests verify state machine transitions in isolation — no real BSP process. We mock the process
spawn and feed `ConnectionMessage` values directly into the actor queue.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| 1 | Happy path | Startup: spawn → handshake success → connected | State = Connected, attemptCounter = 0 |
| 2 | Single crash + recovery | Feed ReaderForkCrashed in Connected | State cycles: Connected → BackoffWait → Spawning → Connected; attemptCounter resets to 0 |
| 3 | Backoff timing | Feed repeated crashes, measure delays | Delays follow 1s, 2s, 4s, 8s, 16s, 30s, 30s… |
| 4 | Max attempts → Failed | Feed crashes until attemptCounter hits 10 | State = Failed; showMessage(Error) sent |
| 5 | Reset semantics (reset=true) | Feed BspPublishDiagnostics with reset=true | File's diagnostics replaced, no stale entries from prior target |
| 6 | Reset semantics (reset=false) | Feed BspPublishDiagnostics with reset=false | File's diagnostics appended, prior entries preserved |
| 7 | Ghost error clear | Feed empty + reset=true for a file that had diagnostics | Published list is empty; squiggles vanish |
| 8 | Crash preserves diagnostics | Feed diagnostics, then crash, then reconnect | lastKnownDiagnostics survives backoff; republished on reconnect |
| 9 | Reload during backoff | Feed crash, then ReloadRequested during BackoffWait | Backoff cancelled; state → Reloading (not Spawning) |
| 10 | Debounce collapse | Rapid-fire DidChange, verify only one compile fork fires | Debounce collapses burst into single compile |
| 11 | Supersede | Start a (slow) compile, fire a new edit mid-compile | Stale compile fork canceled; fresh compile fork starts |

---

## Logging

Minimal stderr logging (stdout is LSP transport):

```scala
object Log:
  def info(msg: String): Unit = System.err.println(s"[basamake] $msg")
  def warn(msg: String): Unit = System.err.println(s"[basamake/WARN] $msg")
  def error(msg: String): Unit = System.err.println(s"[basamake/ERROR] $msg")
  def error(msg: String, t: Throwable): Unit =
    System.err.println(s"[basamake/ERROR] $msg")
    t.printStackTrace(System.err)
```

No SLF4J/logback dependency for M1. Editors capture stderr and surface it.

---

## Definition of Done

1. Compile on save → squiggles appear; reset semantics correct (no ghost errors).
2. Kill build-server process → automatic reconnect within backoff window; squiggles preserved,
   no flicker.
3. Repeatedly-crashing server → exponential backoff, then "keeps crashing" message and stop
   (no hot-loop).
4. Unit tests for all state machine transitions pass.

Item 4 from the plan (`.json` rewrite → immediate reconnect) requires the file watcher in the
manager and is deferred to M2.
