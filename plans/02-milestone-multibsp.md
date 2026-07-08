# Milestone 2 — Multi-BSP manager, routing & watching

**Goal:** support multiple build servers in one workspace (e.g. root `build.sbt` via sbt-BSP
*and* `examples/myscript.scala` via Scala CLI), each an independent connection, with files
routed to the server that owns them. Design this alongside M1. Read `00-overview.md` first.

**Why first-class:** Metals picks *one* build server per workspace and everything else is
second-class — a long-standing pain point. Proper multi-BSP is a real differentiator.
Migration scenarios (sbt → mill, with both `.bsp/*.json` present) are exactly the case Metals
handles worst.

## Deliverables

- `BuildServerManager` owning N connections, the routing table, the durable per-connection
  records, and the file watcher.
- Discovery (explicit config list OR autodiscovery of `.bsp/*.json`).
- Routing table `Map[DocumentUri, BuildServerId]`.
- Live attach / detach / reload driven by the watcher.

## The durable per-connection record

Owned by the manager, survives crashes/reloads (see M1). Contains:

- `spec` — path to `.bsp/*.json` + resolved settings (debounceMs, argv override, etc.).
- `attemptCounter` — for backoff (M1).
- `lastKnownDiagnostics: Map[Uri, List[Diagnostic]]` — for flicker-free reconnect (M1).
- `currentState` — the M1 state machine's current state.
- The connection's SemanticDB index slice reference (M3).

The connection's **ephemeral ox scope** (process, forks, PC instance) is separate and gets
destroyed/recreated on transitions. Manager owns durable; scope owns ephemeral.

## Discovery: two sources, one internal type

Both sources produce the same internal `ConnectionSpec(path, debounceMs, argvOverride, ...)`
with defaults filled in. The rest of the system never cares which source it came from.

- **Explicit config list** present → use exactly those. Autodiscovery OFF entirely (strict
  either/or for v1 — simpler precedence, fewer "why did it connect to that" bugs). A new
  `.bsp/*.json` appearing is ignored: the user declared the complete set. This is the
  "mid-migration, don't auto-attach to old sbt" guarantee.
- **No explicit list** → autodiscover all `.bsp/*.json` in the workspace (including nested
  ones — Scala CLI's often live in subdirs like `examples/.bsp/`).
- The **build-tool allowlist** (`enabledBuildTools: [mill]`) is the user-facing control for
  the routing tiebreak. During sbt→mill migration, both `.bsp/sbt.json` and `.bsp/mill.json`
  exist and claim overlapping files; the allowlist makes discovery skip sbt entirely, so the
  overlap problem evaporates declaratively. Treat it as core, not cosmetic.
- (Defer) hybrid mode: explicit entries pin/override, autodiscovery fills the rest.
  Reintroduces overlap ambiguity — add only if actually wanted.

## Routing

- For each server: `workspaceBuildTargets` + `buildTarget/sources` → which files/dirs it owns.
- Prefer `buildTarget/inverseSources` when the server supports it — it's the BSP method
  purpose-built for "which target owns this file."
- Build `Map[DocumentUri, BuildServerId]`. Every LSP request routes by URI: diagnostics for
  `examples/myscript.scala` come from the Scala CLI connection; `src/main/scala/**` from sbt.
- **Tiebreak** for a file claimed by two servers (or none): most-specific-path-wins is a sane
  default — the build rooted at `examples/` beats the build rooted at `/`. Make it
  deterministic. The allowlist is the manual override.
- Index (M3) is partitioned per server too — separate classpaths, class dirs, index slices;
  unioned only at query time.

## File watcher (os-lib)

Watch behavior differs by discovery mode:

- **Explicit list** → watch exactly those files for *content* changes only. Membership is
  fixed.
- **Autodiscover** → watch the `.bsp` *directories* for membership changes (attach/detach)
  AND the files for content changes (reload).

Four distinct events — they are NOT symmetric:

1. **New `.json` appears** (autodiscover only) → synthesize spec, create durable record,
   spawn connection, handshake, add to routing.
2. **`.json` content changes** → `argv`/version changed. **Unload-then-reload**, not mutate
   in place (build tools rewrite these on install/setup/version bump). Route into the M1
   `Connected → Reloading` transition of the owning connection. Debounce (truncate-then-write
   fires multiple events).
3. **`.json` disappears** → **clean detach**: unload connection, publish empty diagnostics for
   all its files (squiggles vanish), drop from routing, discard durable record.
4. **Process dies, `.json` still present** → this is M1 *crash/reconnect*, NOT a config event.
   Do not conflate with (2). Same spec, backoff policy applies.

The watcher fork lives in the **manager**, not in any connection scope. It feeds events into
the right connection's supervisor: (2) as an external scope-cancel → Reloading; (1)/(3) as
manager-level create/destroy.

## Definition of done

- sbt root + Scala CLI subdir project: both connect, each file's diagnostics come from the
  correct server.
- Deleting one `.bsp/*.json` cleanly detaches only that server; its squiggles clear, the
  other server unaffected.
- `enabledBuildTools` allowlist correctly suppresses a present-but-unwanted server.
- Overlapping claims resolve deterministically via most-specific-path.
