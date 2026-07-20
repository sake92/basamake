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
- Disable a connection entirely (`enabled: false`)
- Override per-connection compile debounce (`debounceMs`)

Overrides apply at `initialize()` time and during `reloadConnection` when `.json` files change.
