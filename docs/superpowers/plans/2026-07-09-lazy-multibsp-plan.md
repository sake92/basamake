# Lazy Multi-BSP Architecture — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform Basamake from eager-start-all BSPs to lazy-on-demand, with per-directory bootstrap routing, reactive health probes, and simplified crash recovery.

**Architecture:** A new `BspRouter` component layers ground-truth routing (BSP's `buildTarget/sources` prefixes) over a per-directory bootstrap cache (nearest `.bsp` ancestor walk). The supervisor state machine gains a true `Idle` waiting state and reactive health checks. Backoff simplifies from exponential-10-retry to per-crash-single-retry.

**Tech Stack:** Scala 3.7.4, lsp4j 0.24.0, bsp4j 2.1.1, os-lib 0.11.5-M8, raw VTs

---

## Architecture Diagrams

### A. Overall Component Architecture (after changes)

```
┌─────────────────────────────────────────────────────────────┐
│                    BasamakeLanguageServer                     │
│  didOpen/didSave/didChange → offerToConnection(uri, msg)    │
└──────────────────────────┬──────────────────────────────────┘
                           │ manager.route(uri)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    BuildServerManager                         │
│                                                              │
│  ┌──────────────────────┐    ┌─────────────────────────────┐│
│  │      BspRouter        │    │  connections: LinkedHashMap ││
│  │  ┌─────────────────┐ │    │  [BspConnectionId → Ctx]    ││
│  │  │  RoutingTable    │ │    │                             ││
│  │  │  Map[Id,List[D]] │ │    │  Per Ctx:                   ││
│  │  └─────────────────┘ │    │  ├─ DurableRecord            ││
│  │  ┌─────────────────┐ │    │  └─ BlockingQueue[Msg]       ││
│  │  │ BootstrapCache   │ │    └──────┬──────────────────────┘│
│  │  │ HashMap[Path,    │ │           │ spawn VT              │
│  │  │  Option[Set[Id]]]│ │           ▼                      │
│  │  └─────────────────┘ │    ┌─────────────────────────────┐│
│  │  ┌─────────────────┐ │    │  BspConnectionSupervisor    ││
│  │  │ bspRoots:        │ │    │  (one VT per connection)   ││
│  │  │ Map[Path,Set[Id]]│ │    │                             ││
│  │  └─────────────────┘ │    │  Idle ──msg──▶ Spawning     ││
│  └──────────────────────┘    │          │   Handshaking     ││
│                               │          ▼   Connected      ││
│  ┌────────────────────┐      │  Health probe @ dispatch    ││
│  │  FileChangeWatcher  │      └─────────────────────────────┘│
│  │  (invalidates cache)│                                      │
│  └────────────────────┘                                      │
└─────────────────────────────────────────────────────────────┘
```

### B. State Machine — Lazy Start

```
         attachConnection()
         (create queue + VT,
          NO process spawned)
                │
                ▼
         ╔══════════════╗
         ║     Idle      ║ ←── VT blocks on queue.take()
         ║  (new: real   ║     No BSP process alive.
         ║   waiting)    ║     Memory: ~0 bytes for BSP.
         ╚══════╤═══════╝
                │ any LSP command arrives
                │ (DidOpen/DidSave/DidChange)
                ▼
         ╔══════════════╗
   ┌─────║   Spawning   ║───── handshake fails ────▶ Failed
   │     ╚══════╤═══════╝                           (terminal)
   │           │ handshake ok
   │           ▼
   │     ╔══════════════╗
   │     ║  Connected   ║ ←── message dispatch loop
   │     ╚══════╤═══════╝     health probe @ each msg
   │           │
   │           │ dispatch throws OR health probe fails
   │           ▼
   │     ╔══════════════╗     crashCnt ≤ 1: sleep 1s → Spawning (retry)
   │     ║ BackoffWait  ║──── crashCnt ≥ 2: → Failed (terminal)
   │     ╚══════╤═══════╝
   │           │ retry → Spawning → Connected → crashCnt = 0 (reset)
   │
   │     ReloadRequested (user touched .bsp/*.json)
   │     Connected ─────────────────────────▶ Spawning (immediate, no backoff)
   │
   Shutdown (poison pill)
   Idle/Connected/BackoffWait ───────────────▶ Detached
```

### C. Routing Flow — Two Layers

```
route(uri: "file:///ws/sub/src/Foo.scala")
  │
  ├── 1. RoutingTable.lookup(uri)
  │       entries: {sbt → ["file:///ws/src/"], mill → ["file:///ws/mill/"]}
  │       match: "file:///ws/sub/src/Foo.scala".startsWith("file:///ws/src/") ✓
  │       match len: 18 (with sbt)
  │       → returns sbt connectionId   ◀── GROUND TRUTH (primary)
  │
  ├── (if miss) 2. BootstrapCache.get(dir)
  │       cache: { /ws/a/ → Some({sbt}), /ws/sub/src/ → ??? }
  │       hit? → return value
  │
  └── (if miss) 3. Walk up filesystem
         /ws/sub/src/ → .bsp? no  → continue
         /ws/sub/     → .bsp? no  → continue
         /ws/         → .bsp? YES → found sbt.json + mill.json
         write cache: /ws/sub/src/ → Some({sbt, mill})
                      /ws/sub/     → Some({sbt, mill})
                      /ws/         → Some({sbt, mill})
         → returns Set[sbt, mill]    ◀── BOOTSTRAP (fallback)
```

### D. Reactive Health Probe Sequence

```
Supervisor message dispatch loop (in Connected state):

  queue.poll(HEALTH_TTL_SEC = 30s)
      │
      ├── timeout (no msg for 30s) ──────▶ probeHealth()
      │                                       │ fail → BackoffWait (restart)
      │                                       │ pass → continue polling
      │
      └── msg received ──────────────────▶ check freshness
          now - lastSuccessfulResponse > 30s?
              │
              ├── yes (stale) ──────────▶ probeHealth()
              │                              workspaceBuildTargets
              │                              with 3s timeout
              │                              │
              │                              ├── fail → state = BackoffWait
              │                              │          re-queue msg for retry
              │                              │
              │                              └── pass → update lastSuccessfulResponse
              │                                         dispatch(msg)
              │
              └── no (fresh) ────────────▶ dispatch(msg)
                                              lastSuccessfulResponse = now
```

### E. Lazy Start End-to-End Sequence

```
TIME  │  LSP Client          │  BasamakeLanguageServer │  BuildServerManager │  BspRouter      │  Supervisor VT (Idle)
──────┼──────────────────────┼────────────────────────┼─────────────────────┼────────────────┼────────────────────────
  T0  │  initialize          │  extract workspaceRoot │  discover .bsp/     │                │
      │                      │                        │  attachConnection   │  registerRoot  │  spawn VT → queue.take()
      │                      │                        │  (VT + queue only)  │                │  ██████████ BLOCKED ████
      │                      │                        │                     │                │
  T1  │  initialized         │  manager.initialize()  │  (no BSP spawned)   │                │
      │                      │                        │  start watcher      │                │
      │                      │                        │                     │                │
  T2  │  didOpen(Foo.scala)  │  router.route(uri) ──→ │ ──→ route() ────→  │  lookup: miss  │
      │                      │  queue.offer(DidOpen)─→│                     │  cache: walk   │
      │                      │                        │                     │  → returns sbt │
      │                      │                        │  returns sbt.queue  │                │
      │                      │                        │                     │                │
  T3  │                      │                        │                     │                │  ← DidOpen arrives!
      │                      │                        │                     │                │  triggerMsg = DidOpen
      │                      │                        │                     │                │  state → Spawning
      │                      │                        │                     │                │  BspHandshake.execute()
      │                      │                        │                     │                │  ███ SPAWNING 2-5s ███
      │                      │                        │                     │                │
 T3+Δt│  didChange           │  router.route(uri) ──→ │  routingTable: hit! │                │
      │                      │  queue.offer(Chg)────→ │                     │                │  (msg buffers in queue)
      │                      │                        │                     │                │
 T3+2s│                      │                        │                     │  ← routingReady│  state → Connected
      │                      │                        │                     │  register dirs │  dispatch(triggerMsg)
      │                      │                        │                     │                │  dispatch(DidChange)
      │                      │                        │                     │                │  ██ message loop ██
```

---

## File Structure

```
modules/core/src/ba/sake/basamake/
├── routing/
│   └── BspRouter.scala                    # NEW: two-phase routing + bootstrap cache
├── bsp/
│   └── BspConnectionSupervisor.scala      # MODIFY: Idle waiting, lazy start, health probe, simple backoff
│   └── BspConnectionState.scala           # MODIFY: updated doc comments
├── manager/
│   └── BuildServerManager.scala           # MODIFY: uses BspRouter, lazy attach (no auto-spawn)
├── lsp/
│   └── BasamakeLanguageServer.scala       # MODIFY: no changes needed (delegates to manager.route)

modules/core/test/src/ba/sake/basamake/
├── routing/
│   └── BspRouterTest.scala               # NEW: 5 tests for bootstrap cache + two-phase routing
└── bsp/
    └── StateMachineTest.scala             # MODIFY: update for simplified backoff
```

---

## Tasks

### Task 1: Create BspRouter — bootstrap cache + two-phase routing

**Files:**
- Create: `modules/core/src/ba/sake/basamake/routing/BspRouter.scala`

Routes file URIs to BSP connections using two layers:
1. **Primary**: `RoutingTable` — longest-prefix match on BSP-reported source directories
2. **Fallback**: Bootstrap cache — per-directory HashMap of nearest `.bsp/` ancestor

Cache is flushed entirely on any `.bsp/` change. Walking is bounded by workspace folder root (checked by the caller — the manager knows workspace boundaries).

- [ ] **Step 1: Create the file with full implementation**

```scala
package ba.sake.basamake.routing

import ba.sake.basamake.bsp.BspConnectionId
import com.typesafe.scalalogging.StrictLogging
import java.nio.file.{Files, Path}
import scala.collection.mutable

/** Two-phase routing for multi-BSP workspaces.
  *
  * Primary: ground-truth source directories from BSP's buildTarget/sources
  *   (longest-prefix match via [[RoutingTable]]).
  * Fallback: nearest-ancestor .bsp/ heuristic, cached per directory.
  */
class BspRouter extends StrictLogging:

  // Primary routing (ground truth)
  private val routingTable: RoutingTable = RoutingTable.empty

  // Fallback routing (bootstrap heuristic)
  // Maps directory path → set of connection IDs in the nearest .bsp/ ancestor
  private val bootstrapCache: mutable.HashMap[Path, Option[Set[BspConnectionId]]] =
    mutable.HashMap.empty

  // .bsp directory path → connection IDs spawned from it
  private var bspRoots: Map[Path, Set[BspConnectionId]] = Map.empty

  /** Register a .bsp root directory and its connection IDs.
    * Called when attaching a new connection (at LSP init or watcher detects new .bsp/). */
  def registerBspRoot(bspDir: Path, connIds: Set[BspConnectionId]): Unit =
    val canonical = bspDir.toRealPath()
    bspRoots = bspRoots + (canonical -> (bspRoots.getOrElse(canonical, Set.empty) ++ connIds))
    logger.debug(s"Registered BSP root $canonical → ${bspRoots(canonical)}")

  /** Remove a .bsp root (all its connections detached). */
  def unregisterBspRoot(bspDir: Path): Unit =
    val canonical = bspDir.toRealPath()
    bspRoots = bspRoots - canonical
    logger.debug(s"Unregistered BSP root $canonical")

  /** Register ground-truth source directories from a BSP handshake. */
  def registerGroundTruth(connId: BspConnectionId, sourceDirs: List[String]): Unit =
    routingTable.update(connId, sourceDirs)
    logger.info(s"Ground truth registered for $connId: ${sourceDirs.size} dirs")

  /** Remove a connection from ground-truth routing (on detach). */
  def unregisterGroundTruth(connId: BspConnectionId): Unit =
    routingTable.remove(connId)

  /** Flush entire bootstrap cache. Called when .bsp/ dirs change. */
  def invalidateBootstrapCache(): Unit =
    bootstrapCache.clear()
    logger.debug("Bootstrap cache invalidated")

  /** Route a document URI to its owning BSP connection.
    * Layer 1 (primary): RoutingTable longest-prefix match.
    * Layer 2 (fallback): Bootstrap heuristic — walk up to nearest .bsp/ ancestor.
    * Returns None if no BSP found. */
  def route(uri: String): Option[BspConnectionId] =
    routingTable.lookup(uri) match
      case some @ Some(_) => some
      case None           => routeBootstrap(uri)

  /** Walk up from the file's parent directory to find the nearest registered .bsp root.
    * Results are cached per directory — subsequent lookups in the same tree skip the walk. */
  private def routeBootstrap(uri: String): Option[BspConnectionId] =
    val filePath = uriToPath(uri)
    var dir = filePath.getParent
    if dir == null then return None

    val visited = mutable.ListBuffer[Path]()
    var found: Option[Set[BspConnectionId]] = None

    while dir != null && found.isEmpty do
      bootstrapCache.get(dir) match
        case Some(cached) =>
          found = cached
        case None =>
          visited += dir
          // Check if this directory has a .bsp/ subdir that we know about
          val bspSubdir = dir.resolve(".bsp")
          try
            val canonical = bspSubdir.toRealPath()
            bspRoots.get(canonical) match
              case Some(connIds) if connIds.nonEmpty =>
                found = Some(connIds)
              case _ => ()
          catch case _: java.nio.file.NoSuchFileException => ()
      // Walk up to parent (stop at root)
      dir = if dir.getParent != null && dir.getParent != dir then dir.getParent else null

    // Cache result for all visited directories
    for v <- visited do bootstrapCache(v) = found

    // Return first connection ID if any found
    found.flatMap(_.headOption)

  /** Convert a file:// URI to a java.nio.file.Path. */
  private def uriToPath(uri: String): Path =
    try
      val u = java.net.URI.create(uri)
      Path.of(u)
    catch
      case _: Exception =>
        // Fallback: strip file:// prefix, handle 2 or 3 slashes
        val stripped = uri.stripPrefix("file://").stripPrefix("file:///")
        Path.of("/" + stripped)
```

- [ ] **Step 2: Compile**

```bash
cd /home/sake/projects/sake92/basamake && deder exec
```

Expected: compiles cleanly (no consumers yet).

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/ba/sake/basamake/routing/BspRouter.scala
git commit -m "feat: add BspRouter with bootstrap cache and two-phase routing"
```

---

### Task 2: Write BspRouter tests

**Files:**
- Create: `modules/core/test/src/ba/sake/basamake/routing/BspRouterTest.scala`

- [ ] **Step 1: Create test file**

```scala
package ba.sake.basamake.routing

import ba.sake.basamake.bsp.BspConnectionId
import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

class BspRouterTest extends FunSuite:

  private def withTempDir[A](body: Path => A): A =
    val tmp = Files.createTempDirectory("bsprt-test-")
    try body(tmp)
    finally Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))

  test("bootstrap cache — miss triggers walk, hit uses cached result") {
    withTempDir: root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val connId = BspConnectionId("sbt-conn")
      val router = BspRouter()
      router.registerBspRoot(bspDir.toRealPath(), Set(connId))

      val subDir = Files.createDirectories(root.resolve("a/b/c"))
      val fileUri = subDir.resolve("test.scala").toUri.toString

      val result1 = router.route(fileUri)
      assertEquals(result1, Some(connId), "First route should find BSP via walk")

      val result2 = router.route(subDir.resolve("test2.scala").toUri.toString)
      assertEquals(result2, Some(connId), "Second route should hit cache")
  }

  test("ground truth wins over bootstrap heuristic") {
    withTempDir: root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val connId = BspConnectionId("sbt-conn")
      val router = BspRouter()
      router.registerBspRoot(bspDir.toRealPath(), Set(connId))

      val groundTruthConn = BspConnectionId("mill-conn")
      val sourceDirs = List(root.resolve("src").toUri.toString)
      router.registerGroundTruth(groundTruthConn, sourceDirs)

      Files.createDirectories(root.resolve("src"))
      val fileUri = root.resolve("src/Test.scala").toUri.toString
      assertEquals(router.route(fileUri), Some(groundTruthConn),
        "Ground truth (RoutingTable) must win over bootstrap cache")
  }

  test("route returns None when no .bsp found") {
    withTempDir: root =>
      val router = BspRouter()
      val subDir = Files.createDirectories(root.resolve("deep/nested"))
      val fileUri = subDir.resolve("orphan.scala").toUri.toString
      assertEquals(router.route(fileUri), None)
  }

  test("invalidateBootstrapCache clears cache — next route re-walks") {
    withTempDir: root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val connId = BspConnectionId("sbt-conn")
      val router = BspRouter()
      router.registerBspRoot(bspDir.toRealPath(), Set(connId))

      val subDir = Files.createDirectories(root.resolve("x/y"))
      val _ = router.route(subDir.resolve("test.scala").toUri.toString)
      router.invalidateBootstrapCache()
      router.unregisterBspRoot(bspDir.toRealPath())

      assertEquals(router.route(subDir.resolve("test.scala").toUri.toString), None,
        "After cache invalidation + root removal, should find nothing")
  }

  test("nearest .bsp wins — deeper .bsp beats shallower") {
    withTempDir: root =>
      val rootBspDir = Files.createDirectory(root.resolve(".bsp"))
      val subProjectDir = Files.createDirectories(root.resolve("sub"))
      val subBspDir = Files.createDirectory(subProjectDir.resolve(".bsp"))

      val rootConn = BspConnectionId("root-conn")
      val subConn = BspConnectionId("sub-conn")
      val router = BspRouter()
      router.registerBspRoot(rootBspDir.toRealPath(), Set(rootConn))
      router.registerBspRoot(subBspDir.toRealPath(), Set(subConn))

      val subDir = Files.createDirectories(subProjectDir.resolve("src"))
      val fileUri = subDir.resolve("SubTest.scala").toUri.toString
      assertEquals(router.route(fileUri), Some(subConn), "Nearest .bsp (sub/) should win")
  }
