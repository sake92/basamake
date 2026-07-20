---
title: BSP Connection State Machine
description: Seven-state lifecycle of BSP connections including lazy spawn, retry, backoff, and reload
---

# BSP Connection State Machine

**Files:** `BspConnectionState.scala`, `BspConnectionSupervisor.scala`

Every connection has a state machine running in its own virtual thread. Seven states:

```diagram:mermaid
stateDiagram-v2
    [*] --> Idle : supervisor thread starts

    Idle --> Spawning : ✅ first message arrives on queue<br/>(DidOpen/DidSave/RecheckUri)
    Idle --> Detached : ☠️ Shutdown poison pill

    Spawning --> Connected : ✅ handshake successful<br/>(spawn → init → targets → sources)
    Spawning --> Failed : ❌ handshake exception

    Connected --> BackoffWait : ❌ dispatch exception<br/>or health probe failure
    Connected --> Reloading : 🔄 ReloadRequested (JSON changed)
    Connected --> Detached : ☠️ Shutdown poison pill

    BackoffWait --> Spawning : 🔄 timeout expires (1s) or new message arrives
    BackoffWait --> Reloading : 🔄 ReloadRequested during backoff
    BackoffWait --> Failed : ❌ attemptCounter > MaxCrashRetries (1)
    BackoffWait --> Detached : ☠️ Shutdown poison pill

    Reloading --> Spawning : 🔄 immediate respawn (no backoff)

    Failed --> [*]
    Detached --> [*]
```

## Design Choices

- **Lazy spawn** — `Idle` blocks on `queue.take()`. First LSP message (didOpen/didSave) triggers `Spawning`.
- **Single retry** — `MaxCrashRetries = 1`. One crash → permanent `Failed`.
- **Fixed 1s backoff** — `BackoffWait` polls queue with 1s timeout. Arriving message during backoff triggers immediate retry.
- **Reload is not a retry** — `Reloading` (triggered by `.bsp/*.json` change) skips backoff, respawns immediately.
- **Poison pill** — `Shutdown` transitions to `Detached` from any state.

## Retry Flow Detail

```diagram:mermaid
flowchart TD
    A[dispatch exception] --> B[transitionToBackoff]
    B --> C{State is Detached<br/>or Failed?}
    C -->|Yes| D[return]
    C -->|No| E[increment attemptCounter]
    E --> F{attemptCounter > 1?}
    F -->|Yes| G["❌ state = Failed"]
    F -->|No| H["⏳ state = BackoffWait"]
    H --> I["backoffSleep: queue.poll with 1s timeout"]
    I --> J{poison pill?}
    J -->|Shutdown| K["☠️ state = Detached"]
    J -->|ReloadRequested| L["🔄 state = Reloading"]
    J -->|null or other| M["✅ state = Spawning"]
```
