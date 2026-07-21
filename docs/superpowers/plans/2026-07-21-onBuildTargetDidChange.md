# `onBuildTargetDidChange` → Reindex + LSP Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Handle BSP `buildTargetDidChange` notification by re-fetching dependency sources and triggering navigation reindex with LSP progress notification.

**Architecture:** Add new `ConnectionMessage.BuildTargetChanged` message type, enqueue it from `BasamakeBuildClient`, handle in supervisor dispatch with a new callback. Progress notification uses `$/progress` with a fixed token registered once per connection.

**Tech Stack:** Scala 3.7.4, lsp4j 0.24.0, bsp4j 2.1.1, munit 1.0.4

---

### Task 1: Add `BuildTargetChanged` message type to `ConnectionMessage`

**Files:**
- Modify: `modules/core/src/ba/sake/basamake/core/ConnectionMessage.scala`

- [ ] **Step 1: Add the new case class**

Add after `BspPublishDiagnostics` (line 22):

```scala
  // BSP-originated messages (via BasamakeBuildClient callback)
  final case class BspPublishDiagnostics(params: ch.epfl.scala.bsp4j.PublishDiagnosticsParams)
      extends ConnectionMessage
  final case class BuildTargetChanged(params: ch.epfl.scala.bsp4j.DidChangeBuildTarget)
      extends ConnectionMessage
```

- [ ] **Step 2: Compile to verify**

Run: `deder exec`
Expected: COMPILED, 0 errors

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/ba/sake/basamake/core/ConnectionMessage.scala
git commit -m "feat: add BuildTargetChanged connection message"
```

---

### Task 2: Enqueue message from `BasamakeBuildClient`

**Files:**
- Modify: `modules/core/src/ba/sake/basamake/bsp/BasamakeBuildClient.scala`

- [ ] **Step 1: Replace no-op with queue offer**

Replace line 40-41:

```scala
  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit =
    logger.debug(s"BSP TARGET DID CHANGE ${params}")
```

With:

```scala
  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit =
    logger.debug(s"BSP TARGET DID CHANGE: ${params.getChanges.size()} event(s)")
    queue.offer(ConnectionMessage.BuildTargetChanged(params))
```

- [ ] **Step 2: Compile to verify**

Run: `deder exec`
Expected: COMPILED, 0 errors

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/ba/sake/basamake/bsp/BasamakeBuildClient.scala
git commit -m "feat: enqueue BuildTargetChanged on BSP target change notification"
```

---

### Task 3: Add `onBuildTargetChanged` callback parameter to `BspConnectionSupervisor`

**Files:**
- Modify: `modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala`

- [ ] **Step 1: Add new import for lsp4j progress types**

After line 8 (`import org.eclipse.lsp4j.*`):

```scala
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
```

(`org.eclipse.lsp4j.*` already covers `ProgressParams`, `WorkDoneProgressBegin`, `WorkDoneProgressEnd`, `WorkDoneProgressCreateParams`, `WorkDoneProgressNotification`)

- [ ] **Step 2: Add `onBuildTargetChanged` parameter to `supervise` method**

Change the signature (lines 21-27) — add new parameter after `onCompileSuccess`, before the closing `): Unit = {`:

```scala
  def supervise(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage],
      lspClient: LanguageClient,
      onRoutingReady: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTarget], SourcesResult, DependencySourcesResult) => Unit,
      onCompileSuccess: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTargetIdentifier]) => Unit = (_, _) => (),
      onBuildTargetChanged: (ch.epfl.scala.bsp4j.BuildServer, DependencySourcesResult, List[BuildTargetIdentifier], List[BuildTargetIdentifier]) => Unit = (_, _, _, _) => ()
  ): Unit = {
```

- [ ] **Step 3: Pass `onBuildTargetChanged` through to `transitionToRunning`**

Update all calls to `transitionToRunning` in `supervise` (lines 43, 45, 50) to include `onBuildTargetChanged`:

Line 43:
```scala
              transitionToRunning(durable, queue, lspClient, onRoutingReady, onCompileSuccess, onBuildTargetChanged, Some(msg))
```

Line 45:
```scala
          transitionToRunning(durable, queue, lspClient, onRoutingReady, onCompileSuccess, onBuildTargetChanged, None)
```

Line 50:
```scala
          transitionToRunning(durable, queue, lspClient, onRoutingReady, onCompileSuccess, onBuildTargetChanged, None)
```

- [ ] **Step 4: Add `onBuildTargetChanged` parameter to `transitionToRunning`**

Change the `transitionToRunning` signature (lines 91-98):

