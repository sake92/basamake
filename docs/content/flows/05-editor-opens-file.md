---
title: "Flow: Editor Opens File"
description: What happens when the editor opens a file - lazy BSP process spawn and compile
---

# What Happens When the Editor Opens a File

Trigger: editor sends `didOpen` via LSP for a file not yet compiled.

```diagram:mermaid
sequenceDiagram
    participant ED as Editor
    participant LSP as BasamakeLanguageServer
    participant MGR as BuildServerManager
    participant RTR as BspRouter
    participant Q as Connection Queue
    participant SVC as BspConnectionSupervisor
    participant BSP as Build Server

    ED->>LSP: didOpen(uri)
    LSP->>MGR: trackDidOpen(uri)
    LSP->>MGR: route(uri)
    MGR->>RTR: route(uri)
    
    alt No BSP connection for URI
        RTR-->>MGR: None
        MGR-->>LSP: None
        LSP->>LSP: log warning, drop message
    else Connection found
        RTR-->>MGR: connId
        MGR-->>LSP: queue
        LSP->>Q: offer(DidOpen)
        
        alt Supervisor is Idle
            Note over SVC: queue.take() unblocks with DidOpen
            Note over SVC: State: Idle → Spawning
            
            SVC->>BSP: BspHandshake.execute()
            Note over BSP: spawn, init, targets, sources, depSources
            
            Note over SVC: State: Spawning → Connected
            SVC->>MGR: onRoutingReady(server, targets, sources)
            MGR->>RTR: registerGroundTruth(connId, sourceDirs)
            MGR->>MGR: refreshNavigationIndex(connId, server, targetIds)
            
            SVC->>BSP: buildTargetCompile(for this file's targets)
            BSP-->>SVC: CompileResult
            alt Compilation OK
                SVC->>MGR: onCompileSuccess → refreshNavigationIndex
            else Errors
                BSP-->>SVC: onBuildPublishDiagnostics
                SVC->>ED: publishDiagnostics(uri, diags)
            end
        else Supervisor is Connected
            Note over SVC: dispatch loop picks up DidOpen
            SVC->>BSP: buildTargetCompile(for this file's targets)
        end
    end
```

## Key Points

- **Lazy spawn on first open** — If the connection's supervisor is in `Idle`, the `DidOpen` message triggers the full BSP handshake. No process starts until a file in that BSP's territory is opened.
- **trackDidOpen** — `BuildServerManager` tracks open URIs so it can replay them after topology changes.
- **After handshake** — `onRoutingReady` registers ground-truth source directories and triggers initial navigation index build.
- **Compile triggered** — Whether newly spawned or already connected, the opened file triggers a compile.