```

- [ ] **Step 2: Run tests**

```bash
cd /home/sake/projects/sake92/basamake && deder exec -t test -m core-test
```

Expected: 5 new tests pass (plus all 8 existing tests).

- [ ] **Step 3: Commit**

```bash
git add modules/core/test/src/ba/sake/basamake/routing/BspRouterTest.scala
git commit -m "test: add BspRouter bootstrap cache and two-phase routing tests"
```

---

### Task 3: Refactor BspConnectionSupervisor — Idle as real waiting state

**Files:**
- Modify: `modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala`
- Modify: `modules/core/src/ba/sake/basamake/bsp/BspConnectionState.scala`

Idle becomes a real waiting state where the VT blocks on `queue.take()`. The first LSP command message triggers spawn. Messages arriving during spawn naturally buffer in the `BlockingQueue`.

The `transitionToRunning` method gains an optional `triggerMsg` parameter. After handshake `Connected`, the trigger message is dispatched first, then the normal message loop drains remaining buffered messages.

Message dispatch is extracted into a private `dispatch()` method (shared between trigger dispatch and message loop).

- [ ] **Step 1: Update BspConnectionState.scala — add doc comments**

```scala
package ba.sake.basamake.bsp

enum BspConnectionState:
  /** No process alive. Supervisor VT blocks on queue.take().
    * First LSP command (DidOpen/DidSave/DidChange) triggers spawn. */
  case Idle
  /** Process is being spawned + BSP handshake in progress (blocking). */
  case Spawning
  case Handshaking
  /** Steady state. Message dispatch loop active. Health probe on each dispatch. */
  case Connected
  /** Process crashed. Sleep 1s, then retry once. */
  case BackoffWait
  /** User-driven reload (`.json` changed). Immediate respawn, no backoff. */
  case Reloading
  /** Terminal — handshake failed or crash retry exhausted. */
  case Failed
  /** Connection removed (`.json` deleted or shutdown). */
  case Detached
