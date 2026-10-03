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
compile and `300` seconds for handshake). Existing overrides are preserved.
New BSP files discovered later are added automatically as well.

## bspOverrides

Per-connection overrides, matched by the `.bsp/*.json` path relative to the workspace root:

- `enabled: false` — disable a connection entirely (stops its process, clears its diagnostics, removes routing); `true` re-enables it (still lazily started)
- `autoCompile: false` — keep the BSP connection and target metadata, but disable Basamake-triggered compilation on file opens, saves, renames and filesystem changes; default `true`
- `compileTimeoutSec` — compile timeout per connection, default `600` (10 minutes)
- `handshakeTimeoutSec` — startup/handshake timeout, default `300`

`autoCompile` changes in `.basamake/config.json` apply to running connections
without restarting them. Pending builds are discarded when it is disabled;
an already running build may finish. Re-enabling takes effect on the next
normal compile trigger.

An external-build configuration keeps the connection enabled:

```json
{
  "bspOverrides": [
    {
      "bspFile": ".bsp/sbt.json",
      "enabled": true,
      "autoCompile": false
    }
  ]
}
```

The BSP configuration must exist, but no initial Basamake compile is required:
the connection imports target metadata without compiling. Navigation follows
changes in the SemanticDB output directories reported by BSP. External builds
must generate SemanticDB for this refresh to work; after output is cleaned,
Basamake falls back to source parsing until SemanticDB is generated again.

Basamake no longer triggers builds that produce compiler diagnostics in this
mode. Diagnostics emitted by the connected BSP server are still forwarded;
SemanticDB files themselves do not contain diagnostics.

## ignorePatterns

Extra ignore patterns in gitignore syntax, relative to the project root.
They are merged *after* `.gitignore` rules, so they can add or negate entries
(e.g. un-ignore a folder that `.gitignore` skips).

## Logs

- Runtime logs: `.basamake/logs/basamake.log`
- Internal state: `.basamake/data.json`, `.basamake/status.json` (generated, don't edit)
