---
title: "Flow: BSP File Removed"
description: What happens when a .bsp JSON file is deleted - detach, clear diagnostics, poison pill
---

# What Happens When a `.bsp/*.json` File Is Deleted

Trigger: filesystem change detected, debounce completes, classify shows a known `.bsp/*.json` is no longer on disk.

```diagram:mermaid
sequenceDiagram
    participant FS as Filesystem
    participant WAT as FileChangeWatcher
    participant MGR as BuildServerManager
    participant TIMER as Debounce Timer
    participant RTR as BspRouter
    participant SVC as BspConnectionSupervisor
    participant NAV as SemanticdbNavigationIndex

    FS-->>WAT: file deleted
    WAT->>MGR: onFileChanged([path])
    MGR->>MGR: filter .bsp/ paths only
    MGR->>TIMER: enqueueBspChangeBatch(batch)

    Note over TIMER: 300ms debounce

    TIMER-->>TIMER: Timer fires
    TIMER->>MGR: handleBspChanges(batch)

    MGR->>MGR: BspDiscovery.discover(workspaceRoot)
    MGR->>MGR: classifyBspChanges(knownBspFiles, current, changed)
    
    Note over MGR: path in deletedFiles
    
    MGR->>MGR: knownBspFiles -= path
    MGR->>MGR: detachConnection(connId)
    
    par Publish empty diagnostics
        MGR->>SVC: for each URI in lastKnownDiagnostics.keys
        MGR->>ED: publishDiagnostics(uri, emptyList)
    end
    
    par Mark detached and send poison pill
        MGR->>SVC: state = Detached
        MGR->>SVC: queue.offer(Shutdown)
    end
    
    par Clean routing and index
        MGR->>RTR: unregisterGroundTruth(connId)
        MGR->>RTR: unregisterBspRoot(bspDir, connId)
        MGR->>NAV: clear()
    end
    
    MGR->>MGR: connections -= connId
    
    Note over MGR: Supervisor VT exits its loop
    Note over MGR: BSP process killed by supervisor's finally block
```

## Key Points

- **BSP process auto-shutdown** — deleting the `.json` (or setting `enabled: false` in config) triggers `detachConnection`. The BSP process is killed by the supervisor's `finally` block, all diagnostics cleared, routing removed.
- **Poison pill** — `Shutdown` unblocks the supervisor VT. The `finally` block in `transitionToRunning` calls `destroyProcess`.
- **Routing removed** — ground truth and bootstrap cache entries deleted.
- **Navigation index cleared** — all symbol data for this connection is dropped.
