# Design: `onBuildTargetDidChange` → Reindex + LSP Progress

**Date:** 2026-07-21
**Status:** Approved

## Motivation

When a BSP build target changes (dependency added/removed, target created/deleted), the server sends `buildTargetDidChange` notification. Basamake currently logs and ignores it. This leaves the navigation index stale — new dependency sources aren't indexed, deleted targets leave ghost data.

## Scope

- Re-fetch `buildTargetDependencySources` for changed/created targets
- Update the navigation index via existing nav refresh thread
- Notify the LSP client (IDE) with a `$/progress` notification
- Does NOT trigger a recompile or re-fetch workspace sources or target list

## End-to-End Flow

```
BSP server
  → onBuildTargetDidChange(DidChangeBuildTarget)       [BasamakeBuildClient, BSP reader thread]
    → queue.offer(BuildTargetChanged(params))
      → supervisor dispatch                             [supervisor VT]
        → filter by BuildTargetEventKind:
            CREATED/CHANGED:
              → progress begin
              → buildTargetDependencySources(changedIds)
              → onBuildTargetChanged callback
              → progress end
            DELETED:
              → onBuildTargetChanged callback (no BSP fetch, no progress)
        → BuildServerManager callback:
            → merge dep URIs into ctx.dependencySourceUrisByTarget
            → remove deleted targets from ctx.sourceRootsByTarget,
              ctx.dependencySourceUrisByTarget
            → navRefreshPending.set(allAffected)
            → LockSupport.unpark(navRefreshThread)
              → NavigationIndex.refresh()               [nav refresh VT]
                [existing incremental logic, including stale target pruning]
```

## File Changes

### 1. `ConnectionMessage.scala`

New case:

```scala
final case class BuildTargetChanged(params: ch.epfl.scala.bsp4j.DidChangeBuildTarget)
    extends ConnectionMessage
```

### 2. `BasamakeBuildClient.scala`

Replace `onBuildTargetDidChange` body:

```scala
override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit =
  logger.debug(s"BSP TARGET DID CHANGE: ${params.getChanges.size()} event(s)")
  queue.offer(ConnectionMessage.BuildTargetChanged(params))
```

### 3. `BspConnectionSupervisor.scala`

Three changes:

**a) New callback parameter** on `supervise` and `transitionToRunning`:

```scala
onBuildTargetChanged: (ch.epfl.scala.bsp4j.BuildServer,
  ch.epfl.scala.bsp4j.DependencySourcesResult,
  List[BuildTargetIdentifier],
  List[BuildTargetIdentifier]) => Unit
```

Parameters: `(buildServer, depSourcesResult, changedOrCreatedIds, deletedIds)`.

**b) New dispatch case** in `dispatch()`:

```scala
case ConnectionMessage.BuildTargetChanged(params) =>
  handleBuildTargetChanged(params, buildServer, lspClient, onBuildTargetChanged)
```

**c) New method `handleBuildTargetChanged`:**

- Guard: if `buildServer` is null or connection not Connected, return immediately
- Classify events by `BuildTargetEventKind` (CREATED=1, CHANGED=2, DELETED=3).
  Extract `BuildTargetIdentifier` from `BuildTargetEvent.getTarget`.
- If `changedOrCreated` non-empty:
  - Send `$/progress` begin with message `"Reindexing X target(s)…"`
  - Call `buildServer.buildTargetDependencySources(Params(changedOrCreated.asJava))`
    with timeout from `durable.bspFile.get().compileTimeoutSec`
  - Invoke `onBuildTargetChanged(buildServer, result, changedOrCreated, Nil)`
  - Send `$/progress` end
  - If BSP call fails: send progress end anyway, log error, don't update context,
    don't invoke callback
- If `deleted` non-empty:
  - Invoke `onBuildTargetChanged(buildServer, null, Nil, deleted)`.
    `null` signals "no BSP call was made, no new dependency source data."
  - No progress, no BSP fetch
- If `changes` is empty: no-op

### 4. `BuildServerManager.scala`

New callback implementation:

