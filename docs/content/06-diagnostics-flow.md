---
title: Diagnostics Flow
description: How compile diagnostics flow from BSP server through queue to the editor
---

# Diagnostics Flow

When the BSP server sends `onBuildPublishDiagnostics`, the `BasamakeBuildClient` wraps it as `ConnectionMessage.BspPublishDiagnostics` and enqueues it. The supervisor's dispatch loop calls `handleDiagnostics`:

```diagram:mermaid
flowchart TD
    BSP["BSP build server"] -->|"onBuildPublishDiagnostics"| CLIENT["BasamakeBuildClient"]
    CLIENT -->|"queue.offer(BspPublishDiagnostics)"| Q[BlockingQueue]
    Q -->|"dispatch()"| SVC["BspConnectionSupervisor"]
    SVC --> HD["handleDiagnostics(params, durable, lspClient)"]
    
    HD --> ACC["Accumulate per URI, per targetId\nrespecting reset flag"]
    ACC --> PUBLISH["Publish full union across all targets\nfor this URI"]
    PUBLISH --> EDITOR["editor.publishDiagnostics(uri, allDiags)"]
    
    BSP -->|"compile complete"| SVC["supervisor: triggerCompile returns"]
    SVC -->|"onCompileSuccess"| MGR["BuildServerManager\n→ refreshNavigationIndex"]
```

Diagnostics are stored per-file, per-target in `DurableRecord.lastKnownDiagnostics`. When a connection is detached, empty diagnostics are published for all previously-diagnosed URIs to clear stale errors.

**ANSI stripping:** Build tools like sbt emit color codes. `stripAnsi()` removes them with `"\u001b\\[[0-9;]*m".r` before forwarding to the editor.
