---
title: BSP Handshake
description: Sequence of BSP protocol messages during build server initialization
---

# BSP Handshake (Spawning a Build Server)

**File:** `BspHandshake.scala`

Straight-line blocking sequence on the supervisor virtual thread:

```diagram:mermaid
sequenceDiagram
    participant SVC as BspConnectionSupervisor
    participant HK as BspHandshake
    participant BSP as Build Server Process

    SVC->>HK: execute(bspFile, queue, timeoutSec=20)

    HK->>HK: ProcessBuilder(argv).start()
    HK->>HK: lsp4j JSON-RPC launcher
    Note over HK: stdin/stdout to process
    HK->>HK: launcher.startListening()

    HK->>BSP: buildInitialize(caps)
    BSP-->>HK: InitializeBuildResult
    HK->>BSP: onBuildInitialized()

    HK->>BSP: workspaceBuildTargets()
    BSP-->>HK: targets (list of BuildTarget)

    HK->>BSP: buildTargetSources(targets)
    BSP-->>HK: SourcesResult (source dirs per target)

    HK->>BSP: buildTargetDependencySources(targets)
    BSP-->>HK: DependencySourcesResult (JAR URIs per target)

    HK-->>SVC: HandshakeResult(process, server, targets, sources, depSources)

    Note over SVC: State: Connected
    SVC->>MGR: onRoutingReady(buildServer, targets, sources, depSources)
    MGR->>MGR: registerGroundTruth(connId, sourceDirs)
    MGR->>MGR: refreshNavigationIndex(connId, buildServer, targetIds)
```

The `BasamakeBuildClient` (BSP client callbacks) feeds BSP-originated messages back into the connection's `BlockingQueue`. The most important callback is `onBuildPublishDiagnostics`, which enqueues `ConnectionMessage.BspPublishDiagnostics`.

**Error handling:** If any step throws, `BspHandshake` kills the process before rethrowing. The supervisor transitions to `Failed`.