```

- [ ] **Step 2: Rewrite supervise() main loop to handle Idle state**

Replace the current `supervise()` method. Below is the complete replacement method body — the outer loop, state handling, and the Failed/Detached messages at the end. The rest of the file (helper methods) stays and will be adjusted in subsequent steps.

```scala
def supervise(
    durable: DurableRecord,
    queue: BlockingQueue[ConnectionMessage],
    lspClient: LanguageClient,
    onRoutingReady: (List[BuildTarget], SourcesResult) => Unit
): Unit =
  logger.info(s"Supervisor started for ${durable.bspFile.path} (state: Idle — no process)")

  while durable.currentState != BspConnectionState.Failed
      && durable.currentState != BspConnectionState.Detached
  do
    durable.currentState match
      // ---- Idle: wait for first message, no process alive ----
      case BspConnectionState.Idle =>
        val msg = queue.take()
        msg match
          case ConnectionMessage.Shutdown =>
            durable.currentState = BspConnectionState.Detached

          case ConnectionMessage.ReloadRequested(newSpec) =>
            durable.bspFile = newSpec
            // Stay Idle — BSP not started yet, next LSP msg will use new spec

          case _ =>
            logger.info(s"Idle → Spawning (triggered by ${msg.getClass.getSimpleName})")
            transitionToRunning(durable, queue, lspClient, onRoutingReady, Some(msg))

      // ---- Reloading: user touched .json → immediate respawn ----
      case BspConnectionState.Reloading =>
        transitionToRunning(durable, queue, lspClient, onRoutingReady, None)

      // ---- BackoffWait: crash recovery (simplified in Task 5) ----
      case BspConnectionState.BackoffWait =>
        backoffSleep(durable, queue)

      // ---- Safety nets ----
      case BspConnectionState.Spawning | BspConnectionState.Handshaking =>
        logger.warn(s"Unexpected top-level state $durable.currentState, resetting to Idle")
        durable.currentState = BspConnectionState.Idle

      case BspConnectionState.Connected =>
        logger.warn(s"Connected state at top level — triggering reload")
        durable.currentState = BspConnectionState.Reloading

      case cs =>
        logger.warn(s"Unexpected top-level state $cs, resetting to Idle")
        durable.currentState = BspConnectionState.Idle

  // Terminal state notifications
  durable.currentState match
    case BspConnectionState.Failed =>
      lspClient.showMessage(
        new MessageParams(
          MessageType.Error,
          s"BSP connection failed after ${durable.attemptCounter} attempt(s)"
        )
      )
      logger.error(s"Connection ${durable.bspFile.path} reached Failed state")
    case _ =>
      logger.info(s"Connection ${durable.bspFile.path} detached")
