---
title: Graceful Shutdown
description: Shutdown sequence for stopping watchers, detaching connections, and killing descendant processes
---

# Graceful Shutdown Sequence

```diagram:mermaid
sequenceDiagram
    participant OS as OS / Editor
    participant LSP as BasamakeLanguageServer
    participant MGR as BuildServerManager
    participant WAT as FileChangeWatcher
    participant SVC as BSP Connection Supervisors
    participant PROC as BSP Processes

    alt Normal Shutdown
        LSP->>MGR: shutdown()
    else Stdin EOF (Editor closed)
        MAIN->>LSP: cleanup()
        LSP->>MGR: shutdown()
    end

    MGR->>WAT: stop()
    MGR->>MGR: cancel debounce timer tasks
    MGR->>MGR: cancel debounce timer

    loop For each connection
        MGR->>MGR: detachConnection(connId)
        MGR->>SVC: publish empty diagnostics\nfor all known URIs
        MGR->>SVC: state = Detached\nqueue.offer(Shutdown)
        MGR->>MGR: unregisterGroundTruth\nunregisterBspRoot
        MGR->>MGR: navIndex.clear()
    end

    MGR->>PROC: ProcessUtils.terminateProcessHandleTree(current JVM)
    PROC-->>MGR: descendants killed
```

The shutdown hook (`basamake-shutdown-hook`) ensures cleanup even on `System.exit()` or JVM termination.
