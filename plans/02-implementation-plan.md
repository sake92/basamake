# Milestone 2 — Multi-BSP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support multiple BSP build servers in one workspace (e.g., root `build.sbt` via sbt-BSP + `examples/myscript.scala` via Scala CLI), each an independent connection, with files routed to the correct server via longest-path-prefix matching. Includes live attach/detach/reload driven by an os-lib-watch file watcher, per-`.bsp`-file overrides (`enabled`, `debounceMs`), and deterministic overlap resolution.

**Architecture:** The `BuildServerManager` (already supports N connections) gains: (1) a `RoutingTable` with longest-path-prefix lookup; (2) a `BspFileWatcher` running `os.watch.watch` on a dedicated VT; (3) `attachConnection`/`detachConnection`/`reloadConnection` APIs; (4) an `applyOverrides` method that merges per-file config overrides post-discovery. Discovery always autodiscovers ALL `.bsp/*.json` recursively — no filtering. Overrides (`enabled`, `debounceMs`) are applied in the manager before connecting. The routing table is built from `buildTarget/sources` directory prefixes after each handshake via a callback. JSON parsing uses Tupson (`derives JsonRW`) throughout — no manual Gson.

**Tech Stack:** Scala 3.7.4, lsp4j 0.24.0, bsp4j 2.1.1, os-lib 0.11.5-M8, os-lib-watch 0.11.5-M8, tupson 0.13.0, munit 1.0.4, raw VTs (no ox for M2 scope management)

---

## File Structure

```
modules/core/src/ba/sake/basamake/
├── Main.scala                              # MODIFY: load config, pass to server
├── config/
│   └── BasamakeConfig.scala                # NEW: config types with derives JsonRW
├── core/
│   ├── ConnectionMessage.scala             # No changes
│   ├── DiagnosticsAccumulator.scala        # No changes
│   └── DurableRecord.scala                 # No changes
├── bsp/
│   ├── BspConnectionFile.scala             # MODIFY: add buildToolName
│   ├── BspConnectionId.scala               # No changes
│   ├── BspConnectionState.scala            # No changes
│   ├── BspConnectionSupervisor.scala       # MODIFY: onRoutingReady callback + extractSourceDirs
│   ├── BspDiscovery.scala                  # MODIFY: recursive scan, Tupson parsing, public parseSingleSpec
│   ├── BspHandshake.scala                  # No changes
│   └── BasamakeBuildClient.scala           # No changes
├── routing/
│   └── RoutingTable.scala                  # NEW: prefix-based lookup + reverse index
├── watcher/
│   └── BspFileWatcher.scala                # NEW: os-lib-watch, event classification, debounce
├── manager/
│   └── BuildServerManager.scala            # MODIFY: routing table, watcher, overrides, attach/detach/reload
├── lsp/
│   └── BasamakeLanguageServer.scala        # MODIFY: load config, pass to manager
└── util/
    └── LoggingUtils.scala                  # No changes

modules/core/test/src/ba/sake/basamake/
├── core/
│   ├── DiagnosticsAccumulatorTest.scala    # No changes
│   └── RoutingTableTest.scala              # NEW: 5 tests
├── bsp/
│   ├── StateMachineTest.scala              # No changes
│   └── BspDiscoveryTest.scala              # NEW: 3 tests
└── manager/
    └── BuildServerManagerTest.scala        # NEW: override merge tests

# Build: add os-lib-watch + tupson, remove direct Gson usage
deder.pkl                                    # MODIFY
```

---

## Design Decisions

### Override model: per-`.bsp`-file, not per-tool

Two mill builds in one repo (`backend/.bsp/mill.json`, `frontend/.bsp/mill.json`) must be independently addressable. Filtering by tool name doesn't work. The config identifies each `.bsp` file by its **relative path from workspace root**:

```json
{
  "bspOverrides": [
    { "bspFile": ".bsp/sbt.json", "enabled": false },
    { "bspFile": "examples/.bsp/scalacli.json", "debounceMs": 200 }
  ]
}
```

### Always autodiscover, filter via overrides

Discovery returns ALL `.bsp/*.json` files everywhere (recursive). The manager then calls `applyOverrides` before connecting:

- No override entry → connect with defaults
- Override with `enabled: false` → skip (don't connect)
- Override with `debounceMs` → merge into spec

An empty config (`{}`) or no config file means "connect to everything with defaults."

### Override merging on reload too

When the watcher detects a `.json` content change and calls `reloadConnection(newSpec)`, the manager re-applies overrides. If the override now says `enabled: false`, the connection is detached instead of reloaded.

### Tupson for all JSON parsing

Replace the current `com.google.gson.JsonParser` manual parsing in `BspDiscovery.extractJsonArray` with a Tupson case class:

```scala
// Before (manual Gson):
val json = JsonParser.parseString(raw).getAsJsonObject
json.getAsJsonArray("argv").iterator().asScala.map(_.getAsString).toList

// After (Tupson):
case class BspDiscoverySpec(name: String, argv: List[String]) derives JsonRW
raw.parseJson[BspDiscoverySpec].argv
```

Config parsing is equally clean:

```scala
case class BasamakeConfig(bspOverrides: List[BspOverride] = Nil) derives JsonRW
case class BspOverride(  bspFile: String, enabled: Boolean = true, debounceMs: Option[Long] = None) derives JsonRW

val config = Files.readString(configPath).parseJson[BasamakeConfig]
```

### os.watch.watch is a separate artifact

`os.watch.watch` lives in `os-lib-watch`, NOT the main `os-lib` jar. Need to add both dependencies. The callback receives `Set[os.Path]` (already batched) but tells us nothing about event type — we classify create/delete/modify by comparing filesystem state before vs. after each callback batch.

### No ox for M2

Raw VTs work correctly for both the watcher and per-connection supervisors. Migration to ox structured concurrency is a cross-cutting concern for a later milestone.

---

## Tasks

### Task 1: Add tupson + os-lib-watch dependencies

**Files:** `deder.pkl`

```pkl
deps {
  "org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0"
  "ch.epfl.scala:bsp4j:2.1.1"
  "com.softwaremill.ox::core:1.0.5"
  "com.lihaoyi::os-lib:0.11.5-M8"
  "com.lihaoyi::os-lib-watch:0.11.5-M8"    // NEW: file watching
  "ba.sake::tupson:0.13.0"                   // NEW: JSON parsing (replaces Gson manual)
  "com.typesafe.scala-logging::scala-logging:3.9.5"
  "org.slf4j:slf4j-api:2.0.16"
  "ch.qos.logback:logback-classic:1.5.12"
}
```

- [ ] **Step 1: Add dependencies**
- [ ] **Step 2: Compile** — `deder exec` (should still compile; tupson is only used in new code)
- [ ] **Step 3: Commit**

```bash
git add deder.pkl
git commit -m "build: add tupson 0.13.0 and os-lib-watch 0.11.5-M8 dependencies"
```

---

### Task 2: Add `buildToolName` to `BspConnectionFile`

**Files:** `BspConnectionFile.scala`

Add a `buildToolName` field extracted from the `.json` filename (e.g., `sbt.json` → `"sbt"`, `scalacli.json` → `"scalacli"`). Used for logging only, NOT for routing/filtering decisions.

- [ ] **Step 1: Add field**

```scala
final case class BspConnectionFile(
    path: Path,
    argv: List[String],
    workingDir: Path,
    debounceMs: Long = 500,
    buildToolName: String = ""  // extracted from filename, for logging
)
```

- [ ] **Step 2: Compile** — `deder exec`
- [ ] **Step 3: Commit**

---

### Task 3: Create config types with Tupson parsing

**Files:** `BasamakeConfig.scala` (new)

- [ ] **Step 1: Create file**

```scala
package ba.sake.basamake.config

import java.nio.file.{Files, Path}
import ba.sake.tupson.{given, *}

/** Per-.bsp-file override. bspFile is relative path from workspace root. */
final case class BspOverride(
    bspFile: String,                          // e.g. ".bsp/sbt.json", "examples/.bsp/scalacli.json"
    enabled: Boolean = true,                  // false = suppress this connection entirely
    debounceMs: Option[Long] = None           // None = use spec default (500ms)
) derives JsonRW

/** Basamake configuration, loaded from .basamake/config.json. */
final case class BasamakeConfig(
    bspOverrides: List[BspOverride] = Nil     // per-.bsp-file overrides
) derives JsonRW

object BasamakeConfig:
  /** Load config from .basamake/config.json if present, otherwise defaults (allow all). */
  def load(workspaceRoot: Path): BasamakeConfig =
    val configPath = workspaceRoot.resolve(".basamake").resolve("config.json")
    if Files.isRegularFile(configPath) then
      try
        val raw = Files.readString(configPath)
        raw.parseJson[BasamakeConfig]
      catch
        case e: Exception => BasamakeConfig() // degrade gracefully — use defaults
    else BasamakeConfig()
```

- [ ] **Step 2: Compile** — `deder exec`
- [ ] **Step 3: Commit**

---

### Task 4: Enhance BspDiscovery — recursive scan + Tupson parsing

**Files:** `BspDiscovery.scala`, `BspDiscoveryTest.scala` (new)

Replace manual Gson `extractJsonArray` with a Tupson case class. Add recursive `.bsp/` scanning. Add public `parseSingleSpec` for the file watcher.

- [ ] **Step 1: Write discovery tests**

```scala
// BspDiscoveryTest.scala
class BspDiscoveryTest extends FunSuite:

  test("recursive scan finds nested .bsp dirs") {
    // Create root .bsp/sbt.json + examples/.bsp/scalacli.json
    val results = BspDiscovery.discover(tmp)
    assertEquals(results.size, 2)
    assertEquals(results.map(_.buildToolName).toSet, Set("sbt", "scalacli"))
  }

  test("parse returns None for non-json files") {
    assertEquals(BspDiscovery.parseSingleSpec(tmp.resolve("not-json.txt")), None)
  }

  test("workspace with no .bsp dirs returns empty list") {
    assertEquals(BspDiscovery.discover(emptyTmp), Nil)
  }
```

- [ ] **Step 2: Verify tests fail** — `deder exec -t test -m core-test`
- [ ] **Step 3: Implement BspDiscovery with Tupson**

```scala
package ba.sake.basamake.bsp

import com.typesafe.scalalogging.StrictLogging
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Tupson-parsed BSP connection spec from .bsp/*.json files.
  * The `name` field is for BSP protocol display; buildToolName comes from the filename. */
private case class BspDiscoverySpec(name: String, argv: List[String]) derives ba.sake.tupson.JsonRW

object BspDiscovery extends StrictLogging:
  import ba.sake.tupson.{given, *}

  /** Autodiscover ALL .bsp/*.json files recursively under workspace root.
    * No filtering — the manager applies overrides post-discovery. */
  def discover(workspaceRoot: Path): List[BspConnectionFile] =
    val bspDirs = findBspDirs(workspaceRoot)
    if bspDirs.isEmpty then
      logger.warn(s"No .bsp directories found under $workspaceRoot")
      return Nil

    val allSpecs = bspDirs.flatMap: bspDir =>
      val jsonFiles = Files.list(bspDir)
        .filter(p => p.getFileName.toString.endsWith(".json"))
        .iterator().asScala.toList
      jsonFiles.flatMap(parseBspSpec)

    logger.info(s"Discovered ${allSpecs.size} BSP connection(s)")
    allSpecs

  /** Parse a single .bsp/*.json file. Public for the file watcher. */
  def parseSingleSpec(jsonPath: Path): Option[BspConnectionFile] =
    parseBspSpec(jsonPath)

  private def findBspDirs(root: Path): List[Path] =
    if !Files.isDirectory(root) then return Nil
    val dirs = scala.collection.mutable.ListBuffer[Path]()
    Files.walk(root).forEach: p =>
      if Files.isDirectory(p) && p.getFileName.toString == ".bsp" then dirs += p
    dirs.toList

  private def parseBspSpec(jsonPath: Path): Option[BspConnectionFile] =
    try
      val raw = Files.readString(jsonPath)
      val spec = raw.parseJson[BspDiscoverySpec]

      val bspDir = jsonPath.getParent
      val workingDir = Option(bspDir.getParent).getOrElse(Path.of("."))

      val fileName = jsonPath.getFileName.toString
      val buildToolName =
        if fileName.endsWith(".json") then fileName.dropRight(5) else fileName

      if spec.argv.isEmpty then
        logger.warn(s"No argv found in $jsonPath")
        None
      else
        logger.info(s"Discovered $buildToolName from $jsonPath: ${spec.argv.mkString(", ")}")
        Some(BspConnectionFile(
          path = jsonPath,
          argv = spec.argv,
          workingDir = workingDir,
          debounceMs = 500L,
          buildToolName = buildToolName
        ))
    catch
      case e: Exception =>
        logger.error(s"Failed to parse BSP spec from $jsonPath: ${e.getMessage}")
        None
```

Key changes from M1:
- `findBspDirs` uses `Files.walk()` for recursive scanning (was `Files.list(.bsp/)`)
- `parseBspSpec` uses `raw.parseJson[BspDiscoverySpec]` instead of `JsonParser.parseString().getAsJsonObject.getAsJsonArray()`
- `discover()` takes only `workspaceRoot` — no config parameter
- `parseSingleSpec` is public for the file watcher
- `buildToolName` extracted from filename

- [ ] **Step 4: Run tests** — `deder exec -t test -m core-test` (3 new tests pass)
- [ ] **Step 5: Commit**

---

### Task 5: Create RoutingTable

**Files:** `RoutingTable.scala` (new), `RoutingTableTest.scala` (new)

Thread-safe (`synchronized`) prefix-based routing table. Stores source directory URIs per connection. Longest-prefix wins on lookup.

```scala
package ba.sake.basamake.routing

import ba.sake.basamake.bsp.BspConnectionId

final class RoutingTable private (private var entries: Map[BspConnectionId, List[String]]):
  def update(connId: BspConnectionId, sourceDirs: List[String]): Unit = synchronized {
    entries = entries + (connId -> sourceDirs)
  }
  def remove(connId: BspConnectionId): Unit = synchronized {
    entries = entries - connId
  }
  def lookup(uri: String): Option[BspConnectionId] = synchronized {
    entries.flatMap { (connId, dirs) =>
      dirs.collect { case dir if uri.startsWith(dir) => (dir.length, connId) }
    }.maxByOption(_._1).map(_._2)
  }
  def reverseLookup(connId: BspConnectionId): Set[String] = synchronized {
    entries.get(connId).map(_.toSet).getOrElse(Set.empty)
  }

object RoutingTable:
  val empty: RoutingTable = new RoutingTable(Map.empty)
```

5 tests: longest prefix wins, more-specific beats less-specific, remove clears, reverse lookup, update overwrites.

- [ ] **Step 1: Write tests, verify they fail**
- [ ] **Step 2: Implement RoutingTable, verify tests pass**
- [ ] **Step 3: Commit**

---

### Task 6: Wire routing announcement callback

**Files:** `BspConnectionSupervisor.scala`, `BuildServerManager.scala`

After successful BSP handshake, the supervisor calls `onRoutingReady(targets, sources)` so the manager can populate the routing table with source directory URIs.

- [ ] **Step 1: Add callback parameter + extractSourceDirs helper**

```scala
// BspConnectionSupervisor.scala
def supervise(
    durable: DurableRecord,
    queue: BlockingQueue[ConnectionMessage],
    lspClient: LanguageClient,
    onRoutingReady: (List[BuildTarget], SourcesResult) => Unit
): Unit =

// In transitionToRunning, after Connected + attemptCounter = 0:
try onRoutingReady(targets, result.sources)
catch case e: Exception => logger.error("Failed to announce routing", e)

def extractSourceDirs(sources: SourcesResult): List[String] =
  sources.getItems.asScala.flatMap(i => Option(i.getSources).toList.flatMap(_.asScala))
    .collect { case si if si.getKind == SourceItemKind.DIRECTORY && !si.getGenerated => si.getUri }
    .toList
```

- [ ] **Step 2: Wire in manager's `attachConnection` + replace `route()`**

```scala
// In attachConnection:
val routingCallback = (targets: List[BuildTarget], sources: SourcesResult) =>
  val dirs = BspConnectionSupervisor.extractSourceDirs(sources)
  routingTable.update(id, dirs)
  logger.info(s"Routing updated for $id: ${dirs.size} source dirs")

// route() method:
def route(uri: String): BlockingQueue[ConnectionMessage] =
  routingTable.lookup(uri) match
    case Some(connId) => connections(connId).queue
    case None => connections.values.headOption.map(_.queue).getOrElse(
      throw IllegalStateException("No BSP connections available"))
```

- [ ] **Step 3: Compile, run tests, smoke test** — `deder exec -t test -m core-test` + `examples/hello/smoke_test.py`
- [ ] **Step 4: Commit**

---

### Task 7: Manager APIs — attach, detach, reload + override merging

**Files:** `BuildServerManager.scala`, `BuildServerManagerTest.scala` (new)

Manager gains:
1. `applyOverrides(spec)` — merges config overrides, returns `None` if disabled
2. `attachConnection(spec)` — creates durable record + queue + VT (extracted from current `initialize` logic)
3. `detachConnection(connId)` — clears diagnostics + routing + process
4. `reloadConnection(connId, newSpec)` — sends `ReloadRequested` to queue

- [ ] **Step 1: Write override merge test**

```scala
// BuildServerManagerTest.scala
test("override with enabled=false suppresses connection") {
  val config = """{"bspOverrides":[{"bspFile":".bsp/sbt.json","enabled":false}]}"""
    .parseJson[BasamakeConfig]
  val override = config.bspOverrides.find(_.bspFile == ".bsp/sbt.json")
  assert(override.exists(!_.enabled))
}

test("override merges debounceMs") {
  val spec = BspConnectionFile(Paths.get(".bsp/sbt.json"), List("sbt"), Paths.get("."), debounceMs = 500)
  val config = """{"bspOverrides":[{"bspFile":".bsp/sbt.json","debounceMs":200}]}"""
    .parseJson[BasamakeConfig]
  // applyOverrides should produce spec.copy(debounceMs = 200)
}
```

- [ ] **Step 2: Implement applyOverrides, attachConnection, detachConnection, reloadConnection**

```scala
class BuildServerManager extends StrictLogging {
  private val connections = mutable.LinkedHashMap[BspConnectionId, ConnectionContext]()
  private var client: LanguageClient = uninitialized
  private val routingTable = RoutingTable.empty
  private var workspaceRoot: Path = uninitialized
  private var config: BasamakeConfig = uninitialized
  private var watcher: BspFileWatcher = uninitialized
  private var watcherThread: Thread = uninitialized

  def initialize(workspaceRoot: Path, lspClient: LanguageClient, config: BasamakeConfig): Unit = {
    this.client = lspClient
    this.workspaceRoot = workspaceRoot
    this.config = config

    val bspFiles = BspDiscovery.discover(workspaceRoot)
    logger.info(s"Discovered ${bspFiles.size} BSP connection(s)")

    for bspFile <- bspFiles do
      applyOverrides(bspFile).foreach(attachConnection)

    // Start watcher (Task 9)
  }

  /** Apply per-.bsp-file overrides. Returns None if the connection is disabled. */
  private def applyOverrides(spec: BspConnectionFile): Option[BspConnectionFile] =
    val relPath = workspaceRoot.relativize(spec.path).toString
    config.bspOverrides.find(_.bspFile == relPath) match
      case Some(ov) if !ov.enabled =>
        logger.info(s"BSP connection $relPath is disabled by override")
        None
      case Some(ov) =>
        val merged = spec.copy(debounceMs = ov.debounceMs.getOrElse(spec.debounceMs))
        logger.debug(s"Override applied for $relPath: debounceMs=${merged.debounceMs}")
        Some(merged)
      case None =>
        Some(spec)  // no override → use as-is

  private def attachConnection(bspFile: BspConnectionFile): Unit = {
    val id = BspConnectionId(bspFile.path.toAbsolutePath.toString)
    val record = DurableRecord(
      bspFile = bspFile,
      attemptCounter = 0,
      lastKnownDiagnostics = Map.empty,
      currentState = BspConnectionState.Idle
    )
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    connections(id) = ConnectionContext(record, queue)

    val routingCallback = (targets: List[BuildTarget], sources: SourcesResult) =>
      routingTable.update(id, BspConnectionSupervisor.extractSourceDirs(sources))

    val vt = Thread.ofVirtual().start(() =>
      BspConnectionSupervisor.supervise(record, queue, client, routingCallback)
    )
    logger.info(s"Spawned supervisor for $id (${bspFile.buildToolName}) on VT ${vt.threadId()}")
  }

  private def detachConnection(connId: BspConnectionId): Unit =
    connections.get(connId) match
      case Some(ctx) =>
        logger.info(s"Detaching connection $connId")
        // Publish empty diagnostics for all its files
        for uri <- ctx.record.lastKnownDiagnostics.keys do
          client.publishDiagnostics(new PublishDiagnosticsParams(uri, java.util.Collections.emptyList()))
        // Poison pill
        ctx.record.currentState = BspConnectionState.Detached
        ctx.queue.offer(ConnectionMessage.Shutdown)
        ctx.record.lastKnownDiagnostics = Map.empty
        routingTable.remove(connId)
        connections -= connId
      case None =>
        logger.warn(s"Cannot detach unknown connection $connId")

  private def reloadConnection(connId: BspConnectionId, newSpec: BspConnectionFile): Unit =
    applyOverrides(newSpec) match
      case Some(merged) =>
        connections.get(connId) match
          case Some(ctx) =>
            logger.info(s"Requesting reload for $connId")
            ctx.queue.offer(ConnectionMessage.ReloadRequested(merged))
          case None =>
            // Connection doesn't exist yet — attach it
            attachConnection(merged)
      case None =>
        // Override now says disabled — detach if connected
        detachConnection(connId)

  def route(uri: String): BlockingQueue[ConnectionMessage] =
    routingTable.lookup(uri) match
      case Some(connId) => connections(connId).queue
      case None => connections.values.headOption.map(_.queue).getOrElse(
        throw IllegalStateException("No BSP connections available"))

  def shutdown(): Unit =
    if watcher != null then watcher.stop()
    connections.keys.toList.foreach(detachConnection)

  def killBspProcesses(): Unit =
    Thread.sleep(500); killAllBspProcesses()
    Thread.sleep(200); killAllBspProcesses()

  private def killAllBspProcesses(): Unit =
    connections.values.foreach: ctx =>
      ctx.record.bspProcess.foreach: p =>
        if p.isAlive then { p.destroyForcibly(); ctx.record.bspProcess = None }
}
```

- [ ] **Step 3: Wire config through Main → LanguageServer**

```scala
// Main.scala — config loading moves to BasamakeLanguageServer.initialize
// since workspace root is only reliably known after initialize.rootUri

// BasamakeLanguageServer.scala:
override def initialized(params: InitializedParams): Unit =
  val config = BasamakeConfig.load(workspaceRoot)
  logger.info(s"Config loaded: ${config.bspOverrides.size} override(s)")
  manager.initialize(workspaceRoot, client, config)
```

- [ ] **Step 4: Compile, run tests, smoke test** — everything still works
- [ ] **Step 5: Commit**

---

### Task 8: Create BspFileWatcher with os-lib-watch

**Files:** `BspFileWatcher.scala` (new)

Uses `os.watch.watch` (from `os-lib-watch` artifact) on a dedicated VT. The callback receives `Set[os.Path]` (already batched). We classify create/delete/modify by comparing filesystem state before vs. after each batch. 300ms debounce between batches for truncate-then-write safety.

```scala
package ba.sake.basamake.watcher

import ba.sake.basamake.bsp.{BspConnectionFile, BspDiscovery}
import com.typesafe.scalalogging.StrictLogging
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

class BspFileWatcher(
    workspaceRoot: Path,
    onAttach: BspConnectionFile => Unit,
    onDetach: java.nio.file.Path => Unit,
    onReload: BspConnectionFile => Unit
) extends StrictLogging:

  private val workspaceOsPath = os.Path(workspaceRoot.toAbsolutePath)
  @volatile private var running = true
  private val knownFiles = ConcurrentHashMap.newKeySet[Path]().asScala

  def start(): Unit =
    refreshKnownFiles()
    logger.info(s"File watcher started, watching ${knownFiles.size} .bsp/*.json file(s)")
    try
      while running do
        try os.watch.watch(Seq(workspaceOsPath), handleEvents)
        catch case _: InterruptedException => ()
        catch case e: Exception => logger.error("Watch iteration error", e)
    catch case _: InterruptedException => ()
    logger.info("File watcher stopped")

  def stop(): Unit = running = false

  private def handleEvents(changed: Set[os.Path]): Unit =
    Thread.sleep(300)  // debounce: let truncate-then-write bursts settle
    val currentFiles = findBspJsonFiles()
    val newFiles     = currentFiles.diff(knownFiles)
    val deletedFiles = knownFiles.diff(currentFiles)
    val changedPaths = changed.map(p => Paths.get(p.toString))
    val modifiedFiles = knownFiles.intersect(currentFiles).intersect(changedPaths)

    for p <- deletedFiles  do knownFiles -= p; onDetach(p)
    for p <- newFiles      do knownFiles += p; BspDiscovery.parseSingleSpec(p).foreach(onAttach)
    for p <- modifiedFiles do BspDiscovery.parseSingleSpec(p).foreach(onReload)

  private def refreshKnownFiles(): Unit =
    knownFiles.clear()
    knownFiles.addAll(findBspJsonFiles())

  private def findBspJsonFiles(): Set[Path] =
    findBspDirs(workspaceRoot).flatMap: bspDir =>
      if Files.isDirectory(bspDir) then
        Files.list(bspDir)
          .filter(p => p.getFileName.toString.endsWith(".json"))
          .iterator().asScala.map(_.toAbsolutePath).toSet
      else Set.empty[Path]
    .toSet

  private def findBspDirs(root: Path): List[Path] =
    if !Files.isDirectory(root) then return Nil
    val dirs = scala.collection.mutable.ListBuffer[Path]()
    Files.walk(root).forEach: p =>
      if Files.isDirectory(p) && p.getFileName.toString == ".bsp" then dirs += p
    dirs.toList
```

- [ ] **Step 1: Create file**
- [ ] **Step 2: Compile** — `deder exec`
- [ ] **Step 3: Commit**

---

### Task 9: Wire watcher into manager lifecycle

**Files:** `BuildServerManager.scala`

- [ ] **Step 1: Add watcher start to `initialize()`, stop to `shutdown()`**

```scala
def initialize(...): Unit =
  // ... attach discovered connections (Task 7) ...
  watcher = BspFileWatcher(
    workspaceRoot,
    onAttach = spec => applyOverrides(spec).foreach(attachConnection),
    onDetach = path => detachConnection(BspConnectionId(path.toAbsolutePath.toString)),
    onReload = spec => {
      val connId = BspConnectionId(spec.path.toAbsolutePath.toString)
      reloadConnection(connId, spec)
    }
  )
  watcherThread = Thread.ofVirtual().start(() => watcher.start())
  logger.info("File watcher started")
```

Note: `onAttach` passes through `applyOverrides` — if a newly-appeared `.json` file has `enabled: false` in config, it's silently skipped.

- [ ] **Step 2: Compile, run smoke test** — `deder exec` + `examples/hello/smoke_test.py`
- [ ] **Step 3: Commit**

---

### Task 10: Integration verification + full test suite

- [ ] **Step 1: Create multi-BSP test workspace** (`examples/multi-bsp/`)

```
examples/multi-bsp/
├── build.sbt                    # sbt root with Main.scala error
├── src/main/scala/Main.scala    # val x: Int = "oops"
├── examples/
│   ├── myscript.scala           # val y: String = 42
│   └── .bsp/scalacli.json       # Scala CLI BSP spec
└── .bsp/sbt.json                # sbt BSP spec (generated by sbt bspConfig)
```

- [ ] **Step 2: Verify routing** — Each file's diagnostics come from correct server
- [ ] **Step 3: Verify clean detach** — Delete `examples/.bsp/scalacli.json` → only its squiggles clear
- [ ] **Step 4: Verify override `enabled: false`** — Config with `{"bspOverrides":[{"bspFile":".bsp/sbt.json","enabled":false}]}` → sbt suppressed
- [ ] **Step 5: Verify `debounceMs` override** — Config with `{"bspOverrides":[{"bspFile":".bsp/sbt.json","debounceMs":100}]}` applies
- [ ] **Step 6: Verify tiebreak** — Two connections claiming overlapping dirs → longest prefix wins
- [ ] **Step 7: Run full test suite**

```bash
deder exec -t test -m core-test
```

Expected: 16 tests pass (3 diagnostics + 3 state machine + 3 discovery + 5 routing + 2 manager overrides)

- [ ] **Step 8: Run smoke test** — `examples/hello/smoke_test.py` still passes (M1 preserved)
- [ ] **Step 9: Commit**

---

## Definition of Done

- [ ] sbt root + Scala CLI subdir: both connect, correct routing
- [ ] Delete `.bsp/*.json` → clean detach, diagnostics clear, other server unaffected
- [ ] `bspOverrides` with `enabled: false` suppresses specific `.bsp` file
- [ ] `bspOverrides` with `debounceMs` merges into spec
- [ ] Overlapping source dir claims → longest matching prefix wins
- [ ] `route(uri)` uses routing table, falls back to first connection for unknown files
- [ ] File watcher VT starts/stops with manager lifecycle
- [ ] Clean detach publishes empty diagnostics for all owned files
- [ ] All 16 unit tests pass + smoke test passes
- [ ] JSON parsing uses Tupson; no Gson.`JsonParser` calls remain

## Config file reference

```jsonc
// .basamake/config.json
{
  "bspOverrides": [
    // Suppress a specific .bsp config file entirely
    { "bspFile": ".bsp/sbt.json", "enabled": false },

    // Override debounce for a specific build tool
    { "bspFile": "examples/.bsp/scalacli.json", "debounceMs": 200 },

    // Use all defaults for a file (equivalent to omitting it)
    { "bspFile": ".bsp/mill.json" }
    // ^ same as not listing it at all; shown for clarity
  ]
  // (timeouts, explicit connection list — M5)
}
```