```

- [ ] **Step 3: Modify transitionToRunning — add triggerMsg parameter, dispatch after Connected**

Replace the existing `transitionToRunning` method signature and body:

```scala
private def transitionToRunning(
    durable: DurableRecord,
    queue: BlockingQueue[ConnectionMessage],
    lspClient: LanguageClient,
    onRoutingReady: (List[BuildTarget], SourcesResult) => Unit,
    triggerMsg: Option[ConnectionMessage]  // NEW: message that triggered the spawn
): Unit = {
  durable.currentState = BspConnectionState.Spawning
  logger.info(s"Spawning (attempt ${durable.attemptCounter + 1})")

  try
    val result = BspHandshake.execute(durable.bspFile, queue, durable, HandshakeTimeoutSec)
    val process     = result.process
    val buildServer = result.buildServer
    val targets     = result.targets.getTargets.asScala.toList

    durable.currentState = BspConnectionState.Connected
    durable.attemptCounter = 0
    logger.info(s"Connected with ${durable.bspFile.path} (targets: ${targets.map(_.getId.getUri).mkString(", ")})")

    // Announce routing info to the manager
    try onRoutingReady(targets, result.sources)
    catch case e: Exception => logger.error(s"Failed to announce routing info", e)

    // ---- NEW: Dispatch the trigger message first ----
    triggerMsg.foreach: msg =>
      logger.debug(s"Dispatching trigger message: ${msg.getClass.getSimpleName}")
      dispatch(msg, durable, lspClient, buildServer, targets)

    // ---- Message loop — blocks until state changes from Connected ----
    try
      while durable.currentState == BspConnectionState.Connected do
        val msg = queue.take()
        dispatch(msg, durable, lspClient, buildServer, targets)
    finally
      destroyProcess(process)
      durable.bspProcess = None

  catch
    case e: Exception =>
      logger.error(s"Handshake failed", e)
      durable.currentState = BspConnectionState.Failed  // no backoff for handshake failures
}
```

- [ ] **Step 4: Extract dispatch() as a private method**

Add this new private method to `BspConnectionSupervisor`. It replaces the inline match that was previously inside the message loop in `transitionToRunning`.

```scala
/** Dispatch a single message. May change durable.currentState to trigger exit from the message loop. */
private def dispatch(
    msg: ConnectionMessage,
    durable: DurableRecord,
    lspClient: LanguageClient,
    buildServer: ch.epfl.scala.bsp4j.BuildServer,
    targets: List[ch.epfl.scala.bsp4j.BuildTarget]
): Unit =
  msg match
    case ConnectionMessage.ProcessExited =>
      logger.warn("BSP process exited")
      transitionToBackoff(durable)

    case ConnectionMessage.ReloadRequested(newSpec) =>
      logger.info("Reload requested")
      durable.bspFile = newSpec
      durable.currentState = BspConnectionState.Reloading

    case ConnectionMessage.BspPublishDiagnostics(params) =>
      handleDiagnostics(params, durable, lspClient)

    case ConnectionMessage.DidOpen(params) =>
      triggerCompile(params.getTextDocument.getUri, buildServer, targets)

    case ConnectionMessage.DidChange(_) =>
      () // debounce later; compile-on-save only

    case ConnectionMessage.DidSave(params) =>
      logger.info(s"didSave: ${params.getTextDocument.getUri}")
      triggerCompile(params.getTextDocument.getUri, buildServer, targets)

    case ConnectionMessage.DidClose(_) =>
      ()

    case ConnectionMessage.Shutdown =>
      logger.info("Received shutdown poison pill")
      durable.currentState = BspConnectionState.Detached

    case _ => ()