```scala
val targetChangedCallback = (
    buildServer: bsp4j.BuildServer,
    depSourcesResult: bsp4j.DependencySourcesResult,
    changedOrCreatedIds: List[BuildTargetIdentifier],
    deletedIds: List[BuildTargetIdentifier]) => {
  if depSourcesResult != null then
    ctx.dependencySourceUrisByTarget =
      ctx.dependencySourceUrisByTarget ++ extractTargetDependencySourceUris(depSourcesResult)
  for tid <- deletedIds do
    ctx.sourceRootsByTarget -= tid
    ctx.dependencySourceUrisByTarget -= tid
  val allAffected = changedOrCreatedIds ++ deletedIds
  if allAffected.nonEmpty then
    navRefreshPending.set(allAffected)
    LockSupport.unpark(navRefreshThread)
}
```

Wire into existing `supervise` call with `targetChangedCallback` as additional argument.

### Files NOT changed

- `NavigationIndex.scala` — no changes (incremental refresh + stale target pruning already exists)
- `DependencySourceIndexing.scala` — no changes
- `DependencySliceCache.scala` — no changes
- `BspHandshake.scala` — no changes

## Progress Notification Details

### Token strategy

Fixed token string: `"basamake-reindex"`. Registered once per connection when supervisor enters Connected state. Avoids async `workDoneProgressCreate` round-trip on every target change event.

Two boolean flags per connection (locals in `dispatch` method's `while Connected` loop):

```scala
var progressTokenRegistered = false  // attempted registration
var progressSupported = false        // client confirmed support
```

On first `BuildTargetChanged` message: attempt `lspClient.createProgress(...)`. If future completes OK, both flags true. If fails, `progressTokenRegistered = true, progressSupported = false` (don't retry).

### lsp4j types

```java
// Registration (once)
lspClient.createProgress(new WorkDoneProgressCreateParams(
  new Either3<SymbolInformation, Integer, String>("basamake-reindex")
))

// Begin
lspClient.notifyProgress(new ProgressParams(
  new Either3<SymbolInformation, Integer, String>("basamake-reindex"),
  new Either3<>(new WorkDoneProgressBegin("Reindexing X target(s)…"))
))

// End
lspClient.notifyProgress(new ProgressParams(
  new Either3<SymbolInformation, Integer, String>("basamake-reindex"),
  new Either3<>(new WorkDoneProgressEnd())
))
```

### Message text

`"Reindexing X target(s)…"` where X is the count of changed+created targets. No target IDs/URIs — they are noisy file:// URLs with no value in status bar.

Only sent for changed/created targets. Deleted targets skip progress (fast, no BSP fetch needed).

## Edge Cases

| Scenario | Behavior |
|----------|----------|
| **Deleted targets** | Callback with null depSources, no progress. Nav refresh thread prunes stale targets (existing behavior). |
| **BSP call fails** (timeout/disconnect) | Send progress end, log error, don't update context, don't trigger nav refresh. |
| **Rapid target change events** | Serial dispatch on supervisor VT. Nav refresh thread uses latest-wins with `AtomicReference.getAndSet(null)` — second event overwrites pending before first refresh completes. Nav thread drains in inner loop. |
| **Zero targets in event** | No-op — return immediately. |
| **Progress client rejection** | Set `progressSupported = false`. Skip all future progress for this connection. Still do the reindex work. |
| **Shutdown during reindex** | `handleBuildTargetChanged` runs synchronously to completion within dispatch loop. Shutdown message waits in queue. Next loop iteration processes it normally. Nav refresh thread checks `ctx.shuttingDown` before indexing. |
| **Connection not in Connected state** | Message dropped silently (dispatch only runs in Connected state). If connection later enters Connected, it will get fresh data from handshake. |

## Testing

- **Unit**: `BspConnectionSupervisor.selectCompileTargetIds` already tested. New `handleBuildTargetChanged` method is testable by calling directly with mock params and verifying callback invocation.
- **StateMachineTest**: Existing suite covers backoff state transitions. No new states added — no changes needed.
- **System**: Smoke test with Python script wait for target change event from sbt (modify `build.sbt`). Manual verification:
  1. Change a dependency in `build.sbt`
  2. sbt sends `onBuildTargetDidChange`
  3. Check basamake logs for "BSP TARGET DID CHANGE" and "Reindexing X target(s)"
  4. Verify progress bar appears in VS Code
  5. Verify go-to-definition works for new dependency symbols

## Non-Goals

- Recompile on target change
- Re-fetch workspace target list (`workspaceBuildTargets`)
- Re-fetch workspace sources (`buildTargetSources`)
- Forward `onBuildShowMessage`/`onBuildLogMessage` to LSP client
- Forward BSP task progress notifications to LSP client
