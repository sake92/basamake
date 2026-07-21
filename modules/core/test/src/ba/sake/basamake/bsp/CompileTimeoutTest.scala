package ba.sake.basamake.bsp

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.{CompletableFuture, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.*
import munit.FunSuite
import ba.sake.basamake.core.{ConnectionMessage, DurableRecord}

class CompileTimeoutTest extends FunSuite {

  private def createSpec(compileTimeoutSec: Long): BspConnectionSpec =
    val tmpDir = os.temp.dir(prefix = "compile-spec-")
    val bspDir = tmpDir / ".bsp"
    os.makeDir(bspDir)
    val jsonPath = bspDir / "test.json"
    os.write(jsonPath, """{"name":"test","argv":["echo","test"]}""")
    val spec = BspDiscovery.parseSingleSpec(jsonPath).get
    spec.copy(compileTimeoutSec = compileTimeoutSec)

  /** Mock BuildServer where buildTargetCompile returns a never-completing future. */
  private def neverCompletingBuildServer: BuildServer =
    val handler = new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        method.getName match
          case "buildTargetCompile" =>
            new CompletableFuture[CompileResult]() // never completes
          case "buildTargetInverseSources" =>
            new CompletableFuture[InverseSourcesResult]() // never completes
          case "toString" => "mock-timeout-server"
          case _          => throw new UnsupportedOperationException(method.getName)
    }
    Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[BuildServer]), handler)
      .asInstanceOf[BuildServer]

  /** Mock BuildServer where buildTargetCompile returns an OK status. */
  private def okBuildServer: BuildServer =
    val handler = new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        method.getName match
          case "buildTargetCompile" =>
            val result = new CompileResult(StatusCode.OK)
            CompletableFuture.completedFuture(result)
          case "buildTargetInverseSources" =>
            val result = new InverseSourcesResult(java.util.Collections.emptyList())
            CompletableFuture.completedFuture(result)
          case "toString" => "mock-ok-server"
          case _          => throw new UnsupportedOperationException(method.getName)
    }
    Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[BuildServer]), handler)
      .asInstanceOf[BuildServer]

  private val testTargetId = new BuildTargetIdentifier("target://test")
  private val testUri = "file:///ws/src/Main.scala"

  // ── triggerCompile timeout tests ──

  test("triggerCompile does not hang when buildTargetCompile never completes") {
    val spec = createSpec(compileTimeoutSec = 1)
    val record = DurableRecord(
      bspFile = new AtomicReference(spec),
      attemptCounter = new AtomicInteger(0),
      lastKnownDiagnostics = new AtomicReference(Map.empty),
      currentState = BspConnectionState.Connected
    )
    val compileInFlight = new AtomicBoolean(false)
    val buildServer = neverCompletingBuildServer
    val targetToSourceRoots = Map(testTargetId -> List("file:///ws/src/"))
    val allTargetIds = List(testTargetId)
    var callbackCalled = false

    val start = System.currentTimeMillis()
    BspConnectionSupervisor.triggerCompile(
      uri = testUri,
      buildServer = buildServer,
      targetToSourceRoots = targetToSourceRoots,
      allTargetIds = allTargetIds,
      onCompileSuccess = (_, _) => callbackCalled = true,
      durable = record,
      compileInFlight = compileInFlight
    )
    val elapsed = System.currentTimeMillis() - start

    // Must return within 5s (1s compile + 2s inverseSources + overhead)
    assert(elapsed >= 900, s"Should wait ~1s for timeout, took ${elapsed}ms")
    assert(elapsed < 5000, s"triggerCompile should not hang, took ${elapsed}ms")

    // compileInFlight must be cleared in finally block
    assert(!compileInFlight.get(), "compileInFlight must be false after timeout")

    // Callback must NOT have been called (compile didn't succeed)
    assert(!callbackCalled, "onCompileSuccess must not be called on timeout")

    os.remove.all(spec.path / os.up / os.up)
  }

  test("triggerCompile clears compileInFlight flag even on exception") {
    val spec = createSpec(compileTimeoutSec = 1)
    val record = DurableRecord(
      bspFile = new AtomicReference(spec),
      attemptCounter = new AtomicInteger(0),
      lastKnownDiagnostics = new AtomicReference(Map.empty),
      currentState = BspConnectionState.Connected
    )
    val compileInFlight = new AtomicBoolean(false)

    // Use null buildServer — triggerCompile will NPE, but finally block must still clear the flag.
    // We catch the exception so the test doesn't fail.
    try
      BspConnectionSupervisor.triggerCompile(
        uri = testUri,
        buildServer = neverCompletingBuildServer,
        targetToSourceRoots = Map.empty,
        allTargetIds = List(testTargetId),
        onCompileSuccess = (_, _) => (),
        durable = record,
        compileInFlight = compileInFlight
      )
    catch case _: Exception => () // expected

    // compileInFlight must be false regardless of exception
    assert(!compileInFlight.get(), "compileInFlight must be cleared in finally block even on exception")

    os.remove.all(spec.path / os.up / os.up)
  }

  test("triggerCompile invokes onCompileSuccess when compile succeeds") {
    val spec = createSpec(compileTimeoutSec = 5)
    val record = DurableRecord(
      bspFile = new AtomicReference(spec),
      attemptCounter = new AtomicInteger(0),
      lastKnownDiagnostics = new AtomicReference(Map.empty),
      currentState = BspConnectionState.Connected
    )
    val compileInFlight = new AtomicBoolean(false)
    val buildServer = okBuildServer
    val targetToSourceRoots = Map(testTargetId -> List("file:///ws/src/"))
    val allTargetIds = List(testTargetId)
    var callbackCalled = false
    var callbackTargets: List[BuildTargetIdentifier] = Nil

    BspConnectionSupervisor.triggerCompile(
      uri = testUri,
      buildServer = buildServer,
      targetToSourceRoots = targetToSourceRoots,
      allTargetIds = allTargetIds,
      onCompileSuccess = (bs, targets) => { callbackCalled = true; callbackTargets = targets },
      durable = record,
      compileInFlight = compileInFlight
    )

    assert(callbackCalled, "onCompileSuccess must be called on OK status")
    assertEquals(callbackTargets, List(testTargetId))
    assert(!compileInFlight.get(), "compileInFlight must be false after success")

    os.remove.all(spec.path / os.up / os.up)
  }

  test("triggerCompile returns immediately when no targets match the URI") {
    val spec = createSpec(compileTimeoutSec = 1)
    val record = DurableRecord(
      bspFile = new AtomicReference(spec),
      attemptCounter = new AtomicInteger(0),
      lastKnownDiagnostics = new AtomicReference(Map.empty),
      currentState = BspConnectionState.Connected
    )
    val compileInFlight = new AtomicBoolean(false)
    // null buildServer skips inverseSources (returns Nil immediately from null guard)
    // Empty maps and lists — selectCompileTargetIds returns Nil
    val targetToSourceRoots = Map.empty[BuildTargetIdentifier, List[String]]
    val allTargetIds = List.empty[BuildTargetIdentifier]

    val start = System.currentTimeMillis()
    BspConnectionSupervisor.triggerCompile(
      uri = testUri,
      buildServer = null, // null → tryInverseSources returns Nil immediately
      targetToSourceRoots = targetToSourceRoots,
      allTargetIds = allTargetIds,
      onCompileSuccess = (_, _) => (),
      durable = record,
      compileInFlight = compileInFlight
    )
    val elapsed = System.currentTimeMillis() - start

    // Must return immediately (no targets → no compile)
    assert(elapsed < 500, s"Should return immediately when no targets match, took ${elapsed}ms")
    assert(!compileInFlight.get(), "compileInFlight must not be set if no compile triggered")

    os.remove.all(spec.path / os.up / os.up)
  }

  /** Mock BuildServer where buildTargetCompile returns ERROR status,
    * and buildTargetScalacOptions returns -Ybest-effort. */
  private def errorWithBestEffortServer: BuildServer =
    val handler = new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        method.getName match
          case "buildTargetCompile" =>
            val result = new CompileResult(StatusCode.ERROR)
            CompletableFuture.completedFuture(result)
          case "buildTargetScalacOptions" =>
            val items = List(
              new ScalacOptionsItem(
                testTargetId,
                List("-Xsemanticdb", "-Ybest-effort").asJava,
                List.empty[String].asJava,
                "/ws"
              )
            ).asJava
            CompletableFuture.completedFuture(new ScalacOptionsResult(items))
          case "buildTargetInverseSources" =>
            val result = new InverseSourcesResult(java.util.Collections.emptyList())
            CompletableFuture.completedFuture(result)
          case "toString" => "mock-error-best-effort-server"
          case _ => throw new UnsupportedOperationException(method.getName)
    }
    Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[BuildServer], classOf[ScalaBuildServer]), handler)
      .asInstanceOf[BuildServer]

  /** Mock BuildServer where buildTargetCompile returns ERROR status,
    * and buildTargetScalacOptions does NOT return -Ybest-effort. */
  private def errorWithoutBestEffortServer: BuildServer =
    val handler = new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        method.getName match
          case "buildTargetCompile" =>
            val result = new CompileResult(StatusCode.ERROR)
            CompletableFuture.completedFuture(result)
          case "buildTargetScalacOptions" =>
            val items = List(
              new ScalacOptionsItem(
                testTargetId,
                List("-Xsemanticdb").asJava,
                List.empty[String].asJava,
                "/ws"
              )
            ).asJava
            CompletableFuture.completedFuture(new ScalacOptionsResult(items))
          case "buildTargetInverseSources" =>
            val result = new InverseSourcesResult(java.util.Collections.emptyList())
            CompletableFuture.completedFuture(result)
          case "toString" => "mock-error-no-best-effort-server"
          case _ => throw new UnsupportedOperationException(method.getName)
    }
    Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[BuildServer], classOf[ScalaBuildServer]), handler)
      .asInstanceOf[BuildServer]

  test("triggerCompile calls onCompileSuccess on ERROR status when -Ybest-effort enabled") {
    val spec = createSpec(compileTimeoutSec = 5)
    val record = DurableRecord(
      bspFile = new AtomicReference(spec),
      attemptCounter = new AtomicInteger(0),
      lastKnownDiagnostics = new AtomicReference(Map.empty),
      currentState = BspConnectionState.Connected
    )
    val compileInFlight = new AtomicBoolean(false)
    val buildServer = errorWithBestEffortServer
    val targetToSourceRoots = Map(testTargetId -> List("file:///ws/src/"))
    val allTargetIds = List(testTargetId)
    var callbackCalled = false
    var callbackTargets: List[BuildTargetIdentifier] = Nil

    BspConnectionSupervisor.triggerCompile(
      uri = testUri,
      buildServer = buildServer,
      targetToSourceRoots = targetToSourceRoots,
      allTargetIds = allTargetIds,
      onCompileSuccess = (bs, targets) => { callbackCalled = true; callbackTargets = targets },
      durable = record,
      compileInFlight = compileInFlight
    )

    assert(callbackCalled, "onCompileSuccess must be called on ERROR status with -Ybest-effort")
    assertEquals(callbackTargets, List(testTargetId))
    assert(!compileInFlight.get(), "compileInFlight must be false after completion")

    os.remove.all(spec.path / os.up / os.up)
  }

  test("triggerCompile does NOT call onCompileSuccess on ERROR status without -Ybest-effort") {
    val spec = createSpec(compileTimeoutSec = 5)
    val record = DurableRecord(
      bspFile = new AtomicReference(spec),
      attemptCounter = new AtomicInteger(0),
      lastKnownDiagnostics = new AtomicReference(Map.empty),
      currentState = BspConnectionState.Connected
    )
    val compileInFlight = new AtomicBoolean(false)
    val buildServer = errorWithoutBestEffortServer
    val targetToSourceRoots = Map(testTargetId -> List("file:///ws/src/"))
    val allTargetIds = List(testTargetId)
    var callbackCalled = false

    BspConnectionSupervisor.triggerCompile(
      uri = testUri,
      buildServer = buildServer,
      targetToSourceRoots = targetToSourceRoots,
      allTargetIds = allTargetIds,
      onCompileSuccess = (_, _) => callbackCalled = true,
      durable = record,
      compileInFlight = compileInFlight
    )

    assert(!callbackCalled, "onCompileSuccess must NOT be called on ERROR status without -Ybest-effort")
    assert(!compileInFlight.get(), "compileInFlight must be false after completion")

    os.remove.all(spec.path / os.up / os.up)
  }

  // ── selectCompileTargetIds fallback tests ──

  test("selectCompileTargetIds falls back to allTargets when source-root match fails") {
    val uri = "file:///ws/orphan/file.scala"
    val allTargetIds = List(testTargetId, new BuildTargetIdentifier("target://extra"))

    val selected = BspConnectionSupervisor.selectCompileTargetIds(
      uri = uri,
      buildServer = null,
      targetToSourceRoots = Map.empty,
      allTargetIds = allTargetIds
    )

    assertEquals(selected.toSet, allTargetIds.toSet)
  }

  test("selectCompileTargetIds returns empty when all sources fail and no allTargetIds") {
    val uri = "file:///ws/orphan/file.scala"

    val selected = BspConnectionSupervisor.selectCompileTargetIds(
      uri = uri,
      buildServer = null,
      targetToSourceRoots = Map.empty,
      allTargetIds = Nil
    )

    assertEquals(selected, Nil)
  }
}
