package ba.sake.basamake.bsp

import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import ch.epfl.scala.bsp4j.*
import munit.FunSuite

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

  private def noopSink = new BspEventSink {
    def onDiagnostics(p: PublishDiagnosticsParams): Unit = ()
    def onTargetChanged(p: DidChangeBuildTarget): Unit = ()
  }

  test("liveness check failure → killTree + respawn") {
    var spawnCount = new AtomicInteger(0)
    var killCalled = new AtomicBoolean(false)
    var firstCall = true
    val conn = BspConnection.forTesting(
      spec = fakeSpec,
      spawn = () => {
        spawnCount.incrementAndGet()
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        val server =
          if firstCall then { firstCall = false; new MockBuildServer {
            override def workspaceBuildTargets() = CompletableFuture.failedFuture(new RuntimeException("boom"))
          }}
          else new MockBuildServer
        HandshakeResult(proc, server, new WorkspaceBuildTargetsResult(java.util.Collections.emptyList()),
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTree = _ => { killCalled.set(true); () },
      eventSink = noopSink
    )

    conn.poke()  // spawn #1: alive=false → ensureConnected → return (no liveness check yet)
    conn.poke()  // alive=true → workspaceBuildTargets fails → killTree + respawn
    assert(spawnCount.get() >= 2, "respawn after a liveness failure")
    assert(killCalled.get(), "killTree called on the dead process")
    // Third poke: liveness passes (second spawn's server is fine), no further spawn.
    killCalled.set(false)
    val c = spawnCount.get()
    conn.poke()
    assertEquals(spawnCount.get(), c, "no spawn if alive")
    assert(!killCalled.get(), "no killTree if alive")
  }

  test("spawn failure → next poke triggers fresh spawn") {
    var spawnCount = new AtomicInteger(0)
    val conn = BspConnection.forTesting(
      spec = fakeSpec,
      spawn = () => {
        spawnCount.incrementAndGet()
        throw new RuntimeException("deder refuses to start")
      },
      killTree = _ => (),
      eventSink = noopSink
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
    val conn = BspConnection.forTesting(
      spec = fakeSpec,
      spawn = () => {
        spawnCount.incrementAndGet()
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        HandshakeResult(proc, new MockBuildServer,
          new WorkspaceBuildTargetsResult(java.util.Collections.emptyList()),
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTree = _ => (),
      eventSink = noopSink
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
    val conn = BspConnection.forTesting(
      spec = fakeSpec,
      spawn = () => {
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        HandshakeResult(proc, new MockBuildServer,
          new WorkspaceBuildTargetsResult(java.util.Collections.emptyList()),
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTree = _ => (),
      eventSink = noopSink
    )
    conn.poke()
    assert(conn.aliveForTesting, "alive should be true after handshake")
    conn.simulateProcessExitForTesting()
    assert(!conn.aliveForTesting, "alive should flip false after onExit callback")
  }

  test("poke during spawn returns immediately without blocking") {
    val latch = new java.util.concurrent.CountDownLatch(1)
    var spawnCount = new AtomicInteger(0)
    val conn = BspConnection.forTesting(
      spec = fakeSpec,
      spawn = () => {
        spawnCount.incrementAndGet()
        latch.await()  // block spawn until we've tested concurrent access
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        HandshakeResult(proc, new MockBuildServer,
          new WorkspaceBuildTargetsResult(java.util.Collections.emptyList()),
          new SourcesResult(java.util.Collections.emptyList()),
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTree = _ => (),
      eventSink = noopSink
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
    val conn = BspConnection.forTesting(
      spec = fakeSpec,
      spawn = () => {
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        val server = new MockBuildServer {
          override def buildTargetCompile(p: CompileParams) = {
            compileCount.incrementAndGet()
            CompletableFuture.completedFuture(new CompileResult(StatusCode.OK))
          }
        }
        HandshakeResult(proc, server,
          new WorkspaceBuildTargetsResult(java.util.Collections.emptyList()),
          sourcesResult,
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTree = _ => (),
      eventSink = noopSink
    )
    // First spawn: success (populates sourceDirsByTarget)
    conn.ensureConnected()
    conn.simulateProcessExitForTesting()
    // Simulate: spawning in progress + compile queued
    conn.setSpawningFlagForTesting(true)
    conn.compile("file:///test/Foo.scala")
    assertEquals(conn.pendingCompileTargetIdsForTesting.size, 1)
    assertEquals(conn.pendingCompileTargetIdsForTesting.head, tid)
    // Duplicate compile during same spawn — addIfAbsent prevents dup
    conn.compile("file:///test/Foo.scala")
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
    val conn = BspConnection.forTesting(
      spec = fakeSpec,
      spawn = () => {
        if (!spawnSucceed) throw new RuntimeException("spawn fail")
        val proc = new FakeProcess { override def isAlive = true; override def onExit() = CompletableFuture.completedFuture(null) }
        HandshakeResult(proc, new MockBuildServer,
          new WorkspaceBuildTargetsResult(java.util.Collections.emptyList()),
          sourcesResult,
          new DependencySourcesResult(java.util.Collections.emptyList()),
          emptyScalacOptions)
      },
      killTree = _ => (),
      eventSink = noopSink
    )
    // First spawn: success (populates sourceDirsByTarget)
    conn.ensureConnected()
    conn.simulateProcessExitForTesting()
    // Simulate: spawning in progress + compile queued
    conn.setSpawningFlagForTesting(true)
    conn.compile("file:///test/Bar.scala")
    assertEquals(conn.pendingCompileTargetIdsForTesting.size, 1)
    conn.setSpawningFlagForTesting(false)
    // Second spawn: fail → queue must be cleared
    spawnSucceed = false
    try conn.ensureConnected() catch { case _: RuntimeException => () }
    assertEquals(conn.pendingCompileTargetIdsForTesting.size, 0, "pending compiles cleared on spawn failure")
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
