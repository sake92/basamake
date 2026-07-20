---
title: "Flow: Workspace Initialization"
description: Complete sequence from LSP initialize to BSP discovery, connection setup, and file watcher start
---

# What Happens on Workspace Initialization

Trigger: VS Code opens the project, LSP `initialize` + `initialized` messages arrive.

```diagram:mermaid
sequenceDiagram
    participant ED as Editor / VS Code
    participant LSP as BasamakeLanguageServer
    participant MGR as BuildServerManager
    participant CFG as BasamakeConfig
    participant WAT as FileChangeWatcher
    participant RTR as BspRouter

    ED->>LSP: initialize(rootUri, capabilities)
    LSP->>LSP: extract workspaceRoot from rootUri
    LSP->>LSP: configure file logging to .basamake/logs/
    LSP-->>ED: InitializeResult(capabilities)
    Note over LSP: Full text sync, definition, references, documentSymbol

    ED->>LSP: initialized()
    LSP->>MGR: initialize(workspaceRoot, lspClient, config)

    MGR->>CFG: load(workspaceRoot)
    CFG-->>MGR: BasamakeConfig (bspOverrides list)

    MGR->>MGR: BspDiscovery.discover(workspaceRoot)
    Note over MGR: walks workspace, finds .bsp/*.json files

    loop For each discovered .bsp JSON
        MGR->>MGR: applyOverrides(spec)
        alt Override disables
            Note over MGR: skip this BSP
        else Enabled
            MGR->>MGR: attachConnection(spec)
            Note over MGR: Creates DurableRecord, BlockingQueue
            Note over MGR: Spawns supervisor virtual thread (blocks on queue.take())
            MGR->>RTR: registerBspRoot(bspDir, Set(connId))
            Note over MGR: No process started yet (Idle state)
        end
    end

    MGR->>WAT: start()
    Note over WAT: os-lib watch daemon thread starts

    Note over LSP, WAT: Initialization complete
    Note over LSP: Now accepting didOpen/didSave/definition requests
```

## What Gets Created at Init Time

| Object | Quantity | Notes |
|--------|----------|-------|
| `DurableRecord` | 1 per `.bsp/*.json` | attemptCounter=0, state=Idle |
| `BlockingQueue` | 1 per `.bsp/*.json` | empty, waiting for messages |
| Supervisor VT | 1 per `.bsp/*.json` | blocked on `queue.take()` |
| `RoutingTable` entry | 1 per `.bsp/*.json` | fallback only (no ground truth yet) |
| `bootstrapCache` | empty | populated on first `route()` call |
| `FileChangeWatcher` | 1 | os-lib daemon thread |
| Debounce timer | 1 `java.util.Timer` | for batching BSP change events |

## What Does NOT Happen at Init Time

- No BSP processes spawned
- No compile triggered
- No navigation index built
- No routing ground truth registered (source dirs unknown before handshake)

## Key Points

- **Zero BSP processes at startup** — all connections start in `Idle`. The first `didOpen`/`didSave` for a file in each BSP's territory triggers the process spawn.
- **Overrides applied before any process starts** — disabled connections never create a supervisor VT.
- **File watcher started after all connections attached** — prevents racing between watcher-detected changes and initial setup.
