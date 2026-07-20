---
title: Config Overrides
description: Per-BSP connection configuration via .basamake/config.json
---

# Config Overrides

**File:** `BasamakeConfig.scala`

Users control BSP connections via `.basamake/config.json`:

```json
{
  "bspOverrides": [
    {
      "bspFile": "hello/sbt/.bsp/sbt.json",
      "enabled": true,
      "debounceMs": 1000
    },
    {
      "bspFile": "hello/scalacli/.bsp/scala-cli.json",
      "enabled": false
    }
  ]
}
```

Each override matches a `.bsp/*.json` file by its path **relative to workspace root**. An override can:
- Disable a connection entirely (`enabled: false`) — **auto-shuts down the BSP process** if already running, clears diagnostics, removes routing.
- Enable a connection (`enabled: true`) — **auto-attaches the connection** (lazy: BSP process spawns on first LSP message).
- Override per-connection compile debounce (`debounceMs`)

These behaviors apply at `initialize()` time and reactively when `.json` files change on disk (see [file watching](file-watching-topology.html)).
