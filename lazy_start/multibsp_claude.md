# BSP Lifecycle & Multi-Config Discovery — Implementation Plan

Scope: Scala LSP server that must detect and manage multiple BSP (Build Server
Protocol) configs within one workspace, and across multiple workspace folders.
Two subsystems: **(1) connection lifecycle/health**, **(2) BSP discovery /
which-servers-to-start**.

---

## 1. BSP Connection Lifecycle

### 1.1 Problem

Eagerly starting all BSPs once at LSP init is fragile:
- Laptop sleep can leave the BSP process alive but unresponsive (hung), or
  the OS can reap it — neither produces a clean "file changed" event to
  react to.
- "Is the process running" is not a sufficient health check; a hung process
  still passes that check.

### 1.2 State machine (per BSP connection)

```
NotStarted -> Starting -> Ready -> Dead -> Restarting -> Ready
                              ^                    |
                              +--------------------+
```

Transitions:
- `NotStarted -> Starting`: triggered by proactive warm-up or first routed
  request.
- `Starting -> Ready`: BSP responds to `build/initialize` +
  `workspace/buildTargets` successfully.
- `Ready -> Dead`: health check fails (timeout or crash/EOF on process pipe).
- `Dead -> Restarting -> Ready`: lazy restart, triggered by next routed
  request or next proactive warm-up tick.

Implement as an explicit enum + per-connection struct, not implicit boolean
flags. Each BSP connection owns: process handle, last-successful-response
timestamp, pending-request queue (for requests arriving during
Starting/Restarting), and target-ownership cache (see §2.3).

### 1.3 Triggers