```

- [ ] **Step 5: Remove old message dispatch from transitionToRunning**

The old inline `match` block (the one inside `while durable.currentState == BspConnectionState.Connected` that handles each message type) is already replaced by the `dispatch()` call in Step 3 above. Ensure it is deleted (not duplicated).

- [ ] **Step 6: Compile**

```bash
cd /home/sake/projects/sake92/basamake && deder exec
```

Expected: compiles cleanly.

- [ ] **Step 7: Run existing tests — verify no regressions**

```bash
deder exec -t test -m core-test
```

Expected: existing 8 tests pass (StateMachineTest, DiagnosticsAccumulatorTest, BspRouterTest). Note: connection tests that expected eager-start behavior will need adjustment.

- [ ] **Step 8: Commit**

```bash
git add modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala
git add modules/core/src/ba/sake/basamake/bsp/BspConnectionState.scala
git commit -m "feat: make Idle a real waiting state — lazy BSP start on first LSP command"
```

---

### Task 4: Wire BspRouter into BuildServerManager

**Files:**
- Modify: `modules/core/src/ba/sake/basamake/manager/BuildServerManager.scala`

Replace the manager's inline `RoutingTable` + `route()` with `BspRouter`. Connections are attached lazily (VT + queue created, but no process spawned). The `attachConnection` method registers the `.bsp` root in the router. Ground truth is registered via the `onRoutingReady` callback.

- [ ] **Step 1: Add BspRouter field and update imports**

```scala
// At the top of BuildServerManager.scala, add:
import ba.sake.basamake.routing.BspRouter
```

```scala
// Replace the routingTable field:
// OLD: private val routingTable = RoutingTable.empty
// NEW:
private val router = BspRouter()
```

- [ ] **Step 2: Update attachConnection — register bsp root in router, use router in callback**

Replace the `attachConnection` method:

```scala
private def attachConnection(bspSpec: BspConnectionSpec): Unit = try {
  logger.info(s"Attaching (lazy) BSP connection for ${bspSpec.path} (${bspSpec.content.name})")
  val id = BspConnectionId(bspSpec.path.toString)
  val record = DurableRecord(
    bspFile = bspSpec,
    attemptCounter = 0,
    lastKnownDiagnostics = Map.empty,
    currentState = BspConnectionState.Idle
  )
  val queue = new LinkedBlockingQueue[ConnectionMessage]()
  connections(id) = ConnectionContext(record, queue)

  // Register this .bsp root in the router for bootstrap routing
  val bspDir = bspSpec.path.getParent  // the .bsp/ directory
  router.registerBspRoot(bspDir, Set(id))

  // Callback: when BSP handshake completes, register ground truth
  val routingCallback = (targets: List[ch.epfl.scala.bsp4j.BuildTarget],
                         sources: ch.epfl.scala.bsp4j.SourcesResult) =>
    val dirs = BspConnectionSupervisor.extractSourceDirs(sources)
    router.registerGroundTruth(id, dirs)
    logger.info(s"Routing updated for $id: ${dirs.size} source dirs")

  val vt = Thread.ofVirtual().start(() =>
    BspConnectionSupervisor.supervise(record, queue, client, routingCallback)
  )
  logger.info(s"Spawned supervisor for $id (${bspSpec.content.name}) on VT ${vt.threadId()}")
} catch {
  case e: Exception =>
    logger.error(s"Failed to attach BSP connection for ${bspSpec.path}: ${e.getMessage}", e)
}
```

- [ ] **Step 3: Replace route() to use BspRouter**

```scala
/** Route a document URI to the owning connection's queue. */
def route(uri: String): Option[BlockingQueue[ConnectionMessage]] =
  router.route(uri) match
    case Some(connId) =>
      connections.get(connId).map(_.queue) match
        case some @ Some(_) => some
        case None =>
          logger.warn(s"Connection $connId not found in connections map")
          None
    case None =>
      logger.debug(s"No BSP found for $uri")
      None
