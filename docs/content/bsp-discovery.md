---
title: BSP Discovery
description: How Basamake discovers BSP build server configurations scanning for .bsp directories
---

# BSP Discovery

**File:** `BspDiscovery.scala`

Scans workspace recursively (max depth 10) for `.bsp/` directories. Every `*.json` inside is parsed.

```diagram:mermaid
flowchart TD
    A["discover(workspaceRoot)"] --> B["os.walk(root, maxDepth=10)"]
    B --> C{Found .bsp/ dir?}
    C -->|Yes| D[os.list each .bsp/]
    D --> E[Filter *.json files]
    E --> F[Parse each JSON with tupson]
    F --> G{Has argv?}
    G -->|Yes| H["BspConnectionSpec: name, argv, path, debounceMs"]
    G -->|No| I[Warn + skip]
    C -->|No| J[Warn: no BSP configs found]
```

Each `.bsp/*.json` yields a `BspConnectionSpec`:

```scala
final case class BspConnectionSpec(
    content: BspDiscoveryFile,  // name, argv from JSON
    path: os.Path,              // .bsp/foo.json path
    debounceMs: Long = 500      // compile debounce
) {
  val workingDir: os.Path = path / os.up / os.up  // two levels up from .bsp/foo.json
}
```

**Working directory** is always two levels up from the `.json` file (`.bsp/` → parent). Matches BSP convention where `.bsp/sbt.json` lives inside a project subdirectory, build server runs from project root.
