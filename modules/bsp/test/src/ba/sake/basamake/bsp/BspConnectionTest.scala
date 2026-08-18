package ba.sake.basamake.bsp

import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import ch.epfl.scala.bsp4j.*
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import ba.sake.basamake.index.indexing.SemanticdbDirs
import ba.sake.tupson.{given, *}

/** Base mock BuildServer — returns completed empty futures for all methods.
  * Tests selectively override what they need. */
class MockBuildServer extends BuildServer {
  def buildInitialize(p: InitializeBuildParams) = CompletableFuture.completedFuture(new InitializeBuildResult("x", "1", "2", new BuildServerCapabilities()))
  def onBuildInitialized() = ()
  def workspaceBuildTargets() = CompletableFuture.completedFuture(new WorkspaceBuildTargetsResult(java.util.Collections.emptyList()))
  def buildShutdown() = CompletableFuture.completedFuture(null: Object)
  def onBuildExit() = ()
  def buildTargetSources(p: SourcesParams) = CompletableFuture.completedFuture(new SourcesResult(java.util.Collections.emptyList()))
  def buildTargetDependencySources(p: DependencySourcesParams) = CompletableFuture.completedFuture(new DependencySourcesResult(java.util.Collections.emptyList()))
  def buildTargetCompile(p: CompileParams) = CompletableFuture.completedFuture(new CompileResult(StatusCode.OK))
  def buildTargetInverseSources(p: InverseSourcesParams) = CompletableFuture.completedFuture(new InverseSourcesResult(java.util.Collections.emptyList()))
  def buildTargetScalacOptions(p: ScalacOptionsParams) = CompletableFuture.completedFuture(new ScalacOptionsResult(java.util.Collections.emptyList()))
  def buildTargetJavacOptions(p: JavacOptionsParams) = CompletableFuture.completedFuture(new JavacOptionsResult(java.util.Collections.emptyList()))
  def buildTargetOutputPaths(p: OutputPathsParams) = CompletableFuture.completedFuture(new OutputPathsResult(java.util.Collections.emptyList()))
  def buildTargetResources(p: ResourcesParams) = CompletableFuture.completedFuture(new ResourcesResult(java.util.Collections.emptyList()))
  def buildTargetRun(p: RunParams) = CompletableFuture.completedFuture(new RunResult(StatusCode.OK))
  def buildTargetTest(p: TestParams) = CompletableFuture.completedFuture(new TestResult(StatusCode.OK))
  def debugSessionStart(p: DebugSessionParams) = CompletableFuture.completedFuture(new DebugSessionAddress(""))
  def onRunReadStdin(p: ReadParams) = ()
  def workspaceReload() = CompletableFuture.completedFuture(null: Object)
  def buildTargetCleanCache(p: CleanCacheParams) = CompletableFuture.completedFuture(new CleanCacheResult(false))
  def buildTargetDependencyModules(p: DependencyModulesParams) = CompletableFuture.completedFuture(new DependencyModulesResult(java.util.Collections.emptyList()))
}

class BspConnectionTest extends FunSuite {

  private def emptyScalacOptions = new ScalacOptionsResult(java.util.Collections.emptyList())

  private def fakeSpec: BspConnectionSpec =
    BspConnectionSpec(content = BspDiscoveryFile("fake", List("true")), path = os.pwd, compileTimeoutSec = 2, workspaceRoot = os.pwd)

  private def noopSink = new BspEvents {
    def onDiagnostics(p: PublishDiagnosticsParams, connId: BspConnectionId): Unit = ()
  }

  test("dead process on ping → respawn (killTree skips already-dead process)") {
    var spawnCount = new AtomicInteger(0)
    var killCalled = new AtomicBoolean(false)
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        val n = spawnCount.incrementAndGet()
        val proc = new FakeProcess {
          override def isAlive = n > 1   // first spawn's process is dead
          override def onExit() = CompletableFuture.completedFuture(null)
        }
        val server = new MockBuildServer {
          override def workspaceBuildTargets() =
            if (n == 1) CompletableFuture.failedFuture(new RuntimeException("boom"))
            else CompletableFuture.completedFuture(new WorkspaceBuildTargetsResult(java.util.Collections.emptyList()))
        }
        HandshakeResult(proc, server,
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => { killCalled.set(true); () },
      events = noopSink,
      debounceMs = 500
    )

    conn.poke()  // spawn #1: alive=false → ensureConnected → return (no liveness check yet)
    conn.poke()  // alive=true but process dead → ping fails → respawn
    assert(spawnCount.get() >= 2, "respawn after a dead process")
    assert(!killCalled.get(), "no killTreeFn for an already-dead process (nothing to kill)")
    // Third poke: alive + healthy process → liveness passes, no further spawn.
    val c = spawnCount.get()
    conn.poke()
    assertEquals(spawnCount.get(), c, "no spawn if alive")
  }

