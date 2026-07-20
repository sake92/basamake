---
title: "Flow: BSP File Added"
description: What happens when a new .bsp JSON file appears in the workspace
---

# What Happens When a `.bsp/*.json` File Is Added

Trigger: filesystem watcher detects a new `.bsp/*.json` file in the workspace.

```diagram:mermaid
sequenceDiagram
    participant FS as Filesystem
    participant WAT as FileChangeWatcher
    participant MGR as BuildServerManager
    participant TIMER as Debounce Timer
    participant RTR as BspRouter
    participant SVC as BspConnectionSupervisor

    FS-->>WAT: file created
    WAT->>MGR: onFileChanged([newBspJson])
    MGR->>MGR: filter .bsp/ paths only
    MGR->>TIMER: enqueueBspChangeBatch(batch)

    Note over TIMER: 300ms debounce

    TIMER-->>TIMER: Timer fires
    TIMER->>MGR: handleBspChanges(batch)

    MGR->>MGR: BspDiscovery.discover(workspaceRoot)
    MGR->>MGR: classifyBspChanges(knownBspFiles, current, changed)
    
    Note over MGR: newBspJson in newFiles
    
    MGR->>MGR: knownBspFiles += newBspJson
    MGR->>MGR: BspDiscovery.parseSingleSpec(newBspJson)
    MGR->>MGR: applyOverrides(spec)
    
    alt Override disables it
        Note over MGR: Connection not created
    else Enabled
        MGR->>MGR: attachConnection(spec)
        MGR->>MGR: create DurableRecord + BlockingQueue + VirtualThread
        
        Note over SVC: Supervisor VT starts, blocks on queue.take()
        MGR->>RTR: registerBspRoot(bspDir, connId)
        Note over RTR: No ground-truth dirs yet (handshake not done)
    end
    
    MGR->>MGR: replayOpenAndErroredUris()
    Note over MGR: Open files in new BSP's territory get RecheckUri
```

## Key Points

- **Lazy** — adding a `.bsp/*.json` creates the connection infrastructure (record, queue, VT) but starts **no BSP process**. Process spawn waits for the first LSP message targeting this connection.
- **Debounced** — rapid batch changes wait 300ms before processing.
- **Overrides honored** — config can disable a connection even before first spawn.
- **Open URIs replayed** — if any currently-open files fall in the new BSP's territory, they get `RecheckUri` to trigger spawn+compile.
