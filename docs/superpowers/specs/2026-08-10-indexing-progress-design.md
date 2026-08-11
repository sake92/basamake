# Indexing progress in VS Code + dependency priority scheduling

Date: 2026-08-10
Status: Approved

## Problem

Indexing work happens with zero user-visible feedback (only file logs under
`.basamake/logs/`). On a cold cache the server silently spends minutes on the
JDK `src.zip` and dozens of dependency source jars, and the user cannot tell
whether the server is alive, what it is doing, or when it will be done.

Additionally, index order is unprioritized: the JDK and the most-used
`org.scala-lang` jars (scala-library, scala3-library, ...) race with arbitrary
dep jars for the two index worker permits.

## Decisions (agreed with user)

1. **Per-domain progress items** — three stacked status-bar items, one per
   indexing domain: workspace files, dependency jars, JDK src.zip.
2. **Async startup** — `initialize()` returns early; workspace indexing moves
   to a background thread (required for workspace-phase progress, since
   vscode-languageclient only registers the `window/workDoneProgress/create`
   handler after the initialize handshake completes).
3. **Indexing only** — BSP compile progress (taskStart/taskProgress/taskFinish)
   stays as logMessage; not in scope.
4. **Priority, concurrent** — JDK always starts first (priority 0); scala-lang
   jars next (priority 1); the rest last (priority 2). Two workers still run
   concurrently; no strict serialization.

## Architecture

### Navigation module stays LSP-free

New file `modules/navigation/src/.../navigation/indexing/IndexingProgressListener.scala`:

```scala
enum IndexingPhase: case Workspace, Dependencies, Jdk

trait IndexingProgressListener:
  def onProgress(phase: IndexingPhase, done: Long, total: Long, message: String): Unit

object IndexingProgressListener:
  val noop: IndexingProgressListener = (_, _, _, _) => ()
```

- `WorkspaceIndex` gains constructor param
  `progressListener: IndexingProgressListener = IndexingProgressListener.noop`.
- `IndexedSymbolTable` gains the same constructor param (default no-op).
- `SourceJarIndexer.index(source, fingerprint, progress)` gains a per-entry
  callback `progress: (done: Long, total: Long, unitName: String) => Unit`
  with a no-op default. Phase-agnostic — callers route by phase.

### Main module: `IndexingProgressReporter` (new file in `lsp` package)

Implements `IndexingProgressListener` over LSP `workDoneProgress`:

- Per-phase state: token string (`"basamake-workspace"`, `"basamake-deps"`,
  `"basamake-jdk"`), title (`"Indexing workspace"`, `"Indexing dependencies"`,
  `"Indexing JDK sources"`), last-send timestamp, active flag.
- `begin` on first event: `client.createProgress(WorkDoneProgressCreateParams(token))`
  then `client.notifyProgress(begin)`. Begin message carries the done count
  (`"$done/$total $message"` — correct when a phase restarts with done > 0).
- `report`: throttled — at most one notification per phase per 100ms; the
  first event and the final `done == total` event are always sent. Percentage
  = `done * 100 / total`.
- `end` when `done == total`: always sent; phase state reset.
- **Defensive + handshake-safe**: `createProgress`/`notifyProgress` wrapped in
  try/catch — lsp4j's default interface implementations throw
  `UnsupportedOperationException` (also protects test fakes that don't override
  them). A rejected `createProgress` (e.g. MethodNotFound while the client is
  still initializing — vscode-languageclient registers
  `window/workDoneProgress/create` only AFTER the initialize handshake) is
  TRANSIENT: the begin is retried after a per-phase cooldown (default 5s;
  `setBeginRetryMillis` test seam) instead of permanently disabling the
  reporter — a permanent disable would kill progress on cold caches, exactly
  when it matters most. Only a broken transport (`notifyProgress` throwing)
  disables the reporter. Nothing progress-related runs on the initialize
  thread (the JDK enqueue runs on its own background thread; the workspace
  indexing on another), so the handshake is never stalled by the bounded
  5s `createProgress` wait.
- Client late-binding: the reporter is constructed at server construction with
  `@volatile var client: LanguageClient = null` (set in `connect()`) and
  `@volatile var enabled = false` (set in `initialize()` from
  `params.getCapabilities.getWindow.isWorkDoneProgress`, null-guarded).
  Events before enable are dropped — nothing indexes before `initialize`.

VS Code renders `$/progress` workDone items in the status bar natively — **no
extension changes** (`../basamake-vscode` untouched).

## Priority scheduler in `IndexedSymbolTable`

Replaces the `indexLimiter` semaphore + fire-and-forget virtual threads:

- `PriorityBlockingQueue[(priority: Int, seq: Long, fp: String, src: os.Path)]`
  + 2 worker virtual threads (worker count injectable for tests) pulling jobs
  in `(priority, seq)` order. The semaphore goes away — the workers *are* the
  concurrency bound of 2.
