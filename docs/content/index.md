---
title: Architecture Overview
description: Basamake LSP server architecture, layer diagram, concurrency model, and subsystem overview
---

# Basamake: Architecture Overview

**Basamake** is a minimal Scala language server. It connects the LSP editor protocol to one or more BSP build servers running inside a workspace.

## Guiding Philosophy

- **Multi-BSP by default** — a workspace may contain multiple build tools (sbt, Mill, scala-cli, etc.), each in a subdirectory with its own `.bsp/` config. Basamake discovers all of them and routes editor requests to the right one automatically.
- **Lazy connections** — BSP processes are not started at editor startup. They spawn only when the first LSP message (didOpen/didSave) targets a URI in their territory.
- **concurrency is done with virtual threads** — `BlockingQueue`, `@volatile` and `synchronized`.
- **SemanticDB for navigation** — go-to-definition and references use SemanticDB protobuf files produced by the compiler, plus a regex-based fallback for dependency sources.

---

## Layer Diagram

```diagram:mermaid
flowchart TB
    subgraph Editor["Editor (VS Code)"]
        LSP["LSP Client\n(stdin/stdout JSON-RPC)"]
    end

    subgraph Basamake["Basamake LSP Server"]
        direction TB
        MAIN["Main.scala\n(LSPLauncher, stdio wiring)"]
        LSS["BasamakeLanguageServer\n(LSP TextDocumentService)"]
        MGR["BuildServerManager\n(central orchestrator)"]
        RTR["BspRouter\n+ RoutingTable\n(two-phase routing)"]
        WAT["FileChangeWatcher\n(os-lib file watching)"]
        CFG["BasamakeConfig\n(.basamake/config.json)"]

        subgraph conn_super["Per-Connection Supervisor (Virtual Thread)"]
            SVC["BspConnectionSupervisor\n(state machine, dispatch)"]
            HANDSHAKE["BspHandshake\n(spawn, BSP init)"]
            QUEUE["BlockingQueue[ConnectionMessage]\n(connection message queue)"]
            DUR["DurableRecord\n(attempts, diagnostics)"]
        end
    end

    subgraph BuildTools["BSP Build Servers"]
        SBT["sbt\n(.bsp/sbt.json)"]
        MILL["Mill\n(.bsp/mill.json)"]
        SC["Scala CLI\n(.bsp/scala-cli.json)"]
    end

    LSP <-->|stdin/stdout JSON-RPC| MAIN
    MAIN --> LSS
    LSS --> MGR
    MGR --> CFG
    MGR --> WAT
    MGR --> RTR
    MGR -->|"attach per .bsp"| SVC
    SVC --> QUEUE
    SVC --> HANDSHAKE
    HANDSHAKE <-->|"JSON-RPC over stdio"| SBT
    HANDSHAKE <-->|"JSON-RPC over stdio"| MILL
    HANDSHAKE <-->|"JSON-RPC over stdio"| SC
    WAT -->|"change events"| MGR
```

---

## Key Files by Concern

| Concern | Files |
|---------|-------|
| JVM entry, stdio, Logback setup | `Main.scala`, `LoggingUtils.scala` |
| LSP protocol handlers | `BasamakeLanguageServer.scala` |
| Configuration | `BasamakeConfig.scala` |
| BSP discovery | `BspDiscovery.scala` |
| Manager (attach/detach/reload) | `BuildServerManager.scala` |
| Per-connection state machine | `BspConnectionSupervisor.scala`, `BspConnectionState.scala` |
| BSP handshake (spawn + init) | `BspHandshake.scala` |
| BSP client callbacks | `BasamakeBuildClient.scala` |
| Connection message protocol | `ConnectionMessage.scala` |
| Durable per-connection state | `DurableRecord.scala` |
| URI→BSP routing | `BspRouter.scala`, `RoutingTable.scala` |
| File watcher | `FileChangeWatcher.scala` |
| Diagnostics accumulation | `DiagnosticsAccumulator.scala` |
| Navigation / go-to-def | `SemanticdbNavigationIndex.scala`, `NavigationSymbolLookup.scala`, `NavigationLocationUtils.scala`, `NavigationRangeUtils.scala`, `NavigationUriUtils.scala` |
| Dependency source parsing | `DependencySourceParsing.scala` |
| Process tree termination | `ProcessUtils.scala` |

---

## Data Flow Overview

