# Config & persistence (JSON)

**Goal:** a config file to control the server, and a minimal JSON cache for build digests.
No embedded SQL DB. Read `00-overview.md` first. Design the config alongside M1/M2 — it drives
discovery and routing.

## Config file

Scope it to **orchestration, not compilation**: which servers, whether on, how to reach them.
Compiler settings come from BSP; config that tries to override them fights the build tool and
loses. Resist letting config metastasize into "reconfigure the compiler."

Fields:

- `enabled: bool` — **hard kill switch, checked at startup before spawning anything.** Not
  "connect then ignore" — don't even start. The escape hatch when a user hits a pathological
  project and needs to disable cleanly without uninstalling.
- `enabledBuildTools: [sbt, scalacli, mill, ...]` — allowlist. This is the user-facing control
  for the routing tiebreak (M2): during migration, both old and new `.bsp/*.json` exist;
  the allowlist makes discovery skip the unwanted one. Core, not cosmetic.
- Explicit connection list (optional) — when present, disables autodiscovery entirely (v1
  strict either/or). Each entry:
  ```jsonc
  [
    { "path": ".bsp/mill.json", "debounceMs": 500 },
    { "path": "examples/.bsp/scalacli.json" }
  ]
  ```
  Per-tool overrides live here (debounceMs, custom argv, non-standard `.bsp` path — Scala
  CLI's nesting occasionally defeats autodiscovery).
- (Absent explicit list) → autodiscover all `.bsp/*.json`.

**Precedence — decide and document up front** (this will be the #1 support question:
"why isn't my server connecting?"):

1. `enabled: false` → nothing runs. Full stop.
2. Explicit connection list present → use exactly it; autodiscovery off.
3. Else autodiscover, filtered by `enabledBuildTools`.

## JSON persistence (replaces Metals' H2 DB)

Metals uses an embedded H2 DB (`.metals/metals.h2.db`, Flyway-migrated) as a *metadata/cache*
store — NOT the code index. It holds: build/import digests (hash of build definition; skip
re-import if unchanged), dismissed prompts / remembered user choices, file-change
digests/timestamps, dependency-source bookkeeping. Its corruption is what causes Metals'
"always asks to re-import" bug — an argument for keeping the persisted surface tiny.

We persist only the "skip expensive work" state, as a plain JSON file (e.g. `.scala-lsp/cache.json`):

- **Build digests** — hash of each `.bsp/*.json` + relevant build files. On startup, compare
  to stored; if unchanged, skip re-handshake/re-import work. If changed, reconnect.
- **User choices** — dismissed prompts, remembered build-tool selection, etc.

Everything else (diagnostics, the SemanticDB index) is recomputed on startup from a fresh
compile, so it stays in memory only. Losing the JSON cache is harmless — worst case a one-time
re-import, same as deleting `.metals/`.

Keep the surface small deliberately: the smaller the persisted state, the fewer
corruption/staleness failure modes. For v1 it's defensible to skip persistence entirely and
always do the cheap handshake on startup.

## Definition of done

- `enabled: false` prevents any process spawn.
- Explicit list disables autodiscovery; per-entry `debounceMs`/overrides apply.
- `enabledBuildTools` filters autodiscovered servers.
- Build-digest JSON lets an unchanged workspace skip re-import; a changed build triggers
  reconnect; deleting the JSON degrades gracefully to a full re-import.
