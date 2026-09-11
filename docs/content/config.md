---
title: Configuration
description: The optional .basamake/config.json file — BSP connection overrides and ignore patterns
---

# Configuration

Basamake works with zero configuration. If you need to tweak something, create
`.basamake/config.json` in the project root:

```json
{
  "bspOverrides": [
    {
      "bspFile": "app/.bsp/sbt.json",
      "enabled": false
    }
  ],
  "ignorePatterns": ["generated/"]
}
```

When Basamake starts, it automatically adds an enabled override for every
discovered `.bsp/*.json`, using the standard timeouts (`600` seconds for
compile and `120` seconds for handshake). Existing overrides are preserved.
New BSP files discovered later are added automatically as well.

## bspOverrides

Per-connection overrides, matched by the `.bsp/*.json` path relative to the workspace root:

- `enabled: false` — disable a connection entirely (stops its process, clears its diagnostics, removes routing); `true` re-enables it (still lazily started)
- `compileTimeoutSec` — compile timeout per connection, default `600` (10 minutes)
- `handshakeTimeoutSec` — startup/handshake timeout, default `300`

Overrides apply at startup and reactively when `.bsp/*.json` files change on disk.

## ignorePatterns

Extra ignore patterns in gitignore syntax, relative to the project root.
They are merged *after* `.gitignore` rules, so they can add or negate entries
(e.g. un-ignore a folder that `.gitignore` skips).

## Logs

- Runtime logs: `.basamake/logs/basamake.log`
- Internal state: `.basamake/data.json`, `.basamake/status.json` (generated, don't edit)