```diagram:mermaid
sequenceDiagram
    participant Editor as VS Code
    participant LSP as BasamakeLanguageServer
    participant MGR as BuildServerManager
    participant RTR as BspRouter
    participant Q as BlockingQueue
    participant SVC as BspConnectionSupervisor
    participant BSP as Build Server (sbt/mill/scala-cli)

    Note over Editor, BSP: INITIALIZATION
    Editor->>LSP: initialize(rootUri)
    LSP->>LSP: extract workspaceRoot, configure logging
    Editor->>LSP: initialized()
    LSP->>MGR: initialize(workspaceRoot, client, config)
    MGR->>MGR: BspDiscovery.discover(workspaceRoot)
    MGR->>RTR: registerBspRoot(bspDir)
    Note over MGR: No BSP processes started yet

    Note over Editor, BSP: FIRST FILE OPEN (triggers lazy spawn)
    Editor->>LSP: didOpen(uri)
    LSP->>MGR: route(uri)
    MGR->>RTR: route(uri)
    RTR-->>MGR: connId
    MGR-->>LSP: queue
    LSP->>Q: offer(DidOpen)

    SVC->>Q: take() blocks until first message

    Note over SVC, BSP: State: Idle → Spawning
    SVC->>HK: execute(bspFile, queue, timeout)
    HK->>BSP: spawn process, JSON-RPC init
    BSP-->>HK: buildInitialize, workspaceBuildTargets, sources
    HK-->>SVC: HandshakeResult

    Note over SVC: State: Spawning → Connected
    SVC->>MGR: onRoutingReady(targets, sources)
    MGR->>RTR: registerGroundTruth(connId, sourceDirs)

    Note over SVC: Message dispatch loop starts
    SVC->>BSP: buildTargetCompile (from queued DidOpen)
    BSP-->>SVC: compile result
    alt has diagnostics
        BSP-->>SVC: onBuildPublishDiagnostics
        SVC->>Editor: publishDiagnostics(uri, diags)
    end
```

---

## Concurrency Model

```diagram:mermaid
flowchart LR
    subgraph LSP Threads["lsp4j Thread Pool"]
        LSP["LSP handlers\n(didOpen/didSave)"]
    end
    subgraph VTs["Virtual Threads"]
        VT1["Supervisor VT #1\n(sbt)"]
        VT2["Supervisor VT #2\n(Mill)"]
        VT3["Supervisor VT #3\n(Scala CLI)"]
    end
    subgraph sync["synchronized Blocks"]
        MGR["BuildServerManager\n(connection state)"]
        ROUT["RoutingTable\n(routing entries)"]
        NAV["SemanticdbNavigationIndex\n(index state)"]
    end
    subgraph oscope["os-lib Threads"]
        WAT["File watcher\n(os-lib internal)"]
    end
    subgraph timer["Timer Thread"]
        DEB["Debounce timer\n(BSP change batching)"]
    end

    LSP -->|"route().offer()"| VT1
    LSP -->|"route().offer()"| VT2
    LSP -->|"route().offer()"| VT3
    WAT -->|"onFileChanged"| MGR
    MGR -->|route / attach / detach| ROUT
    VT1 --> MGR
    VT2 --> MGR
    VT3 --> MGR
    DEB -->|"batch handler"| MGR
    VT1 -.->|"synchronized"| NAV
    VT2 -.->|"synchronized"| NAV
    VT3 -.->|"synchronized"| NAV
```

- **One virtual thread per BSP connection** — supervisor VTs block on their connection's `BlockingQueue.take()`.
- **LSP handlers return instantly** — they only `route(uri)` and `queue.offer(msg)`. No compile work on lsp4j threads.
- **File watcher** runs on os-lib internal threads. It calls `BuildServerManager.onFileChanged` which **debounces** and diffs filesystem, then calls `handleBspChanges` from a `TimerTask`.
- **Synchronized on `BuildServerManager`**, `RoutingTable`, and `SemanticdbNavigationIndex` since they are shared mutable state touched from multiple VTs.
- **Pooling not needed** — each connection supervisor has a dedicated queue. No thread-pool contention.

---

## State Management: DurableRecord

A `DurableRecord` per connection survives scope teardown. Its counters and diagnostics outlive individual spawn→crash cycles.

```diagram:mermaid
classDiagram
    class DurableRecord {
        var bspFile: BspConnectionSpec
        var attemptCounter: Int
        var lastKnownDiagnostics: Map[String, Map[String, List[Diagnostic]]]
        @volatile var currentState: BspConnectionState
    }

    class BspConnectionState {
        <<enumeration>>
        Idle
        Spawning
        Handshaking
        Connected
        BackoffWait
        Reloading
        Failed
        Detached
    }

    class ConnectionContext {
        record: DurableRecord
        queue: BlockingQueue~ConnectionMessage~
        navIndex: SemanticdbNavigationIndex
        sourceRootsByTarget: Map
        dependencySourceUrisByTarget: Map
    }

    DurableRecord --> BspConnectionState
    ConnectionContext --> DurableRecord
```

