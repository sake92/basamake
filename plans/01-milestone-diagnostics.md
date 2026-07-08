# Milestone 1 — BSP diagnostics + connection lifecycle

**Goal:** editor shows compile errors/warnings as squiggles, sourced from a BSP build server.
This is the "proxy" — but the real work (and the differentiator) is a rock-solid connection
lifecycle. Read `00-overview.md` first.

**Prereq for the agent:** design this together with `02-milestone-multibsp.md`. Everything
per-connection here must be an *instance* coordinated by a manager, never a global singleton,
or M2 forces a rewrite.

## Deliverables

- An lsp4j `LanguageServer` the editor connects to over stdio.
- A bsp4j `BuildClient` that connects to one build server, does the handshake, triggers
  compiles, and forwards diagnostics.
- A connection state machine with distinct crash-reconnect and config-reload paths.
- Debounced compilation with supersede-on-new-edit.

## LSP side (lsp4j)

- Implement `initialize`, `textDocument/didOpen`, `didChange`, `didSave`, `didClose`,
  `shutdown`, `exit`.
- Advertise `TextDocumentSyncKind.Full` so we always know buffer contents.
- Diagnostics are *pushed* (`client.publishDiagnostics`), never requested.
- Every handler: drop a message on the owning connection's queue, return a completed future.
  No work on the lsp4j thread.

## BSP side (bsp4j)

Handshake sequence (all blocking `.get()` on a virtual thread — straight-line):

1. Read `.bsp/<name>.json`, spawn its `argv` process (working dir = workspace root),
   wire stdio to a bsp4j `Launcher`.
2. `buildInitialize(...).get()` → `onBuildInitialized()`.
3. `workspaceBuildTargets().get()` to learn compile targets.
4. `buildTargetSources(...).get()` to learn which files each target owns (feeds M2 routing).

Only implement the `BuildClient` callbacks that matter; everything else is a no-op. The one
that matters is `onBuildPublishDiagnostics`.

## Diagnostics forwarding — the correctness core

- BSP `Diagnostic` is byte-for-byte the LSP `Diagnostic`; mapping is near-1:1 (range,
  severity ints line up 1..4, message, source).
- **Reset semantics — the #1 source of ghost errors.** BSP streams diagnostics with a
  `reset` flag per (textDocument, buildTarget):
  - `reset = true`  → replace that file's accumulated list.
  - `reset = false` → append to it (build tools stream incrementally).
  - empty array + `reset = true` → clear the file.
  LSP has no append concept, so maintain `Map[DocumentUri, List[Diagnostic]]` per target and
  **re-publish the full accumulated list** for a file every time it changes.

## Compile triggering + debounce (ox)

- `didChange` fires per keystroke; do not compile per keystroke.
- Model as an ox channel/flow: edits push dirty target-ids → debounce (300–500ms of quiet) →
  compile fork fires `buildTargetCompile`. The debounce operator collapses bursts.
- Supersede, don't queue: if a new edit arrives while a compile is in flight, cancel the
  stale compile fork (see cancellation caveat below) and fire a fresh one. Debounce +
  supersede become one pipeline, not two interacting piles of mutable state.
- Compile-on-save is the acceptable MVP fallback; debounced-on-change is the better target.

## Connection state machine

States are named by **what forks are alive in the connection's ox scope** — a state *is* its
scope contents. Transitioning out = cancel the scope (forks unwind); transitioning in = open
a new scope.

| State | Live forks in scope | Notes |
|-------|--------------------|-------|
| `Idle` | none | scope exists, empty |
| `Spawning` | process + **reader fork** | reader fork is long-lived; survives into Connected |
| `Handshaking` | reader fork + handshake fork | blocking init/targets/sources sequence |
| `Connected` | reader fork + **debounce fork** + transient **compile forks** | steady state |
| `Backoff wait` | sleep timer only | scope torn down; failure-driven |
| `Reloading` | none (teardown) | user-driven; immediate |
| `Failed` | none | terminal until user intervention; status surfaced |

Transitions:

- `Idle → Spawning` : spawn.
- `Spawning → Handshaking` : stream up.
- `Handshaking → Connected` : handshake ok.
- `Handshaking → Backoff wait` : handshake throws/times out.
- `Connected → Backoff wait` : **crash/EOF** (see liveness below).
- `Backoff wait → Spawning` : retry, attempt n < max.
- `Backoff wait → Failed` : attempt n == max.
- `Connected → Reloading` : `.json` content changed (signal from manager's watcher).
- `Reloading → Idle → Spawning` : respawn immediately with the *new* spec.

### Liveness detection

Do not poll. The **reader fork** blocks on the JSON-RPC input stream; when the process dies,
its read returns EOF / throws. That event *is* the liveness signal and fires the crash
transition. The connection's ox supervisor listens for two failure kinds: its own forks
throwing (→ Backoff) vs. an external scope-cancel from the manager (→ Reloading/teardown).

### Crash vs. reload — keep rigorously distinct

They look alike ("connection gone, make a new one") but differ in trigger, source of truth,
and policy:

- **Crash** — failure-driven, from *inside* (reader fork EOF). Exponential backoff with a cap
  (e.g. 1s, 2s, 4s … capped at 30s), give up at max → Failed.
- **Reload** — user-driven, from *outside* (watcher, `.json` rewritten). Immediate,
  unconditional, no backoff.

They share the spawn+handshake tail but never the trigger or retry policy. Debounce the file
watcher too — build tools truncate-then-write, so a single logical change can fire multiple
watch events; don't reconnect three times.

### Two pieces of state that MUST outlive the scope

Both live in the manager's durable per-connection record, NOT in the connection scope (which
is destroyed on every crash):

1. **Attempt counter.** If it lives in the scope it resets to 0 every reconnect → never
   reaches max → infinite hot-loop (the exact Metals-beating bug). Increment on failure,
   compare to max in Backoff, **reset to 0 only on successful entry to Connected**.
2. **Last-known diagnostics** (`Map[Uri, List[Diagnostic]]`). Must survive Backoff/Reloading
   so squiggles don't flicker off during a 1–4s transient reconnect. Cleared only on **clean
   detach** (`.json` deleted → connection removed).

## Cancellation caveat (ox + BSP)

ox cancellation rides on interruption. Interrupting a VT blocked on a bsp4j `CompletableFuture`
throws `InterruptedException` and unwinds *us* — but the build server may still be churning on
that compile. For true cancellation, also send the protocol-level cancel (`$/cancelRequest` /
BSP cancellation) AND unwind locally. ox handles the local half; wire the wire-level half
yourself. Don't let clean local cancellation fool you into thinking the remote work stopped.

## Definition of done

- Errors appear/clear correctly on save, including reset semantics (no ghost errors).
- Killing the build-server process mid-session → automatic reconnect, squiggles preserved
  through the gap, no flicker.
- Repeatedly-crashing server → backs off, then surfaces a clear "keeps crashing" status and
  stops (no hot-loop).
- Rewriting `.bsp/<name>.json` → immediate clean reconnect with the new spec.