  test("stream closed with alive process → killTree + respawn (real error)") {
    var spawnCount = new AtomicInteger(0)
    var killCalled = new AtomicBoolean(false)
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        val n = spawnCount.incrementAndGet()
        val proc = new FakeProcess {
          override def isAlive = true
          override def onExit() = CompletableFuture.completedFuture(null)
        }
        val server = new MockBuildServer {
          override def workspaceBuildTargets() =
            if (n == 1) CompletableFuture.failedFuture(
              new org.eclipse.lsp4j.jsonrpc.JsonRpcException(new java.io.IOException("Stream closed")))
            else CompletableFuture.completedFuture(new WorkspaceBuildTargetsResult(java.util.Collections.emptyList()))
        }
        HandshakeResult(proc, server,
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => { killCalled.set(true); () },
      events = noopSink,
      debounceMs = 500
    )

    conn.poke()  // spawn #1
    conn.poke()  // stream closed = real error → killTree + respawn
    assert(spawnCount.get() >= 2, "respawn after stream closed")
    assert(killCalled.get(), "killTree called when stream is closed")
  }

  test("ping failure with alive process → no kill, no respawn (busy server)") {
    var spawnCount = new AtomicInteger(0)
    var killCalled = new AtomicBoolean(false)
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        spawnCount.incrementAndGet()
        val proc = new FakeProcess {
          override def isAlive = true   // healthy process, just slow to answer
          override def onExit() = CompletableFuture.completedFuture(null)
        }
        val server = new MockBuildServer {
          override def workspaceBuildTargets() =
            CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException("busy compiling"))
        }
        HandshakeResult(proc, server,
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => { killCalled.set(true); () },
      events = noopSink,
      debounceMs = 500
    )