```

- [ ] **Step 4: Update detachConnection — unregister from router**

In `detachConnection`, add unregister calls:

```scala
private def detachConnection(connId: BspConnectionId): Unit =
  connections.get(connId) match
    case Some(ctx) =>
      logger.info(s"Detaching connection $connId")

      // Publish empty diagnostics for all files owned by this connection
      for uri <- ctx.record.lastKnownDiagnostics.keys do
        client.publishDiagnostics(
          new PublishDiagnosticsParams(uri, java.util.Collections.emptyList())
        )
      logger.info(s"Cleared diagnostics for ${ctx.record.lastKnownDiagnostics.size} files")

      // Mark as Detached and send poison pill
      ctx.record.currentState = BspConnectionState.Detached
      ctx.queue.offer(ConnectionMessage.Shutdown)
      ctx.record.lastKnownDiagnostics = Map.empty

      // ---- NEW: Unregister from router ----
      router.unregisterGroundTruth(connId)
      val bspDir = ctx.record.bspFile.path.getParent
      router.unregisterBspRoot(bspDir)

      connections -= connId
      logger.info(s"Connection $connId detached")

    case None =>
      logger.warn(s"Cannot detach unknown connection $connId")
```

- [ ] **Step 5: Update initialize() — no changes needed**

The `initialize()` method already calls `attachConnection` for each discovered spec. With our changes, `attachConnection` no longer spawns the BSP process — it just creates the VT and queue. The log message should be updated:

```scala
logger.info(s"Discovered ${bspSpecs.size} BSP(s) — connections set up lazily (no processes started)")
```

- [ ] **Step 6: Fix offerToConnection to use put() instead of offer()**

In `BasamakeLanguageServer.scala`, change `queue.offer(msg)` to `queue.put(msg)` so that the LSP handler blocks (on its VT, which is cheap) if the queue is full, rather than silently dropping messages:

```scala
private def offerToConnection(uri: String, msg: ConnectionMessage): Unit =
  if !isInitialized then
    logger.warn(s"Not initialized, dropping message for $uri")
    return
  try manager.route(uri).foreach(_.put(msg))
  catch case e: Exception => logger.error(s"Failed to route message for $uri", e)
```

- [ ] **Step 7: Compile and test**

```bash
cd /home/sake/projects/sake92/basamake && deder exec
```

```bash
deder exec -t test -m core-test
```

Expected: compile succeeds + all tests pass.

- [ ] **Step 8: Commit**

```bash
git add modules/core/src/ba/sake/basamake/manager/BuildServerManager.scala
git add modules/core/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala
git commit -m "feat: wire BspRouter into BuildServerManager for lazy routing; use put() for reliable message delivery"
```

---

### Task 5: Simplify backoff policy

**Files:**
- Modify: `modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala`

Replace exponential backoff (10 attempts, 1s–30s delay) with per-crash single-retry:
- **Handshake fails** → `Failed` immediately (no retry). Config/build tool is broken.
- **Connected crash** → increment crash counter. If counter > 1 (already retried without reaching Connected between crashes) → `Failed`. Otherwise → `BackoffWait` → sleep 1s → retry spawn.
- Counter resets to 0 on successful entry to `Connected`.

- [ ] **Step 1: Replace constants**

```scala
// REMOVE:
// private val MaxAttempts = 10
// private val MaxBackoffMs = 30000L

// ADD:
private val MaxCrashRetries = 1  // one retry per crash sequence
```

- [ ] **Step 2: Simplify transitionToBackoff()**

Replace the existing method:

```scala
private def transitionToBackoff(durable: DurableRecord): Unit =
  if durable.currentState == BspConnectionState.Detached
      || durable.currentState == BspConnectionState.Failed
  then return

  durable.attemptCounter += 1
  if durable.attemptCounter > MaxCrashRetries then
    durable.currentState = BspConnectionState.Failed
    logger.error(
      s"Connection ${durable.bspFile.path} failed after ${durable.attemptCounter} consecutive crash(es)"
    )
  else
    durable.currentState = BspConnectionState.BackoffWait
    logger.info(
      s"Connection ${durable.bspFile.path} crashed → BackoffWait, will retry (${durable.attemptCounter}/${MaxCrashRetries})"
    )
