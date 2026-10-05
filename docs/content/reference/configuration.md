---
title: Configuration
description: Fields and defaults for .basamake/config.json
---

# Configuration

Basamake uses the optional `.basamake/config.json` file in the workspace root.

```json
{
  "bspOverrides": [
    {
      "bspFile": ".bsp/sbt.json",
      "enabled": true,
      "autoCompile": true
    }
  ],
  "ignorePatterns": [],
  "offerInstallBlacklist": []
}
```

## `bspOverrides`

`bspOverrides` contains per-connection settings. Each entry is matched by its
`.bsp/*.json` path relative to the workspace root.

| Field | Default | Description |
|---|---|---|
| `bspFile` | required | Path to the BSP configuration, relative to the workspace root. |
| `enabled` | `true` | Enables the connection. Disabling it stops its process, clears its diagnostics, and removes its routes. |
| `autoCompile` | `true` | Enables Basamake-triggered compilation after file opens, saves, renames, and filesystem changes. |
| `compileTimeoutSec` | `600` | Compile timeout in seconds. |
| `handshakeTimeoutSec` | `300` | Startup and handshake timeout in seconds. |

At startup, Basamake adds an enabled override with default timeouts for every
discovered BSP configuration. Existing overrides are preserved. BSP
configurations discovered later are also added automatically.

### `autoCompile`

`autoCompile` controls whether Basamake triggers BSP compiles after file opens,
saves, renames, and filesystem changes. It defaults to `true`.

Set it to `false` for lightweight day-to-day navigation when an sbt/Basamake
compile loop is getting in the way, such as when an agent changes files and
repeatedly triggers sbt. The BSP connection and target metadata remain active,
but Basamake does not start builds, so it does not interfere with the process
that owns compilation. Pending builds are discarded; a build already in progress
may finish.

With `autoCompile` disabled, the process that owns compilation must generate
SemanticDB for navigation to follow its updates. If SemanticDB output is absent,
navigation falls back to source parsing. Diagnostics published by the BSP server
are still forwarded. Re-enabling takes effect on the next compile trigger.

## Other properties

| Field | Default | Description |
|---|---|---|
| `debugSymbolTableDump` | `false` | Writes the full symbol table to `.basamake/symbol_table.txt`. |
| `debugSlowFallbackMs` | off | Logs source-parser fallbacks slower than this many milliseconds at `DEBUG`. |
| `enableJdkIndexing` | `true` | Enables eager background indexing of JDK sources. |
| `depsCacheRoot` | XDG `~/.cache/basamake/deps` | Overrides the dependency/JDK cache root. Relative paths resolve against the workspace root; absolute paths are used as-is. |
| `offerInstallBlacklist` | `[]` | Build-marker paths, relative to the workspace root, where the BSP-install offer was declined. |

## Automatic BSP installation

When a source file is opened in a Deder, sbt, or Mill project without a BSP
configuration, Basamake offers to run the build tool's BSP install command:
`deder bsp install`, `sbt bspConfig`, or `mill mill.bsp.BSP/install`.
The **Do not offer again** choice adds that build marker to
`offerInstallBlacklist`.

The paths are relative to the workspace root. To re-enable a prompt, remove its
marker from the list:

```json
{
  "offerInstallBlacklist": ["build.sbt", "nested/deder.pkl"]
}
```

## `ignorePatterns`

`ignorePatterns` contains gitignore-style patterns relative to the workspace
root. They are applied after `.gitignore` rules, so entries may add exclusions
or negate existing exclusions.

## Generated files

| Path | Contents |
|---|---|
| `.basamake/logs/basamake.log` | Runtime logs. |
| `.basamake/data.json` | Internal generated state. |
| `.basamake/status.json` | Internal generated status. |

Generated state files are not configuration and should not be edited.
