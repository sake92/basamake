---
title: Two-Phase URI Routing
description: How document URIs are routed to the correct BSP connection using RoutingTable and bootstrap heuristic
---

# Two-Phase URI Routing

**Files:** `BspRouter.scala`, `RoutingTable.scala`

Every LSP method call needs to find the owning BSP connection for a given source URI. Two layers:

```diagram:mermaid
flowchart TD
    URI["uri: file:///workspace/foo/src/Main.scala"] --> ROUTE["router.route(uri)"]
    
    ROUTE --> L1["Phase 1: RoutingTable<br/>(longest-prefix via source dirs)"]
    
    L1 --> L1A["reverseLookupCandidates(uri)"]
    L1A --> L1B{Matches found?}
    L1B -->|"Exactly 1"| DONE1["Return connId"]
    L1B -->|"Multiple (tie)"| TIE["tieBreakByNearestBspRoot"]
    L1B -->|"0"| L2["Phase 2: Bootstrap Heuristic"]
    
    TIE --> TIE_RES{"Nearest .bsp/ root<br/>resolves tie?"}
    TIE_RES -->|Yes| DONE2["Return connId"]
    TIE_RES -->|No| ALPHA["Return alphabetically<br/>first connId"]
    
    L2 --> L2A["Walk up directory tree"]
    L2A --> L2B{Each ancestor has .bsp/?}
    L2B -->|"Found"| L2C["Cache result<br/>per directory"]
    L2C --> DONE3["Return connId"]
    L2B -->|"Not found"| L2D["Cache as None,<br/>return None"]
```

## Phase 1: RoutingTable (Ground Truth)

Each connection registers its source directories (from `buildTarget/sources` BSP response) via `registerGroundTruth`. The `RoutingTable` does:

```scala
// For each URI, find all matching (dirLength, connId) pairs
// Return connIds with the longest matching prefix
def reverseLookupCandidates(uri: String): List[BspConnectionId] = synchronized {
    val matches = entries.toList.flatMap { (connId, dirs) =>
      dirs.collect { case dir if uri.startsWith(dir) => (dir.length, connId) }
    }
    matches.maxByOption(_._1) match
      case Some((bestLen, _)) =>
        matches.collect { case (`bestLen`, connId) => connId }.distinct
      case None => Nil
}
```

Example: connection A owns `file:///workspace/foo/`, connection B owns `file:///workspace/foo/bar/`. URI `file:///workspace/foo/bar/baz.scala` routes to B (longer prefix wins).

## Phase 2: Bootstrap Heuristic (Fallback)

When no source directories match, `BspRouter` walks up from the file's parent directory, checking each ancestor for a `.bsp/` subdirectory registered via `registerBspRoot`. Results are cached per directory so subsequent lookups in the same tree skip the walk.

## Compile Target Selection

Inside `BspConnectionSupervisor`, `selectCompileTargetIds` determines which build targets to compile. Three strategies in order:

```diagram:mermaid
flowchart TD
    URI["uri"] --> T1["1. buildTargetInverseSources(uri)<br/>(5s timeout)"]
    T1 --> T1R{Success?}
    T1R -->|"Yes, non-empty"| DONE["Return those targets"]
    T1R -->|"No / empty"| T2["2. targetIdsForUri(uri, sourceRoots)<br/>(directory prefix match)"]
    T2 --> T2R{Match found?}
    T2R -->|"Yes"| DONE
    T2R -->|"No"| T3["3. Fallback: all connection targets<br/>(warns)"]
    T3 --> DONE
```