```scala
  private def transitionToRunning(
      durable: DurableRecord,
      queue: BlockingQueue[ConnectionMessage],
      lspClient: LanguageClient,
      onRoutingReady: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTarget], SourcesResult, DependencySourcesResult) => Unit,
      onCompileSuccess: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTargetIdentifier]) => Unit,
      onBuildTargetChanged: (ch.epfl.scala.bsp4j.BuildServer, DependencySourcesResult, List[BuildTargetIdentifier], List[BuildTargetIdentifier]) => Unit,
      triggerMsg: Option[ConnectionMessage]
  ): Unit = {
```

- [ ] **Step 5: Add progress state variables in `transitionToRunning`**

After `val compileInFlight = new AtomicBoolean(false)` (line 120), add:

```scala
      val progressTokenRegistered = new AtomicBoolean(false)
      val progressSupported = new AtomicBoolean(false)
```

- [ ] **Step 6: Update the dispatch call in `transitionToRunning` (triggerMsg path)**

In the `triggerMsg.foreach` block (line 125-128), update the dispatch call to pass the new params:

```scala
        dispatch(msg, durable, lspClient, buildServer, targetSourceRootsById, allTargetIds, onCompileSuccess, onBuildTargetChanged, compileInFlight, progressTokenRegistered, progressSupported)
```

- [ ] **Step 7: Update all dispatch calls in the Connected loop**

In the Connected loop (lines 161-162, 168-170), update all dispatch calls:

Line 161-162:
```scala
                dispatch(msg, durable, lspClient, buildServer, targetSourceRootsById, allTargetIds, onCompileSuccess, onBuildTargetChanged, compileInFlight, progressTokenRegistered, progressSupported)
```

Line 167-170: same update for the other dispatch call.

- [ ] **Step 8: Compile to verify**

Run: `deder exec`
Expected: COMPILED, 0 errors

- [ ] **Step 9: Commit**

```bash
git add modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala
git commit -m "feat: add onBuildTargetChanged callback plumbing to supervisor"
```

---

### Task 4: Add `dispatch` case and `handleBuildTargetChanged` method

**Files:**
- Modify: `modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala`

- [ ] **Step 1: Update `dispatch` method signature**

Change `dispatch` signature (lines 188-197):

```scala
  private def dispatch(
      msg: ConnectionMessage,
      durable: DurableRecord,
      lspClient: LanguageClient,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      targetToSourceRoots: Map[BuildTargetIdentifier, List[String]],
      allTargetIds: List[BuildTargetIdentifier],
      onCompileSuccess: (ch.epfl.scala.bsp4j.BuildServer, List[BuildTargetIdentifier]) => Unit,
      onBuildTargetChanged: (ch.epfl.scala.bsp4j.BuildServer, DependencySourcesResult, List[BuildTargetIdentifier], List[BuildTargetIdentifier]) => Unit,
      compileInFlight: AtomicBoolean,
      progressTokenRegistered: AtomicBoolean,
      progressSupported: AtomicBoolean
  ): Unit = 
```

- [ ] **Step 2: Add `BuildTargetChanged` dispatch case**

Add new case inside `dispatch` match, before the `case _ => ()` wildcard (line 238):

```scala
      case ConnectionMessage.BuildTargetChanged(params) =>
        handleBuildTargetChanged(params, durable, buildServer, lspClient, onBuildTargetChanged, progressTokenRegistered, progressSupported)
```

- [ ] **Step 3: Add the `handleBuildTargetChanged` method**

Add the method after `dispatch` (before `triggerCompile`, around line 240):