`attemptCounter` lives on the `DurableRecord` specifically to survive between retry cycles. If it were in the supervisor scope, a crash would lose the counter and cause infinite hot-looping.

---

## ConnectionMessage Protocol

**File:** `ConnectionMessage.scala`

Sealed trait defining all messages exchanged via the per-connection `BlockingQueue`. Every message has a single producer and a single consumer.

| Message | Producer | Consumer | Purpose |
|---------|----------|----------|---------|
| `DidOpen` | `BasamakeLanguageServer.didOpen` → `router.route(uri)` → `queue.offer` | `BspConnectionSupervisor.dispatch` | File opened in editor → trigger compile |
| `DidChange` | `BasamakeLanguageServer.didChange` | `BspConnectionSupervisor.dispatch` | Currently a no-op (compile-on-save only) |
| `DidSave` | `BasamakeLanguageServer.didSave` | `BspConnectionSupervisor.dispatch` | File saved → trigger compile |
| `DidClose` | `BasamakeLanguageServer.didClose` | `BspConnectionSupervisor.dispatch` | Currently a no-op |
| `RecheckUri` | `BuildServerManager.replayOpenAndErroredUris` (after BSP topology change) | `BspConnectionSupervisor.dispatch` | Re-trigger compile for open/errored files |
| `BspPublishDiagnostics` | `BasamakeBuildClient.onBuildPublishDiagnostics` — BSP server callback | `BspConnectionSupervisor.handleDiagnostics` | Forward compile errors to editor |
| `ReloadRequested` | `BuildServerManager.reloadConnection` (`.bsp/*.json` changed) | `BspConnectionSupervisor.dispatch` | Respawning BSP with new config |
| `Shutdown` | `BuildServerManager.detachConnection` | `BspConnectionSupervisor` → any state → `Detached` | Poison pill — unblock queue, exit loop |

No message is ever consumed by a different connection's queue. The `BuildServerManager.route()` method ensures each URI maps to exactly one connection via `BspRouter`.

---

## Process Cleanup

Two layers protect against orphaned BSP processes:

1. **Per-connection** — `BspConnectionSupervisor.transitionToRunning` has a `try/finally` that calls `ProcessUtils.terminateProcessTree(process)` when the dispatch loop exits.
2. **Global** — `BuildServerManager.shutdown()` calls `ProcessUtils.terminateProcessHandleTree(ProcessHandle.current())` to kill any remaining descendant processes. Ownership-bounded: only walks this JVM's descendant tree.

```diagram:mermaid
flowchart TD
    A[Detach connection or shutdown] --> B{Process alive?}
    B -->|Yes| C[terminateProcessTree]
    C --> D[Kill descendants, bottom-up]
    D --> E[Kill root process]
    E --> F[waitFor 2s]
    F --> G{Still alive?}
    G -->|Yes| H[destroyForcibly]
    H --> I[Done]
    G -->|No| I[Done]
    B -->|No| I[Done]
```

## Related Documentation

| Topic | File |
|-------|------|
| BSP connection lifecycle | [02-bsp-discovery](02-bsp-discovery.html), [03-bsp-state-machine](03-bsp-state-machine.html), [04-bsp-handshake](04-bsp-handshake.html) |
| URI routing & heuristics | [05-two-phase-routing](05-two-phase-routing.html) |
| Diagnostics accumulation | [06-diagnostics-flow](06-diagnostics-flow.html) |
| File watching & topology | [07-file-watching-topology](07-file-watching-topology.html) |
| Navigation index | [08-navigation-index](08-navigation-index.html) |
| Graceful shutdown | [09-shutdown](09-shutdown.html) |
| Config overrides | [10-config-overrides](10-config-overrides.html) |

**Flows (what happens when...):**

| File | Question Answered |
|------|-------------------|
| [01-file-saved](flows/01-file-saved.html) | What happens on didSave? |
| [02-bsp-file-added](flows/02-bsp-file-added.html) | What happens when a .bsp JSON is added? |
| [03-bsp-file-removed](flows/03-bsp-file-removed.html) | What happens when a .bsp JSON is deleted? |
| [04-bsp-file-modified](flows/04-bsp-file-modified.html) | What happens when a .bsp JSON is modified? |
| [05-editor-opens-file](flows/05-editor-opens-file.html) | What happens on didOpen? (lazy BSP spawn) |
| [06-go-to-definition](flows/06-go-to-definition.html) | What happens on go-to-definition? |
| [07-workspace-init](flows/07-workspace-init.html) | What happens on workspace initialization? |