**Proactive (warm-up, hides latency, doesn't guarantee correctness):**
- LSP server init.
- File save.
- File open / touch.
- Editor regains focus / window state change, if the client sends it.

**Reactive (required — this is what actually catches sleep/hang cases):**
- Before routing *any* request to a BSP connection, check
  `now - lastSuccessfulResponse < HEALTH_TTL` (e.g. 30s, make configurable).
- If stale, issue a cheap health probe (`workspace/buildTargets` with a
  short timeout, e.g. 2–3s) before trusting the connection.
- If probe fails: mark `Dead`, lazily restart, queue the original pending
  request behind the restart, then replay it once `Ready`.

**Debounce restarts.** Coalesce rapid-fire triggers (burst of saves,
multiple files touched in one editor action) into a single restart-check per
connection — do not storm-restart per event. A simple per-connection debounce
timer (e.g. 250–500ms) around the "check health" step is enough.

### 1.4 Acceptance criteria

- Simulate: kill BSP process externally → next routed request still
  succeeds (via lazy restart + replay), no user-visible error unless
  restart itself fails.
- Simulate: BSP process alive but not responding to stdin (hang) → health
  probe times out → connection marked `Dead` and restarted.
- Burst of 20 file-save events in 1s → at most 1 restart-check triggered
  per affected connection.

---

## 2. Discovery: Which BSPs to Start

### 2.1 Problem

Target/folder ownership is only knowable by asking the BSP itself
(`workspace/buildTargets`, `buildTarget/sources`), but asking means starting
it — which we want to avoid doing indiscriminately, especially when two BSP
configs exist under the same workspace root pointing at different
subtrees (e.g. `build.sbt` + a `scala-cli` script in a subfolder).

### 2.2 Heuristic: nearest-ancestor `.bsp/` directory

Algorithm, run per file (open/save/edit event):

1. Start at the file's containing directory.
2. Walk upward directory by directory.
3. Stop at the **first** ancestor directory containing a `.bsp/` folder.
4. Start (or verify-running) **all** BSP connection files found in that
   `.bsp/` folder (e.g. `.bsp/sbt.json`, `.bsp/bloop.json`).
5. Do **not** also start BSPs from any `.bsp/` folder further up the tree —
   nearest match wins exclusively, no merging upward.
6. Bound the walk at the workspace root (or the nearest enclosing workspace
   folder in a multi-root setup) — never walk above it onto unrelated
   filesystem ancestors.
7. If no `.bsp/` ancestor is found within the workspace bound: no-op for
   now (see §2.5 for the open UX decision).

This mirrors well-understood ancestor-lookup patterns (`tsconfig.json`,
`.editorconfig`, `.git`) — deterministic, no BSP interrogation required
upfront.

### 2.3 Caching

- Cache is **per-directory**, not per-file: memoize
  `directory -> nearest .bsp/ path (or None)`.
- Only recompute the walk for a directory once; reuse cached result for all
  files within it.
- Invalidate a cached entry when:
  - A `.bsp/` folder is created/deleted/modified within the workspace
    (watch for this).
  - The workspace is reloaded / workspace folders change.
- Separately maintain a **target-ownership cache**: once a BSP actually
  starts and responds to `buildTarget/sources`, record
  `folder/glob -> BSP connection` from ground truth. On next LSP startup,
  consult this cache first, and only proactively start the BSP(s) actually
  implicated by currently-open files — instead of starting everything the
  heuristic would guess.

### 2.4 Self-correction (heuristic vs. ground truth)

The nearest-`.bsp/`-ancestor heuristic assumes source-tree locality maps to
BSP ownership. This breaks for shared/cross-project source directories
(e.g. a `common/` folder referenced by two sibling builds with sources
outside their own tree). Handle this as follows:

- Heuristic decides which BSP(s) to start speculatively (cheap, no BSP
  interrogation needed).
- Once a BSP responds with real `buildTarget/sources` data, treat that as
  ground truth and reconcile against the heuristic's guess.
- If they disagree, update the target-ownership cache (§2.3) so future
  routing for that file/folder uses the corrected mapping, not the
  heuristic.
- Never trust the heuristic blindly for routing once real ownership data is
  available — heuristic is only for the "what to start" bootstrap step.

### 2.5 Open decision — no `.bsp/` ancestor found

Decide and document one of:
- Silent no-op (do nothing until user explicitly imports/configures).
- Notification to the user ("no build server found for this file").
- "Import project" prompt (scan for `build.sbt` / `build.mill` /
  `build.sc` / scala-cli `using` directives and offer to generate a
  `.bsp/*.json`).

Recommendation: start with silent no-op + a status-bar indicator, add the
import prompt later — easier to add UX on top than to remove it once users
depend on it.

### 2.6 True ambiguity (2+ BSPs claiming the same folder)

Only relevant if two `.bsp/*.json` files exist in the *same* resolved
`.bsp/` directory and both claim the same target folder — not the same
problem as different `.bsp/` dirs at different tree depths (§2.2 already
resolves that). For same-directory ambiguity:
- Cheapest: start both candidates once, ask them, cache the result
  (one-time cost, corrected forever after via §2.3 cache).
- Alternative: prompt the user once ("which build server should own this
  project?"), persist the choice in workspace-local config
  (e.g. `.vscode/settings.json` equivalent or your own config file).
- Avoid re-solving this via inference on every startup — persist the
  decision.

### 2.7 Acceptance criteria

- Workspace with `build.sbt` at root + `.bsp/sbt.json` at root, and
  `scripts/foo.scala` + `scripts/.bsp/scalacli.json`: editing `foo.scala`
  starts only the scala-cli BSP, not sbt.
- Editing a file at workspace root starts only the root `.bsp/` BSPs.
- Directory walk is not re-executed on every keystroke — verify via
  logging/counter that it's cached per directory.
- Moving a `.bsp/` folder (delete + recreate elsewhere) correctly
  invalidates stale cache entries.

---

## 3. Suggested build order

1. Per-connection state machine + reactive health check (§1.2–1.3) — this
   alone fixes the sleep/hang problem even with current eager-start-all
   behavior.
2. Nearest-ancestor `.bsp/` walk + per-directory cache (§2.2–2.3), wired to
   the reactive "start on first routed request" path from step 1.
3. Proactive warm-up triggers (§1.3) layered on top once 1–2 are solid.
4. Target-ownership ground-truth reconciliation (§2.4).
5. Ambiguity handling + no-`.bsp/`-found UX (§2.5–2.6) — lowest priority,
   edge cases.

## 4. Explicit non-goals / things not to build

- Do not attempt to merge/union BSPs from multiple `.bsp/` ancestors for a
  single file — nearest wins, exclusively.
- Do not treat "process alive" as a sufficient health signal anywhere.
- Do not re-run the ancestor walk per-file when a per-directory cache hit
  is available.