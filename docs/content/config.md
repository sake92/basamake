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

## bspOverrides

Per-connection overrides, matched by the `.bsp/*.json` path relative to the workspace root:

- `enabled: false` — disable a connection entirely (stops its process, clears its diagnostics, removes routing); `true` re-enables it (still lazily started)
- `compileTimeoutSec` — compile timeout per connection, default `600` (10 minutes)
- `handshakeTimeoutSec` — startup/handshake timeout, default `120`

Overrides apply at startup and reactively when `.bsp/*.json` files change on disk.

## ignorePatterns

Extra ignore patterns in gitignore syntax, relative to the project root.
They are merged *after* `.gitignore` rules, so they can add or negate entries
(e.g. un-ignore a folder that `.gitignore` skips).

## Logs

- Runtime logs: `.basamake/logs/basamake.log`
- Internal state: `.basamake/data.json`, `.basamake/status.json` (generated, don't edit)