- Priorities: JDK = 0, scala-lang = 1, everything else = 2.
  - Scala-lang detection: `fp.startsWith("org_scala-lang/")` (fingerprint
    embeds the maven group id) OR flat-name fallbacks (`scala-library`,
    `scala3-library`, `scala-reflect`, `scala3-compiler`, `scala3-interfaces`).
- JDK is enqueued during `initialize()` at priority 0 → always starts before
  any dep jar (fixes the current race where a dep jar can grab the first
  permit).
- The `indexing` fingerprint set stays as the single-flight dedupe (add on
  enqueue, remove on completion). `jdkIndexing` AtomicBoolean is subsumed by
  the unified queue. `handleCorrupt` reindexes enqueue at their normal
  priority. `ensureIndexed` / `ensureJdkIndexed` / lookup-miss queue all
  become enqueues.
- **Progress counting**: the scheduler owns exact
  `total = queued + active + done` and `done` counters for the Dependencies
  phase — precise even when new targets register mid-run (total grows,
  percentage recalculates).

## Progress reporting points

| Phase | Emitter | Count semantics | Message |
|---|---|---|---|
| Workspace | `WorkspaceIndex.initialize` | total = scala+java files; Pass A (semanticdb pairing) bumps done by paired count; Pass B bumps per extracted file | file name |
| Dependencies | `IndexedSymbolTable` scheduler | done/total jars (percentage = jar-level) | current jar name + its internal entry % |
| JDK | `SourceJarIndexer` via callback | done/total source entries (percentage = entry-level) | `src.zip NN%` |

`SourceJarIndexer.index` pre-counts source entries with one extra zip
iteration before parsing (cheap vs. parsing; honest N) and reports per source
entry; the reporter throttles the JDK's ~570k events down to ~10/s.

## Async startup (initialize)

`initialize()`:

1. Build capabilities (unchanged).
2. Read `window.workDoneProgress` capability → `reporter.enable(...)`.
3. Launch background thread: `workspaceIndex.initialize(roots)` (try/catch as
   today) — emits Workspace-phase events via the listener.
4. Launch background thread: `depsSymbolTable.ensureJdkIndexed()` — must NOT
   run on the initialize thread: when the JDK is uncached its enqueue fires the
   first Jdk-phase event synchronously, and a `createProgress` before the
   handshake would be rejected (and could stall initialize up to 5s).
5. `bspManager.initialize(...)` — unchanged (fast).
6. Return `InitializeResult` immediately.

Small safety fix in `WorkspaceIndex.initialize`: after `sourcesMap.clear()`,
re-put `openFiles` entries so a file opened mid-walk is not dropped. Because
indexing is now async, a file opened during it can also have its buffer state
(occurrences/locals) wiped by the map re-seed, and its semanticdb pairing
lands after `didOpen` — so at the END of `initialize`, `refreshOpenBuffer` is
re-run for every open file (restores occurrences and prefers semanticdb
occurrences now that pairing is done).

`WorkspaceIndex.invalidate` (post-compile re-index) emits no progress — it is
fast, and flashing an item on every save would be noise.

## Testing

Navigation (`modules-navigation-test`):

- Recording listener + `WorkspaceIndex` fixture → sequence `(0, N)` →
  per-file increments → `(N, N)` with correct total; semanticdb pairing bumps
  done by paired count.
- `IndexedSymbolTable` with tmp cache root (`TestCacheRoot`) + two tiny fake
  source jars → jar-level done/total events; with worker count 1 → start order
  is JDK → scala-lang → normal.
- `priorityOf` unit test: `org_scala-lang/...` → 1, flat `scala3-library` → 1,
  others → 2.
- `SourceJarIndexer` entry-count callback: total = source entries, not
  `zip.size()` (dirs/non-source excluded).

Main (`modules-main-test`):

- `IndexingProgressReporter` unit tests with a fake client: begin/report/end
  sequence; throttle (50 rapid events → ≤ ~10 notifications, final end sent);
  capability-off → zero calls (fake whose `notifyProgress` throws proves it);
  createProgress failure → reporter disabled, no throw.
- Server-level: extend the capturing fake client to record
  `createProgress` + `notifyProgress`; small fixture → workspace token gets
  begin/end with total == fixture file count; fixture without JDK sources
  asserts no Jdk events.
- Existing `BasamakeLanguageServerTest` definition tests become racy with
  async indexing → add a retry-poll helper (call `definition` until non-empty,
  ~10s timeout) after `initialize`.
- `LspTransportTest` fake client needs no change: no `window.workDoneProgress`
  capability is sent, so the reporter stays disabled (and the reporter is
  try/catch-defensive anyway).

## Out of scope

- BSP compile progress ("Compiling" item from taskStart/taskProgress/taskFinish)
- Progress for `WorkspaceIndex.invalidate`
- Cancellable progress / cancel support
- `window.showMessage` fallbacks for unsupported clients