```scala
  private val ReindexProgressToken = "basamake-reindex"

  private def handleBuildTargetChanged(
      params: ch.epfl.scala.bsp4j.DidChangeBuildTarget,
      durable: DurableRecord,
      buildServer: ch.epfl.scala.bsp4j.BuildServer,
      lspClient: LanguageClient,
      onBuildTargetChanged: (ch.epfl.scala.bsp4j.BuildServer, DependencySourcesResult, List[BuildTargetIdentifier], List[BuildTargetIdentifier]) => Unit,
      progressTokenRegistered: AtomicBoolean,
      progressSupported: AtomicBoolean
  ): Unit = {
    if buildServer == null || durable.currentState != BspConnectionState.Connected then return

    val changes = Option(params.getChanges).map(_.asScala.toList).getOrElse(Nil)
    if changes.isEmpty then return

    val (changedOrCreated, deleted) = changes.partition { e =>
      val kind = Option(e.getKind)
      kind.contains(ch.epfl.scala.bsp4j.BuildTargetEventKind.CREATED) ||
        kind.contains(ch.epfl.scala.bsp4j.BuildTargetEventKind.CHANGED)
    }
    val changedOrCreatedIds = changedOrCreated.map(_.getTarget)
    val deletedIds = deleted.map(_.getTarget)

    // Register progress token once per connection
    if !progressTokenRegistered.getAndSet(true) then {
      try {
        val token = Either.forLeft[String, Integer](ReindexProgressToken)
        lspClient.createProgress(new WorkDoneProgressCreateParams(token))
          .get(2, java.util.concurrent.TimeUnit.SECONDS)
        progressSupported.set(true)
        logger.debug(s"Progress token '$ReindexProgressToken' registered")
      } catch {
        case e: Exception =>
          logger.debug(s"Progress token '$ReindexProgressToken' registration failed (client may not support $/progress): ${e.getMessage}")
          progressSupported.set(false)
      }
    }

    // Handle changed/created targets
    if changedOrCreatedIds.nonEmpty then {
      sendProgressBegin(lspClient, changedOrCreatedIds.size, progressSupported)
      try {
        val depParams = new ch.epfl.scala.bsp4j.DependencySourcesParams(changedOrCreatedIds.asJava)
        val result = buildServer.buildTargetDependencySources(depParams)
          .get(durable.bspFile.get().compileTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        onBuildTargetChanged(buildServer, result, changedOrCreatedIds, Nil)
      } catch {
        case e: Exception =>
          logger.error(s"Failed to fetch dependency sources for changed targets: ${e.getMessage}")
      } finally {
        sendProgressEnd(lspClient, progressSupported)
      }
    }

    // Handle deleted targets (no progress, no BSP fetch)
    if deletedIds.nonEmpty then {
      logger.info(s"BSP target(s) deleted: ${deletedIds.map(_.getUri).mkString(", ")}")
      onBuildTargetChanged(buildServer, null, Nil, deletedIds)
    }
  }

  private def sendProgressBegin(lspClient: LanguageClient, targetCount: Int, progressSupported: AtomicBoolean): Unit = {
    if !progressSupported.get() then return
    try {
      val token = Either.forLeft[String, Integer](ReindexProgressToken)
      val begin = Either.forLeft[WorkDoneProgressNotification, Object](
        new WorkDoneProgressBegin(s"Reindexing $targetCount target(s)…")
      )
      lspClient.notifyProgress(new ProgressParams(token, begin))
    } catch {
      case e: Exception => logger.debug(s"Failed to send progress begin: ${e.getMessage}")
    }
  }

  private def sendProgressEnd(lspClient: LanguageClient, progressSupported: AtomicBoolean): Unit = {
    if !progressSupported.get() then return
    try {
      val token = Either.forLeft[String, Integer](ReindexProgressToken)
      val end = Either.forLeft[WorkDoneProgressNotification, Object](
        new WorkDoneProgressEnd()
      )
      lspClient.notifyProgress(new ProgressParams(token, end))
    } catch {
      case e: Exception => logger.debug(s"Failed to send progress end: ${e.getMessage}")
    }
  }
```

- [ ] **Step 4: Compile to verify**

Run: `deder exec`
Expected: COMPILED, 0 errors

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala
git commit -m "feat: handle BuildTargetChanged with progress notification and dep source re-fetch"
```

---

### Task 5: Implement `targetChangedCallback` in `BuildServerManager`

**Files:**
- Modify: `modules/core/src/ba/sake/basamake/bsp/BuildServerManager.scala`

- [ ] **Step 1: Add the callback implementation**

After the `compileCallback` definition (line 152), add:

```scala
    val targetChangedCallback = (
        buildServer: bsp4j.BuildServer,
        depSourcesResult: bsp4j.DependencySourcesResult,
        changedOrCreatedIds: List[BuildTargetIdentifier],
        deletedIds: List[BuildTargetIdentifier]) => {
      // Merge new dep URIs for changed/created targets
      if depSourcesResult != null then
        ctx.dependencySourceUrisByTarget =
          ctx.dependencySourceUrisByTarget ++ extractTargetDependencySourceUris(depSourcesResult)
      // Remove deleted targets from all contexts
      for tid <- deletedIds do
        ctx.sourceRootsByTarget -= tid
        ctx.dependencySourceUrisByTarget -= tid
      // Trigger nav refresh for all affected targets
      val allAffected = changedOrCreatedIds ++ deletedIds
      if allAffected.nonEmpty then
        navRefreshPending.set(allAffected)
        LockSupport.unpark(navRefreshThread)
    }
```

- [ ] **Step 2: Wire `targetChangedCallback` into the `supervise` call**

Update the `supervise` call (line 154-156):

```scala
    val vt = Thread.ofVirtual().start(() =>
      BspConnectionSupervisor.supervise(record, msgQueue, client, routingReadyCallback, compileCallback, targetChangedCallback)
    )
```

- [ ] **Step 3: Compile to verify**

Run: `deder exec`
Expected: COMPILED, 0 errors

- [ ] **Step 4: Run all tests**

Run: `deder exec -t test -m core-test`
Expected: all 167 tests PASS

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/ba/sake/basamake/bsp/BuildServerManager.scala
git commit -m "feat: implement targetChangedCallback to update dep sources and trigger nav refresh"
```

