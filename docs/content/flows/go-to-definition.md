---
title: "Flow: Go-to-Definition"
description: How go-to-definition resolves symbol locations using SemanticDB index
---

# What Happens on Go-to-Definition

Trigger: user requests definition (F12) or hover reference in the editor.

```diagram:mermaid
sequenceDiagram
    participant ED as Editor
    participant LSP as BasamakeLanguageServer
    participant MGR as BuildServerManager
    participant RTR as BspRouter
    participant NAV as NavigationIndex

    ED->>LSP: definition(uri, position)
    LSP->>MGR: definition(uri, position)
    
    MGR->>RTR: route(uri)
    RTR-->>MGR: connId (or None)
    
    alt No BSP connection for URI
        MGR-->>LSP: empty list
        LSP-->>ED: empty list
    else Connection found
        MGR->>NAV: definition(uri, position)
        
        Note over NAV: 1. slicesForUri(uri) → find source file slices
        Note over NAV: 2. symbolAt(position): smallest enclosing occurrence wins
        Note over NAV: 3. candidateSymbolKeys(symbol) → suffix variants
        Note over NAV: 4. firstDefinition: workspace slices first, deps second
        Note over NAV: 5. post-process locations (normalize, dedup, check exist)
        
        NAV-->>MGR: List[Location]
        MGR-->>LSP: List[Location]
        LSP-->>ED: List[Location] or LocationLink
    end
```

## Lookup Algorithm Detail

```diagram:mermaid
flowchart TD
    POS["position (line, char)"] --> OCCUR["slicesForUri(uri)<br/>find owned SemanticdbFileSlice"]
    OCCUR --> SYM["symbolAt(position)<br/>range containment check"]
    SYM --> KEYS["candidateSymbolKeys(symbol)<br/>suffix expansion"]
    KEYS --> WDEF["Search workspace slices<br/>for first definition"]
    WDEF -->|found| DONE["return Location"]
    WDEF -->|not found| DDEF["Search dependency slices<br/>for first definition"]
    DDEF -->|found| DONE
    DDEF -->|not found| EMPTY["return None"]
```

`candidateSymbolKeys` handles symbol mismatches between source and dependency.
SemanticDB occurrence symbols carry markers (`()` / `.` / `#`); dependency index keys
don't. Suffixes require at least 2 segments (owner + name) — bare names are excluded to
prevent cross-library collisions:

```
Input: "com/example/Foo.bar()."
Strip markers: "com/example/Foo.bar"
After last '/': "Foo.bar"
Segments: ["Foo", "bar"]
Suffixes: ["Foo.bar"]   // bare "bar" excluded (single segment)
```

## Key Points

- **Workspace-first** — definitions in your own source code take priority over dependency symbols.
- **Synchronized** — `NavigationIndex.definition()` is `synchronized`, so navigation queries don't race with index refreshes from compile.
- **Post-processing** — locations are normalized (URI canonicalization), deduplicated, and filtered for existence on disk (source files or archive entries).
