---
title: "Flow: BSP File Modified"
description: What happens when a .bsp JSON file changes - reload connections, re-evaluate overrides
---

# What Happens When a `.bsp/*.json` File Is Modified

Trigger: filesystem change detected, debounce completes, classify shows a known `.bsp/*.json` changed content.

```diagram:mermaid
sequenceDiagram
    participant FS as Filesystem
    participant WAT as FileChangeWatcher
    participant MGR as BuildServerManager
    participant TIMER as Debounce Timer
    participant SVC as BspConnectionSupervisor

    FS-->>WAT: file modified
    WAT->>MGR: onFileChanged([path])
    MGR->>MGR: filter .bsp/ paths only
    MGR->>TIMER: enqueueBspChangeBatch(batch)

    Note over TIMER: 300ms debounce

    TIMER-->>TIMER: Timer fires
    TIMER->>MGR: handleBspChanges(batch)

    MGR->>MGR: BspDiscovery.discover(workspaceRoot)
    MGR->>MGR: classifyBspChanges(knownBspFiles, current, changed)
    
    Note over MGR: path in modifiedFiles (intersection of known and changed)
    
    MGR->>MGR: BspDiscovery.parseSingleSpec(path)
    MGR->>MGR: applyOverrides(newSpec)
    
    alt Override disables it
        MGR->>MGR: detachConnection(connId)
    else Override enabled
        MGR->>SVC: queue.offer(ReloadRequested(newSpec))
        
        Note over SVC: Supervisor receives ReloadRequested
        
        alt Connection is Idle
            Note over SVC: Just updates bspFile in DurableRecord
            Note over SVC: Stays Idle, new spec used on next spawn
        else Connection is Connected
            Note over SVC: State → Reloading → Spawning
            Note over SVC: Old process killed in finally block
            SVC->>SVC: Spawn + handshake with new argv
        else Connection is BackoffWait
            Note over SVC: Updates bspFile, state → Reloading → Spawning
        end
    end
    
    MGR->>MGR: replayOpenAndErroredUris()
```

## Key Points

- **No backoff** — `Reloading` transitions directly to `Spawning`, skipping the 1s backoff.
- **Old process killed** — the `finally` block in `transitionToRunning` calls `destroyProcess` on the old BSP process.
- **Overrides re-evaluated** — if the override was disabled, a modified `.json` triggers `detachConnection` instead of reload.
- **Replay** — open/errored URIs get recompiled after the new BSP process connects.