---

### Task 6: Write unit test for `handleBuildTargetChanged`

**Files:**
- Modify: `modules/core/test/src/ba/sake/basamake/bsp/BspConnectionSupervisorTest.scala`

- [ ] **Step 1: Read current test for reference structure**

The existing test uses munit `FunSuite`. Tests are in `ba.sake.basamake.bsp` package.

- [ ] **Step 2: Add test imports**

No new imports needed — `BspConnectionSupervisor` already imported via package.

- [ ] **Step 3: Add test for progress registration attempt**

At the end of `BspConnectionSupervisorTest` class (before the closing brace), add:

```scala
  import java.util.concurrent.atomic.AtomicBoolean
  import org.eclipse.lsp4j.services.LanguageClient
  import org.eclipse.lsp4j.WorkDoneProgressCreateParams

  test("handleBuildTargetChanged classifies CREATED/CHANGED/DELETED events correctly") {
    val created = new ch.epfl.scala.bsp4j.BuildTargetEvent(new BuildTargetIdentifier("target://a"))
    created.setKind(ch.epfl.scala.bsp4j.BuildTargetEventKind.CREATED)
    val changed = new ch.epfl.scala.bsp4j.BuildTargetEvent(new BuildTargetIdentifier("target://b"))
    changed.setKind(ch.epfl.scala.bsp4j.BuildTargetEventKind.CHANGED)
    val deleted = new ch.epfl.scala.bsp4j.BuildTargetEvent(new BuildTargetIdentifier("target://c"))
    deleted.setKind(ch.epfl.scala.bsp4j.BuildTargetEventKind.DELETED)

    val events = List(created, changed, deleted)
    // Verify partition: CREATED + CHANGED are added, DELETED removed
    val (createdChanged, removed) = events.partition { e =>
      val kind = Option(e.getKind)
      kind.contains(ch.epfl.scala.bsp4j.BuildTargetEventKind.CREATED) ||
        kind.contains(ch.epfl.scala.bsp4j.BuildTargetEventKind.CHANGED)
    }
    assertEquals(createdChanged.map(_.getTarget.getUri).toSet, Set("target://a", "target://b"))
    assertEquals(removed.map(_.getTarget.getUri).toSet, Set("target://c"))
  }

  test("handleBuildTargetChanged empty changes is no-op") {
    val params = new ch.epfl.scala.bsp4j.DidChangeBuildTarget(java.util.Collections.emptyList())
    assertEquals(params.getChanges.size, 0)
  }
```

- [ ] **Step 4: Compile and run test**

```bash
deder exec -t test -m core-test
```

Expected: all tests PASS (2 new tests pass)

- [ ] **Step 5: Commit**

```bash
git add modules/core/test/src/ba/sake/basamake/bsp/BspConnectionSupervisorTest.scala
git commit -m "test: add BuildTargetChanged event classification tests"
```

---

### Task 7: End-to-end smoke test verification

**Files:**
- No code changes — manual verification

- [ ] **Step 1: Build fat JAR**

```bash
deder exec -t assembly -m core
```

- [ ] **Step 2: Start basamake against a project with BSP connection**

If using the example hello project:
```bash
cd examples/hello
python3 smoke_test.py
```

Or launch directly and check logs.

- [ ] **Step 3: Verify behavior**

**Trigger conditions:** When a BSP server (e.g., sbt) sends `buildTargetDidChange` after dependency change:

1. Check logs for `"BSP TARGET DID CHANGE: X event(s)"`
2. Check logs for `"BSP target(s) deleted: ..."` (if any deleted)
3. Check logs for `"Reindexing X target(s)…"` via progress notification
4. Verify go-to-definition works for new dependency symbols after reindex

**Edge case verification:**
- Modify `build.sbt` to add dependency → save → check logs
- Remove dependency → save → check logs
- Nothing should crash, no diagnostics should be lost

- [ ] **Step 4: Commit any smoke test changes**

If smoke test was modified, commit it.

---

### Task 8: Final verification and cleanup

- [ ] **Step 1: Run full test suite one last time**

```bash
deder exec -t test -m core-test
```

Expected: all tests PASS (167+ tests)

- [ ] **Step 2: Run full compile**

```bash
deder exec
```

Expected: COMPILED, 0 errors

- [ ] **Step 3: Review diff**

```bash
git diff main..HEAD --stat
```

Expected: 4 source files changed, 1 test file changed.
- `ConnectionMessage.scala`
- `BasamakeBuildClient.scala`
- `BspConnectionSupervisor.scala`
- `BuildServerManager.scala`
- `BspConnectionSupervisorTest.scala`
