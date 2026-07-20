---
title: File Watching and BSP Topology Changes
description: How filesystem changes to .bsp JSON files trigger connection attach, detach, and reload
---

# File Watching & BSP Topology Changes

**Files:** `FileChangeWatcher.scala`, `BuildServerManager.scala` (lines 237–327)

```diagram:mermaid
sequenceDiagram
    participant FS as Filesystem
    participant WAT as FileChangeWatcher
    participant MGR as BuildServerManager
    participant TIMER as Debounce Timer
    participant RTR as BspRouter

    FS-->>WAT: file created/modified/deleted
    WAT->>MGR: onFileChanged(paths)
    MGR->>MGR: filter watchIgnored (target/, out/, .deder/, etc.)
    MGR->>MGR: filter .bsp/ paths only

    MGR->>TIMER: enqueueBspChangeBatch(batch)

    Note over TIMER: 300ms debounce
    
    TIMER-->>TIMER: Timer fires
    TIMER->>MGR: handleBspChanges(batch)

    MGR->>MGR: discover current .bsp files
    MGR->>MGR: classifyBspChanges(known, current, changed)
    
    alt Deleted .json
        MGR->>MGR: detachConnection(connId)
        MGR->>RTR: unregisterGroundTruth / unregisterBspRoot
    else New .json
        MGR->>MGR: parseSingleSpec → attachConnection
        MGR->>RTR: registerBspRoot
    else Modified .json
        MGR->>MGR: reloadConnection(connId, newSpec)
        MGR->>Q: offer(ReloadRequested)
    end

    MGR->>MGR: replayOpenAndErroredUris()
```

`watchIgnored` excludes:
- `.basamake/logs/`
- `target/` (sbt artifact dir)
- `out/` (Mill artifact dir)
- `.deder/`
- `.metals/`

`classifyBspChanges` diffs the previous snapshot (`knownBspFiles`) against current filesystem to detect new, deleted, and modified `.bsp/*.json` files.