    conn.poke()  // spawn #1: alive=false → ensureConnected → return
    conn.poke()  // alive=true, ping times out, but process alive → keep connection
    assert(!killCalled.get(), "no killTree when the process is alive but busy")
    assertEquals(spawnCount.get(), 1, "no respawn when the process is alive but busy")
    assert(conn.aliveForTesting, "connection stays alive when the process is alive but busy")
  }

  test("spawn failure → next poke triggers fresh spawn") {
    var spawnCount = new AtomicInteger(0)
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        spawnCount.incrementAndGet()
        throw new RuntimeException("deder refuses to start")
      },
      killTreeFn = _ => (),
      events = noopSink,
      debounceMs = 500
    )
    // First attempt fails
    try conn.ensureConnected() catch { case _: RuntimeException => () }
    assertEquals(spawnCount.get(), 1)
    // Next attempt also tries (no cooldown, no MaxConsecutiveFails)
    try conn.ensureConnected() catch { case _: RuntimeException => () }
    assertEquals(spawnCount.get(), 2, "no cooldown — next poke is a fresh attempt")
  }

  test("successful handshake → alive → exit → respawn → alive") {
    var spawnCount = new AtomicInteger(0)
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        spawnCount.incrementAndGet()
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        HandshakeResult(proc, new MockBuildServer,
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => (),
      events = noopSink,
      debounceMs = 500
    )
    conn.poke()   // spawn #1, success
    assertEquals(spawnCount.get(), 1)
    conn.simulateProcessExitForTesting()
    conn.poke()   // !alive → respawn, success
    assertEquals(spawnCount.get(), 2)
    conn.poke()   // alive → ping OK, no respawn
    assertEquals(spawnCount.get(), 2)
  }

  test("process.onExit callback flips alive=false") {
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        HandshakeResult(proc, new MockBuildServer,
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => (),
      events = noopSink,
      debounceMs = 500
    )
    conn.poke()
    assert(conn.aliveForTesting, "alive should be true after handshake")
    conn.simulateProcessExitForTesting()
    assert(!conn.aliveForTesting, "alive should flip false after onExit callback")
  }

  test("poke during spawn returns immediately without blocking") {
    val latch = new java.util.concurrent.CountDownLatch(1)
    var spawnCount = new AtomicInteger(0)
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        spawnCount.incrementAndGet()
        latch.await()  // block spawn until we've tested concurrent access
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        HandshakeResult(proc, new MockBuildServer,
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => (),
      events = noopSink,
      debounceMs = 500
    )
    // Start spawn in background
    val t = new Thread(() => conn.poke())
    t.start()
    Thread.sleep(100)  // let spawn begin
    // poke should return immediately (spawning flag set)
    val start = System.currentTimeMillis()
    conn.poke()
    val elapsed = System.currentTimeMillis() - start
    assert(elapsed < 200, s"poke should return fast during spawn, took ${elapsed}ms")
    latch.countDown()
    t.join(5000)
    assertEquals(spawnCount.get(), 1, "only one spawn despite concurrent poke")
  }

  test("compile during spawn queues URI and runs after spawn") {
    var compileCount = new AtomicInteger(0)
    val tid = new BuildTargetIdentifier("//test")
    val sourceItem = new SourceItem("file:///test/", SourceItemKind.DIRECTORY, false)
    val sourcesResult = new SourcesResult(java.util.List.of(new SourcesItem(tid, java.util.List.of(sourceItem))))
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        val server = new MockBuildServer {
          override def buildTargetCompile(p: CompileParams) = {
            compileCount.incrementAndGet()
            CompletableFuture.completedFuture(new CompileResult(StatusCode.OK))
          }
        }
        HandshakeResult(proc, server,
          sourcesResult,
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => (),
      events = noopSink,
      debounceMs = 500
    )
    // First spawn: success (populates sourceDirsByTarget)
    conn.ensureConnected()
    conn.simulateProcessExitForTesting()
    // Simulate: spawning in progress + compile queued
    conn.setSpawningFlagForTesting(true)
    conn.requestCompile("file:///test/Foo.scala")
    assertEquals(conn.pendingCompileTargetIdsForTesting.size, 1)
    assertEquals(conn.pendingCompileTargetIdsForTesting.head, tid)
    // Duplicate compile during same spawn — addIfAbsent prevents dup
    conn.requestCompile("file:///test/Foo.scala")
    assertEquals(conn.pendingCompileTargetIdsForTesting.size, 1, "addIfAbsent deduplicates")
    // Release spawn + drain
    conn.setSpawningFlagForTesting(false)
    conn.ensureConnected()
    // After spawn + drain, compile should have been called
    assert(compileCount.get() >= 1, "queued compile was executed after spawn")
    assertEquals(conn.pendingCompileTargetIdsForTesting.size, 0, "queue drained after spawn")
  }

  test("failed spawn clears pending compiles") {
    val tid = new BuildTargetIdentifier("//bar")
    val sourceItem = new SourceItem("file:///test/", SourceItemKind.DIRECTORY, false)
    val sourcesResult = new SourcesResult(java.util.List.of(new SourcesItem(tid, java.util.List.of(sourceItem))))
    var spawnSucceed = true
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        if (!spawnSucceed) throw new RuntimeException("spawn fail")
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        HandshakeResult(proc, new MockBuildServer,
          sourcesResult,
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => (),
      events = noopSink,
      debounceMs = 500
    )
    // First spawn: success (populates sourceDirsByTarget)
    conn.ensureConnected()
    conn.simulateProcessExitForTesting()
    // Simulate: spawning in progress + compile queued
    conn.setSpawningFlagForTesting(true)
    conn.requestCompile("file:///test/Bar.scala")
    assertEquals(conn.pendingCompileTargetIdsForTesting.size, 1)
    conn.setSpawningFlagForTesting(false)
    // Second spawn: fail → queue must be cleared
    spawnSucceed = false
    try conn.ensureConnected() catch { case _: RuntimeException => () }
    assertEquals(conn.pendingCompileTargetIdsForTesting.size, 0, "pending compiles cleared on spawn failure")
  }

  // ── debounced, per-target coalesced compiles ─────────────────

  test("requestCompile debounces: N rapid pokes for the same target → exactly 1 compile") {
    var compileCount = new AtomicInteger(0)
    val tid = new BuildTargetIdentifier("//debounce")
    val sourceItem = new SourceItem("file:///test/", SourceItemKind.DIRECTORY, false)
    val sourcesResult = new SourcesResult(java.util.List.of(new SourcesItem(tid, java.util.List.of(sourceItem))))
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        val server = new MockBuildServer {
          override def buildTargetCompile(p: CompileParams) = {
            compileCount.incrementAndGet()
            CompletableFuture.completedFuture(new CompileResult(StatusCode.OK))
          }
        }
        HandshakeResult(proc, server,
          sourcesResult,
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => (),
      events = noopSink,
      debounceMs = 100
    )
    conn.ensureConnected()

    // 5 rapid pokes for the same target (e.g. didOpen + didSave + watcher batch)
    (1 to 5).foreach(_ => conn.requestCompile("file:///test/Foo.scala"))
    Thread.sleep(50) // still inside the debounce window
    assertEquals(compileCount.get(), 0, "no compile before the debounce fires")
    Thread.sleep(200) // past the window
    assertEquals(compileCount.get(), 1, "N pokes within the window → exactly one compile")
  }

  test("requestCompile during in-flight compile → exactly one follow-up, further pokes coalesce") {
    val started = new java.util.concurrent.CountDownLatch(1)
    val release = new java.util.concurrent.CountDownLatch(1)
    var compileCount = new AtomicInteger(0)
    val tid = new BuildTargetIdentifier("//inflight")
    val sourceItem = new SourceItem("file:///test/", SourceItemKind.DIRECTORY, false)
    val sourcesResult = new SourcesResult(java.util.List.of(new SourcesItem(tid, java.util.List.of(sourceItem))))
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        val server = new MockBuildServer {
          override def buildTargetCompile(p: CompileParams) = {
            compileCount.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS) // block until the test lets the compile finish
            CompletableFuture.completedFuture(new CompileResult(StatusCode.OK))
          }
        }
        HandshakeResult(proc, server,
          sourcesResult,
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => (),
      events = noopSink,
      debounceMs = 100
    )
    conn.ensureConnected()

    conn.requestCompile("file:///test/Foo.scala")
    assert(started.await(5, TimeUnit.SECONDS), "first compile should be in flight")
    // pokes while the compile runs — must collapse into ONE follow-up
    (1 to 3).foreach(_ => conn.requestCompile("file:///test/Foo.scala"))
    Thread.sleep(200) // follow-up debounce window elapses (task queued behind in-flight)
    release.countDown()

    val deadline = System.currentTimeMillis() + 5000
    while (compileCount.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assertEquals(compileCount.get(), 2, "one follow-up after the in-flight compile, not three")
  }

  test("requestCompile: distinct targets are scheduled independently") {
    var compileCount = new AtomicInteger(0)
    val tid1 = new BuildTargetIdentifier("//t1")
    val tid2 = new BuildTargetIdentifier("//t2")
    val sourceItem1 = new SourceItem("file:///a/", SourceItemKind.DIRECTORY, false)
    val sourceItem2 = new SourceItem("file:///b/", SourceItemKind.DIRECTORY, false)
    val sourcesResult = new SourcesResult(java.util.List.of(
      new SourcesItem(tid1, java.util.List.of(sourceItem1)),
      new SourcesItem(tid2, java.util.List.of(sourceItem2))))
    val conn = new BspConnection(
      spec = fakeSpec,
      spawnFn = () => {
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        val server = new MockBuildServer {
          override def buildTargetCompile(p: CompileParams) = {
            compileCount.incrementAndGet()
            CompletableFuture.completedFuture(new CompileResult(StatusCode.OK))
          }
        }
        HandshakeResult(proc, server,
          sourcesResult,
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTreeFn = _ => (),
      events = noopSink,
      debounceMs = 100
    )
    conn.ensureConnected()

    conn.requestCompile("file:///a/Foo.scala")
    conn.requestCompile("file:///b/Bar.scala")
    Thread.sleep(300)
    assertEquals(compileCount.get(), 2, "one compile per distinct target")
  }

  // ── sourceRootDirByTarget (semanticdb source-root resolution) ──

  private def scalacOptionsItem(tid: BuildTargetIdentifier, options: List[String]): ScalacOptionsItem =
    new ScalacOptionsItem(tid, options.asJava, java.util.Collections.emptyList(), "/class/dir")

  test("sourceRootDirByTarget: explicit -sourceroot flag wins over workingDir") {
    val tid = new BuildTargetIdentifier("//t")
    val result = new ScalacOptionsResult(java.util.List.of(
      scalacOptionsItem(tid, List("-sourceroot", "/flag/root", "-semanticdb-target", "/sem/out"))))
    val map = BspConnection.sourceRootDirByTarget(result, os.Path("/work/dir"))
    assertEquals(map(tid), os.Path("/flag/root"))
  }

  test("sourceRootDirByTarget: scala3 colon form -sourceroot:<dir> is recognized") {
    val tid = new BuildTargetIdentifier("//t")
    val result = new ScalacOptionsResult(java.util.List.of(
      scalacOptionsItem(tid, List("-sourceroot:/colon/root"))))
    val map = BspConnection.sourceRootDirByTarget(result, os.Path("/work/dir"))
    assertEquals(map(tid), os.Path("/colon/root"))
  }

  test("sourceRootDirByTarget: falls back to BSP workingDir when no -sourceroot flag (sbt case)") {
    val tid = new BuildTargetIdentifier("//t")
    // sbt-semanticdb passes only -Xsemanticdb + -semanticdb-target — no -sourceroot
    val result = new ScalacOptionsResult(java.util.List.of(
      scalacOptionsItem(tid, List("-Xsemanticdb", "-semanticdb-target", "/sem/out"))))
    val map = BspConnection.sourceRootDirByTarget(result, os.Path("/project/base"))
    assertEquals(map(tid), os.Path("/project/base"))
  }

  test("sourceRootDirByTarget: empty scalacOptions → empty map (no crash)") {
    val result = new ScalacOptionsResult(java.util.Collections.emptyList())
    assertEquals(BspConnection.sourceRootDirByTarget(result, os.Path("/work")), Map.empty)
  }

  test("BspConnectionSpec: default handshake timeout is 120s (matches BasamakeConfig docs)") {
    assertEquals(fakeSpec.handshakeTimeoutSec, 120L)
  }

  test("handshake → index catch-up: all targets with semanticdb dirs reach the index once") {
    val root = os.temp.dir(prefix = "bsp-catchup")
    val spec = BspConnectionSpec(
      content = BspDiscoveryFile("fake", List("true")),
      path = root / ".bsp/fake.json",
      compileTimeoutSec = 2,
      workspaceRoot = root
    )
    val tid1 = new BuildTargetIdentifier("//m1")
    val tid2 = new BuildTargetIdentifier("//m2")
    val captured = new java.util.concurrent.CopyOnWriteArrayList[List[SemanticdbDirs]]()
    val sink = new BspEvents {
      def onDiagnostics(p: PublishDiagnosticsParams, connId: BspConnectionId): Unit = ()
      override def onAfterCompile(roots: List[SemanticdbDirs]): Unit = captured.add(roots)
    }
    val opts = new ScalacOptionsResult(java.util.List.of(
      new ScalacOptionsItem(tid1, List("-sourceroot", "/flag/root", "-semanticdb-target", "/sem/out1").asJava,
        java.util.Collections.emptyList(), "file:///class/dir1"),
      new ScalacOptionsItem(tid2, List("-sourceroot", "/flag/root", "-semanticdb-target", "/sem/out2").asJava,
        java.util.Collections.emptyList(), "file:///class/dir2")
    ))
    val conn = new BspConnection(
      spec = spec,
      spawnFn = () => {
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        HandshakeResult(proc, new MockBuildServer,
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          opts)
      },
      killTreeFn = _ => (),
      events = sink,
      debounceMs = 500
    )

    conn.ensureConnected()

    assert(captured.size() >= 1, "index catch-up invalidate must fire right after handshake")
    val allRoots = captured.asScala.flatMap(_.iterator).toSet
    assertEquals(allRoots, Set(
      SemanticdbDirs(os.Path("/flag/root"), os.Path("/sem/out1")),
      SemanticdbDirs(os.Path("/flag/root"), os.Path("/sem/out2"))
    ))
    // data.json persisted at connect time (warm start for the next session)
    val dataFiles = os.walk(root / ".basamake/bsp").filter(_.last == "data.json")
    assert(dataFiles.nonEmpty, "target data must be persisted right after handshake")
  }

  test("extractTargetDependencySources converts URIs to paths") {
    val tid1 = new BuildTargetIdentifier("//m1")
    val tid2 = new BuildTargetIdentifier("//m2")
    val result = new DependencySourcesResult(java.util.List.of(
      new DependencySourcesItem(tid1, java.util.List.of(
        "file:///home/user/.cache/coursier/foo/foo-1.0-sources.jar",
        "jar:file:///home/user/.cache/coursier/bar/bar-2.0-sources.jar!/META-INF/anything"
      )),
      new DependencySourcesItem(tid2, java.util.List.of(
        "https://example.com/not-a-file",
        "garbage"
      ))
    ))

    val map = BspConnection.extractTargetDependencySources(result)
    assertEquals(map(tid1), List(
      os.Path("/home/user/.cache/coursier/foo/foo-1.0-sources.jar"),
      os.Path("/home/user/.cache/coursier/bar/bar-2.0-sources.jar")
    ))
    assertEquals(map(tid2), Nil, "non-file URIs must be skipped")
  }

  test("dependency sources: data.json persistence + onDependencySources after handshake") {
    val root = os.temp.dir(prefix = "bsp-dep-src-test-")
    try {
      val spec = BspConnectionSpec(
        content = BspDiscoveryFile("fake", List("true")),
        path = root / ".bsp/fake.json",
        compileTimeoutSec = 2,
        workspaceRoot = root
      )
      val tid1 = new BuildTargetIdentifier("//m1")
      val tid2 = new BuildTargetIdentifier("//m2")
      val capturedRoots = new java.util.concurrent.CopyOnWriteArrayList[List[SemanticdbDirs]]()
      val capturedDeps = new java.util.concurrent.CopyOnWriteArrayList[Map[BuildTargetIdentifier, List[os.Path]]]()
      val sink = new BspEvents {
        def onDiagnostics(p: PublishDiagnosticsParams, connId: BspConnectionId): Unit = ()
        override def onAfterCompile(roots: List[SemanticdbDirs]): Unit = capturedRoots.add(roots)
        override def onDependencySources(depsByTarget: Map[BuildTargetIdentifier, List[os.Path]]): Unit = capturedDeps.add(depsByTarget)
      }
      val opts = new ScalacOptionsResult(java.util.List.of(
        new ScalacOptionsItem(tid1, List("-sourceroot", "/flag/root", "-semanticdb-target", "/sem/out1").asJava,
          java.util.Collections.emptyList(), "file:///class/dir1"),
        new ScalacOptionsItem(tid2, List("-sourceroot", "/flag/root", "-semanticdb-target", "/sem/out2").asJava,
          java.util.Collections.emptyList(), "file:///class/dir2")
      ))
      val depSources = new DependencySourcesResult(java.util.List.of(
        new DependencySourcesItem(tid1, java.util.List.of(
          "file:///cache/lib1-1.0-sources.jar", "file:///cache/lib2-2.0-sources.jar")),
        new DependencySourcesItem(tid2, java.util.List.of(
          "file:///cache/lib2-2.0-sources.jar"))
      ))
      val conn = new BspConnection(
        spec = spec,
        spawnFn = () => {
          val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
          HandshakeResult(proc, new MockBuildServer,
            new SourcesResult(java.util.Collections.emptyList()),
            depSources,
            opts)
        },
        killTreeFn = _ => (),
        events = sink,
        debounceMs = 500
      )

      conn.ensureConnected()

      assert(capturedRoots.size() >= 1, "index catch-up invalidate must fire right after handshake")
      val allRoots = capturedRoots.asScala.flatMap(_.iterator).toSet
      assertEquals(allRoots, Set(
        SemanticdbDirs(os.Path("/flag/root"), os.Path("/sem/out1")),
        SemanticdbDirs(os.Path("/flag/root"), os.Path("/sem/out2"))
      ))
      // onDependencySources must fire once with per-target paths (not flattened)
      assert(capturedDeps.size() >= 1, "onDependencySources must fire right after handshake")
      val allDeps = capturedDeps.asScala.flatMap(_.values).flatten.toSet
      assertEquals(allDeps, Set(
        os.Path("/cache/lib1-1.0-sources.jar"),
        os.Path("/cache/lib2-2.0-sources.jar")
      ))
      assertEquals(capturedDeps.asScala.head(tid1), List(
        os.Path("/cache/lib1-1.0-sources.jar"),
        os.Path("/cache/lib2-2.0-sources.jar")
      ), "sink must receive the per-target map")
      // data.json persisted at connect time (warm start for the next session)
      val dataFiles = os.walk(root / ".basamake/bsp").filter(_.last == "data.json")
      assert(dataFiles.nonEmpty, "target data must be persisted right after handshake")
      val data = os.read(dataFiles.head).parseJson[BspTargetData]
      val tid1Info = data.targets.find(_.id == "//m1").get
      assertEquals(tid1Info.dependencySources, List("/cache/lib1-1.0-sources.jar", "/cache/lib2-2.0-sources.jar"))
    } finally os.remove.all(root)
  }

  test("mergeDeps: empty fresh never replaces non-empty old, non-empty fresh wins") {
    val tid = new BuildTargetIdentifier("//m")
    val tid2 = new BuildTargetIdentifier("//m2")
    val oldDeps = Map(tid -> List(os.Path("/cache/lib1.jar")))

    assertEquals(BspConnection.mergeDeps(oldDeps, Map(tid -> Nil)), oldDeps, "empty fresh must keep old")
    assertEquals(BspConnection.mergeDeps(oldDeps, Map.empty), oldDeps, "absent fresh must keep old")
    assertEquals(BspConnection.mergeDeps(oldDeps, Map(tid2 -> Nil)), oldDeps, "empty fresh for a new target must not appear")
    val fresh = List(os.Path("/cache/lib2.jar"))
    assertEquals(BspConnection.mergeDeps(oldDeps, Map(tid -> fresh)), Map(tid -> fresh), "non-empty fresh wins")
  }

  test("changedTargetIds excludes DELETED events") {
    val t1 = new BuildTargetIdentifier("//a")
    val t2 = new BuildTargetIdentifier("//b")
    val t3 = new BuildTargetIdentifier("//c")
    def event(tid: BuildTargetIdentifier, kind: BuildTargetEventKind): BuildTargetEvent = {
      val e = new BuildTargetEvent(tid)
      e.setKind(kind)
      e
    }
    val params = new DidChangeBuildTarget(java.util.List.of(
      event(t1, BuildTargetEventKind.CHANGED),
      event(t2, BuildTargetEventKind.CREATED),
      event(t3, BuildTargetEventKind.DELETED)
    ))
    assertEquals(BspConnection.changedTargetIds(params), List(t1, t2))
    assertEquals(BspConnection.changedTargetIds(new DidChangeBuildTarget(java.util.Collections.emptyList())), Nil)
  }

  test("handshake with empty dependencySources keeps persisted deps (never-accept-empty)") {
    val root = os.temp.dir(prefix = "bsp-merge-test-")
    try {
      val spec = BspConnectionSpec(
        content = BspDiscoveryFile("fake", List("true")),
        path = root / ".bsp/fake.json",
        compileTimeoutSec = 2,
        workspaceRoot = root
      )
      val tid = new BuildTargetIdentifier("//m1")
      // pre-write data.json with deps (as a previous session would have)
      val dataDir = root / ".basamake/bsp" / BspConnectionSpec.dirName(spec)
      os.makeDir.all(dataDir)
      val persisted = BspTargetData(
        bspFile = ".bsp/fake.json",
        targets = List(BspTargetInfo(
          id = tid.getUri, sourceRootDir = root, semanticdbDir = root / "sem",
          dependencySources = List("/cache/lib1-1.0-sources.jar")
        ))
      )
      os.write.over(dataDir / "data.json", ba.sake.tupson.toJson(persisted))

      val capturedDeps = new java.util.concurrent.CopyOnWriteArrayList[Map[BuildTargetIdentifier, List[os.Path]]]()
      val sink = new BspEvents {
        def onDiagnostics(p: PublishDiagnosticsParams, connId: BspConnectionId): Unit = ()
        override def onDependencySources(depsByTarget: Map[BuildTargetIdentifier, List[os.Path]]): Unit = capturedDeps.add(depsByTarget)
      }
      val conn = new BspConnection(
        spec = spec,
        spawnFn = () => {
          val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
          HandshakeResult(proc, new MockBuildServer,
            new SourcesResult(java.util.Collections.emptyList()),
            new DependencySourcesResult(java.util.Collections.emptyList()), // server returns EMPTY deps
            new ScalacOptionsResult(java.util.Collections.emptyList()))
        },
        killTreeFn = _ => (),
        events = sink,
        debounceMs = 500
      )

      conn.ensureConnected()

      assert(capturedDeps.size() >= 1, "sink must fire at handshake with the merged map")
      val merged = capturedDeps.asScala.head
      assertEquals(merged(tid), List(os.Path("/cache/lib1-1.0-sources.jar")),
        "persisted deps must survive an empty handshake result")
    } finally os.remove.all(root)
  }

  test("refreshDependencySources: non-empty result updates map, re-fires sink, persists data.json") {
    val root = os.temp.dir(prefix = "bsp-refresh-test-")
    try {
      val spec = BspConnectionSpec(
        content = BspDiscoveryFile("fake", List("true")),
        path = root / ".bsp/fake.json",
        compileTimeoutSec = 2,
        workspaceRoot = root
      )
      val tid = new BuildTargetIdentifier("//m1")
      val capturedDeps = new java.util.concurrent.CopyOnWriteArrayList[Map[BuildTargetIdentifier, List[os.Path]]]()
      val sink = new BspEvents {
        def onDiagnostics(p: PublishDiagnosticsParams, connId: BspConnectionId): Unit = ()
        override def onDependencySources(depsByTarget: Map[BuildTargetIdentifier, List[os.Path]]): Unit = capturedDeps.add(depsByTarget)
      }
      // handshake result is pre-built (empty deps); the mock server is only
      // consulted by refreshDependencySources — returns POPULATED deps there
      val server = new MockBuildServer {
        override def buildTargetDependencySources(p: DependencySourcesParams): CompletableFuture[DependencySourcesResult] =
          CompletableFuture.completedFuture(new DependencySourcesResult(java.util.List.of(
            new DependencySourcesItem(tid, java.util.List.of("file:///cache/lib9-1.0-sources.jar")))))
      }
      val conn = new BspConnection(
        spec = spec,
        spawnFn = () => {
          val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
          HandshakeResult(proc, server,
            new SourcesResult(java.util.Collections.emptyList()),
            new DependencySourcesResult(java.util.Collections.emptyList()),
            new ScalacOptionsResult(java.util.Collections.emptyList()))
        },
        killTreeFn = _ => (),
        events = sink,
        debounceMs = 500
      )

      conn.ensureConnected()
      assertEquals(capturedDeps.size(), 0, "empty handshake result must NOT fire the sink")

      conn.refreshDependencySources(List(tid))

      assertEquals(capturedDeps.size(), 1, "refresh must re-fire the sink with the merged map")
      assertEquals(capturedDeps.asScala.last(tid), List(os.Path("/cache/lib9-1.0-sources.jar")))
      val dataFiles = os.walk(root / ".basamake/bsp").filter(_.last == "data.json")
      assert(dataFiles.nonEmpty, "data.json must be persisted after refresh")
      val data = os.read(dataFiles.head).parseJson[BspTargetData]
      assertEquals(data.targets.find(_.id == "//m1").get.dependencySources, List("/cache/lib9-1.0-sources.jar"))
    } finally os.remove.all(root)
  }

  test("refreshDependencySources with empty result keeps existing deps (no sink re-fire)") {
    val root = os.temp.dir(prefix = "bsp-refresh-empty-test-")
    try {
      val spec = BspConnectionSpec(
        content = BspDiscoveryFile("fake", List("true")),
        path = root / ".bsp/fake.json",
        compileTimeoutSec = 2,
        workspaceRoot = root
      )
      val tid = new BuildTargetIdentifier("//m1")
      val capturedDeps = new java.util.concurrent.CopyOnWriteArrayList[Map[BuildTargetIdentifier, List[os.Path]]]()
      val sink = new BspEvents {
        def onDiagnostics(p: PublishDiagnosticsParams, connId: BspConnectionId): Unit = ()
        override def onDependencySources(depsByTarget: Map[BuildTargetIdentifier, List[os.Path]]): Unit = capturedDeps.add(depsByTarget)
      }
      // handshake result is pre-built with POPULATED deps; the mock server is only
      // consulted by refreshDependencySources — returns EMPTY there
      val server = new MockBuildServer {
        override def buildTargetDependencySources(p: DependencySourcesParams): CompletableFuture[DependencySourcesResult] =
          CompletableFuture.completedFuture(new DependencySourcesResult(java.util.Collections.emptyList()))
      }
      val conn = new BspConnection(
        spec = spec,
        spawnFn = () => {
          val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
          HandshakeResult(proc, server,
            new SourcesResult(java.util.Collections.emptyList()),
            new DependencySourcesResult(java.util.List.of(
              new DependencySourcesItem(tid, java.util.List.of("file:///cache/lib5-1.0-sources.jar")))),
            new ScalacOptionsResult(java.util.Collections.emptyList()))
        },
        killTreeFn = _ => (),
        events = sink,
        debounceMs = 500
      )

      conn.ensureConnected()
      assertEquals(capturedDeps.size(), 1)
      assertEquals(capturedDeps.asScala.last(tid), List(os.Path("/cache/lib5-1.0-sources.jar")))

      // refresh returns EMPTY → nothing changes, no sink re-fire
      conn.refreshDependencySources(List(tid))

      assertEquals(capturedDeps.size(), 1, "empty refresh must not re-fire the sink")
      assertEquals(capturedDeps.asScala.last(tid), List(os.Path("/cache/lib5-1.0-sources.jar")),
        "known deps must survive an empty refresh")
    } finally os.remove.all(root)
  }
}

/** Minimal FakeProcess — only the methods BspConnection touches. */
abstract class FakeProcess extends java.lang.Process {
  override def getOutputStream = null
  override def getInputStream = null
  override def getErrorStream = null
  override def waitFor() = 0
  override def waitFor(t: Long, u: TimeUnit) = true
  override def exitValue() = 0
  override def destroy() = ()
  override def destroyForcibly(): java.lang.Process = this
  override def isAlive = false
  override def pid() = 99999L
  override def info() = new java.lang.ProcessHandle.Info {
    override def command() = None.orNull
    override def commandLine() = None.orNull
    override def arguments() = java.util.Optional.empty[Array[String]]
    override def startInstant() = None.orNull
    override def totalCpuDuration() = None.orNull
    override def user() = None.orNull
  }
}