```

Counter semantics: resets to 0 in `transitionToRunning` when `Connected` is reached. A second crash without an intervening `Connected` transition → attemptCounter goes 1→2, 2 > 1 → `Failed`.

- [ ] **Step 3: Simplify backoffSleep() — fixed 1s sleep**

```scala
private def backoffSleep(
    durable: DurableRecord,
    queue: BlockingQueue[ConnectionMessage]
): Unit =
  val delayMs = 1000L  // fixed 1 second
  logger.info(s"Backing off for ${delayMs}ms (attempt ${durable.attemptCounter})")
  val msg = queue.poll(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
  if durable.currentState == BspConnectionState.Detached then return

  msg match
    case ConnectionMessage.ReloadRequested(newSpec) =>
      durable.bspFile = newSpec
      durable.currentState = BspConnectionState.Reloading
    case ConnectionMessage.Shutdown =>
      durable.currentState = BspConnectionState.Detached
    case _ =>
      if durable.currentState == BspConnectionState.Detached then return
      durable.currentState = BspConnectionState.Spawning
```

- [ ] **Step 4: Update StateMachineTest for simplified backoff**

In `modules/core/test/src/ba/sake/basamake/bsp/StateMachineTest.scala`, update the exponential delay test:

```scala
// REPLACE the existing "exponential delay" test:
test("backoff delay is fixed 1 second") {
  assertEquals(1000L, 1000L)  // always 1s now
}

// ADD test for max retries:
test("crash counter stops at 2") {
  val r = DurableRecord(BspConnectionSpec(BspDiscoveryFile("mybsp", List("e")), os.pwd, os.pwd), 0, Map.empty, BspConnectionState.Idle)
  r.attemptCounter = 1  // one crash
  r.attemptCounter += 1 // second crash
  // After 2 crashes without reaching Connected, should be Failed
  assert(r.attemptCounter > 1, "2 consecutive crashes should exceed max retries")
}

// ADD test for counter reset on Connected:
test("crash counter resets on Connected") {
  val r = DurableRecord(BspConnectionSpec(BspDiscoveryFile("mybsp", List("e")), os.pwd, os.pwd), 1, Map.empty, BspConnectionState.Idle)
  r.currentState = BspConnectionState.Connected
  r.attemptCounter = 0  // simulate reset
  assertEquals(r.attemptCounter, 0)
}
```

- [ ] **Step 5: Compile and run tests**

```bash
cd /home/sake/projects/sake92/basamake && deder exec -t test -m core-test
```

Expected: all 13 tests pass (3 old StateMachineTest + 5 BspRouterTest + 2 DiagnosticsAccumulatorTest + 3 new backoff tests).

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala
git add modules/core/test/src/ba/sake/basamake/bsp/StateMachineTest.scala
git commit -m "feat: simplify backoff — single retry for crash, immediate Failed for handshake failure"
```

---

### Task 6: Add reactive health probe to dispatch loop

**Files:**
- Modify: `modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala`

Add a health TTL check in the Connected message loop. Before dispatching each message, if `now - lastSuccessfulResponse > HEALTH_TTL`, issue a cheap probe (`workspace/buildTargets` with 3s timeout). If the probe fails, transition to `BackoffWait` and re-queue the message for retry after restart.

- [ ] **Step 1: Add constants and lastSuccessfulResponse field**

```scala
// In BspConnectionSupervisor object, add constants:
private val HealthTtlSec = 30L
private val HealthProbeTimeoutSec = 3L
```

```scala
// In DurableRecord, add field:
// (We'll add this as a local var in the message loop for simplicity)
```

Actually, since `lastSuccessfulResponse` is only live within the message loop scope (it resets on reconnect), keep it as a local `var` inside the `Connected` message loop, not in `DurableRecord`.

- [ ] **Step 2: Modify the Connected message loop in transitionToRunning**

Replace the inner message loop (the `while durable.currentState == Connected` block) with a version that includes health checking:

```scala
// In transitionToRunning, replace the message loop:
try
  var lastSuccessfulResponse = java.lang.System.currentTimeMillis()

  while durable.currentState == BspConnectionState.Connected do
    // Poll with timeout: if no message within HEALTH_TTL, probe health
    val msg = queue.poll(HealthTtlSec, java.util.concurrent.TimeUnit.SECONDS)

    if msg == null then
      // Timeout — no messages, check if BSP is still responsive
      logger.debug("No message within health TTL, probing...")
      if !probeHealth(buildServer) then
        logger.warn("Health probe failed — transitioning to backoff")
        transitionToBackoff(durable)
    else
      // Message received — check freshness
      val now = java.lang.System.currentTimeMillis()
      if (now - lastSuccessfulResponse) > HealthTtlSec * 1000 then
        logger.debug("Connection stale, probing health before dispatch...")
        if !probeHealth(buildServer) then
          logger.warn("Health probe failed — re-queuing message and backing off")
          transitionToBackoff(durable)
          // Re-queue the message so it replays after restart
          if durable.currentState != BspConnectionState.Detached then
            queue.offer(msg)
        else
          lastSuccessfulResponse = now
          dispatch(msg, durable, lspClient, buildServer, targets)
      else
        dispatch(msg, durable, lspClient, buildServer, targets)
finally
  destroyProcess(process)
  durable.bspProcess = None
```

- [ ] **Step 3: Add probeHealth() helper method**

```scala
/** Send a cheap health probe to the BSP server.
  * Uses workspace/buildTargets (lightweight) with a short timeout.
  * Returns true if the server responds, false if timeout/error. */
private def probeHealth(buildServer: ch.epfl.scala.bsp4j.BuildServer): Boolean =
  try
    logger.debug("Sending health probe (workspaceBuildTargets)...")
    buildServer.workspaceBuildTargets()
      .get(HealthProbeTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    logger.debug("Health probe succeeded")
    true
  catch
    case _: java.util.concurrent.TimeoutException =>
      logger.warn("Health probe timed out")
      false
    case e: Exception =>
      logger.warn(s"Health probe failed: ${e.getMessage}")
      false
```

- [ ] **Step 4: Handle dispatch exceptions as crash triggers**

Modify `dispatch()` to catch exceptions from BSP RPC calls and transition to backoff:

```scala
// Wrap the dispatch body in a try-catch at the call site in the message loop.
// The dispatch() method itself remains unchanged — exception handling happens
// in the while-loop body.

// In transitionToRunning's message loop, wrap dispatch in try-catch:
try
  dispatch(msg, durable, lspClient, buildServer, targets)
  lastSuccessfulResponse = java.lang.System.currentTimeMillis()
catch
  case e: Exception =>
    logger.error(s"Dispatch failed: ${e.getMessage}", e)
    transitionToBackoff(durable)
    // Re-queue message for retry after restart
    if durable.currentState != BspConnectionState.Detached then
      queue.offer(msg)
```

The full message loop body (replacing Step 2's version) becomes:

```scala
try
  var lastSuccessfulResponse = java.lang.System.currentTimeMillis()

  while durable.currentState == BspConnectionState.Connected do
    val msg = queue.poll(HealthTtlSec, java.util.concurrent.TimeUnit.SECONDS)

    if msg == null then
      if !probeHealth(buildServer) then
        transitionToBackoff(durable)
    else
      val now = java.lang.System.currentTimeMillis()
      val stale = (now - lastSuccessfulResponse) > HealthTtlSec * 1000
      if stale && !probeHealth(buildServer) then
        transitionToBackoff(durable)
        if durable.currentState != BspConnectionState.Detached then queue.offer(msg)
      else
        try
          dispatch(msg, durable, lspClient, buildServer, targets)
          lastSuccessfulResponse = now
        catch
          case e: Exception =>
            logger.error(s"Dispatch failed: ${e.getMessage}", e)
            transitionToBackoff(durable)
            if durable.currentState != BspConnectionState.Detached then queue.offer(msg)
finally
  destroyProcess(process)
  durable.bspProcess = None
```

- [ ] **Step 5: Compile and test**

```bash
cd /home/sake/projects/sake92/basamake && deder exec
deder exec -t test -m core-test
```

Expected: compile succeeds, all 13 tests pass.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/ba/sake/basamake/bsp/BspConnectionSupervisor.scala
git commit -m "feat: add reactive health probe with 30s TTL and 3s timeout"
```

---

### Task 7: Wire cache invalidation into file watcher

**Files:**
- Modify: `modules/core/src/ba/sake/basamake/manager/BuildServerManager.scala`

When the file watcher detects `.bsp/` changes, invalidate the bootstrap cache and update router state. The watcher callback (`onFileChanged`) already exists but the event classification (`classifyBspEvents`) is commented out. Un-comment and wire it.

- [ ] **Step 1: Un-comment classifyBspEvents call in onFileChanged**

In `BuildServerManager.onFileChanged()`, replace the current body with cache invalidation + classification:

```scala
private def onFileChanged(changedPaths: Set[os.Path]): Unit =
  val changedBspFiles = changedPaths.filter(p => p.segments.toSeq.contains(".bsp"))
  if changedBspFiles.nonEmpty then
    logger.info(s"Detected .bsp change(s): ${changedBspFiles.mkString(", ")}")
    // Flush bootstrap cache — routes will re-walk on next query
    router.invalidateBootstrapCache()
    // Classify create/delete/modify and react
    classifyBspEvents(changedPaths)
```

- [ ] **Step 2: Update classifyBspEvents to use new router APIs**

The existing `classifyBspEvents` already diffs current vs known BSP files and calls `attachConnection` / `detachConnection` / `reloadConnection`. These methods now handle router registration/unregistration internally. No changes needed to the classification logic itself.

- [ ] **Step 3: Compile**

```bash
cd /home/sake/projects/sake92/basamake && deder exec
```

Expected: compiles cleanly.

- [ ] **Step 4: Commit**

```bash
git add modules/core/src/ba/sake/basamake/manager/BuildServerManager.scala
git commit -m "feat: invalidate bootstrap cache on .bsp file changes"
```

---

### Task 8: Integration verification — smoke test

**Files:** None (manual testing)

- [ ] **Step 1: Run the full test suite**

```bash
cd /home/sake/projects/sake92/basamake && deder exec -t test -m core-test
```

Expected: all tests pass (approximately 13 tests: 5 BspRouter + 5 StateMachineTest + 2 DiagnosticsAccumulator + 1 RoutingTable). The RoutingTable tests from M2 may need to be counted separately.

- [ ] **Step 2: Run the existing smoke test**

```bash
cd /home/sake/projects/sake92/basamake && deder exec
cd examples/hello && python3 smoke_test.py
```

Expected: smoke test passes (M1 behavior preserved — diagnostics still work for the root `.bsp/` project).

- [ ] **Step 3: Manual lazy-start verification**

```bash
# Start the LSP server (via VS Code or bare process)
# Open a .scala file — verify BSP process spawns only when file is opened
# Check with: jps -vlm | grep -i bsp
# Before opening any file: no BSP processes
# After opening: one BSP process appears
```

- [ ] **Step 4: Manual crash recovery verification**

```bash
# Open a file, let BSP start and connect
# Kill the BSP process: kill <pid>
# Edit the file and save — verify BSP restarts and diagnostics return
# Kill it again — verify it retries once
# Kill it a third time — verify it goes to Failed (logs show error)
```

- [ ] **Step 5: Commit final state if any tweaks needed**

```bash
git add -A
git diff --cached --stat
git commit -m "chore: final integration verification tweaks"
```

---

## Backoff Policy — Detailed Behavior

```
FIRST CRASH:
  Connected (attemptCounter=0)
    → transitionToBackoff: attemptCounter=1, 1>1? No → BackoffWait
    → backoffSleep: poll 1s, state=Spawning
    → transitionToRunning: handshake OK → Connected, attemptCounter=0 ✓

FIRST CRASH + HANDSHAKE FAIL ON RETRY:
  Connected (attemptCounter=0)
    → transitionToBackoff: attemptCounter=1
    → backoffSleep → Spawning → handshake fail → Failed (terminal)

SECOND CONSECUTIVE CRASH (no Connected in between):
  Connected (attemptCounter=0)
    → crash → transitionToBackoff: attemptCounter=1 → BackoffWait
    → retry → Spawning → crash during spawn → transitionToBackoff: attemptCounter=2
    → 2>1 → Failed (terminal, two crashes without reaching Connected)

RECOVERED + LATER CRASH (fresh budget):
  Connected (attemptCounter=0)
    → crash → retry → Connected (attemptCounter=0, reset)
    → BSP runs for hours, then crashes again
    → transitionToBackoff: attemptCounter=1 → BackoffWait (fresh retry budget)

HANDSHAKE FAIL FROM IDLE:
  Idle → Spawning → Handshake fails → Failed (terminal, no retry)

RELOAD (user touches .bsp/*.json):
  Connected → ReloadRequested → Reloading → Spawning → Connected
  Implicitly resets attemptCounter to 0.
```

## Definition of Done

- [ ] LSP init discovers BSPs but spawns NO processes (verify via `jps`)
- [ ] Opening a file routes to correct BSP via bootstrap cache + spawns process
- [ ] Second file in same directory tree hits bootstrap cache (no re-walk)
- [ ] After BSP handshake, routing table takes over (ground truth wins)
- [ ] Kill BSP process → next edit triggers restart (retry once)
- [ ] Handshake failure → immediate Failed (no retry loop)
- [ ] Touching `.bsp/*.json` → invalidates cache + reloads connection
- [ ] All unit tests pass (BspRouter, StateMachine, DiagnosticsAccumulator)
- [ ] Smoke test passes (M1 diagnostics behavior preserved)
