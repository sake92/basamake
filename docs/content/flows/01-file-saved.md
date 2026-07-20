---
title: "Flow: File Saved"
description: What happens when the editor saves a file - compile trigger, target selection, diagnostics
---

# What Happens When a File Is Saved

Trigger: editor sends `didSave` via LSP.

```diagram:mermaid
sequenceDiagram
    participant ED as Editor
    participant LSP as BasamakeLanguageServer
    participant RTR as BspRouter
    participant MGR as BuildServerManager
    participant Q as Connection Queue
    participant SVC as BspConnectionSupervisor
    participant BSP as Build Server

    ED->>LSP: didSave(params)
    LSP->>MGR: route(uri)
    MGR->>RTR: route(uri)
    RTR-->>MGR: connId (or None)
    MGR-->>LSP: queue (or None)

    alt No BSP connection for this URI
        LSP->>LSP: log warning, drop message
    else Connection found
        LSP->>Q: offer(DidSave)
        
        SVC->>SVC: dispatch(DidSave)
        SVC->>SVC: selectCompileTargetIds(uri)
        
        alt Phase 1: inverseSources works
            Note over SVC: returns exact targets for this file
        else Phase 2: sourceRoot prefix match
            Note over SVC: matches by directory prefix
        else Phase 3: fallback all targets
            Note over SVC: compiles everything in this connection
        end

        SVC->>BSP: buildTargetCompile(targetIds)
        BSP-->>SVC: CompileResult
        
        alt Compilation OK
            SVC->>MGR: onCompileSuccess → refreshNavigationIndex
        else Compilation errors
            BSP->>SVC: onBuildPublishDiagnostics
            SVC->>ED: publishDiagnostics(uri, diagnostics)
        end
    end
```

## Key Points

- **Compile-on-save only** — `DidChange` (keystroke) is a no-op. Debounce pipeline deferred.
- **Target selection** tries 3 strategies in order: inverseSources BSP call, directory prefix match, fallback to all connection targets.
- **After successful compile** — `onCompileSuccess` refreshes the SemanticDB navigation index so go-to-definition gets the latest symbols.
- **Diagnostics** accumulate per-file, per-target. Full union published to editor on each BSP notification.
