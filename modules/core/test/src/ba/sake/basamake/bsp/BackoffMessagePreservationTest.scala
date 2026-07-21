package ba.sake.basamake.bsp

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import munit.FunSuite
import ba.sake.basamake.core.{ConnectionMessage, DurableRecord}

class BackoffMessagePreservationTest extends FunSuite {

  private def createSpec(compileTimeoutSec: Long = 600): BspConnectionSpec =
    val tmpDir = os.temp.dir(prefix = "backoff-spec-")
    val bspDir = tmpDir / ".bsp"
    os.makeDir(bspDir)
    val jsonPath = bspDir / "test.json"
    os.write(jsonPath, """{"name":"test","argv":["echo","test"]}""")
    val spec = BspDiscovery.parseSingleSpec(jsonPath).get
    spec.copy(compileTimeoutSec = compileTimeoutSec)

  private def freshRecord(spec: BspConnectionSpec, state: BspConnectionState, attempts: Int): DurableRecord =
    DurableRecord(
      bspFile = new AtomicReference(spec),
      attemptCounter = new AtomicInteger(attempts),
      lastKnownDiagnostics = new AtomicReference(Map.empty),
      currentState = state
    )

  private def didSave(uri: String = "file:///ws/Main.scala"): ConnectionMessage =
    val textDoc = new org.eclipse.lsp4j.TextDocumentIdentifier(uri)
    val params = new org.eclipse.lsp4j.DidSaveTextDocumentParams(textDoc)
    ConnectionMessage.DidSave(params)

  // ── backoffSleep tests ──

  test("backoffSleep re-offers non-control messages and transitions to Spawning") {
    val spec = createSpec()
    val record = freshRecord(spec, BspConnectionState.BackoffWait, attempts = 1)
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    val msg = didSave()
    queue.offer(msg)

    BspConnectionSupervisor.backoffSleep(record, queue)

    // Message must be re-offered to queue (not dropped)
    val reOffered = queue.poll()
    assert(reOffered != null, "DidSave message must be re-offered, not dropped")
    assert(reOffered.isInstanceOf[ConnectionMessage.DidSave], s"Expected DidSave, got ${reOffered.getClass.getSimpleName}")

    // State must transition from BackoffWait to Spawning
    assertEquals(record.currentState, BspConnectionState.Spawning)

    os.remove.all(spec.path / os.up / os.up)
  }

  test("backoffSleep with Shutdown message transitions to Detached without re-offer") {
    val spec = createSpec()
    val record = freshRecord(spec, BspConnectionState.BackoffWait, attempts = 2)
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    queue.offer(ConnectionMessage.Shutdown)

    BspConnectionSupervisor.backoffSleep(record, queue)

    assertEquals(record.currentState, BspConnectionState.Detached)
    // Shutdown is NOT re-offered (it's consumed, not re-queued)
    assertEquals(queue.poll(), null, "Shutdown should not be re-offered")

    os.remove.all(spec.path / os.up / os.up)
  }

  test("backoffSleep with ReloadRequested updates spec and transitions to Reloading") {
    val spec = createSpec()
    val record = freshRecord(spec, BspConnectionState.BackoffWait, attempts = 1)
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    val newSpec = spec.copy(debounceMs = 999L)
    queue.offer(ConnectionMessage.ReloadRequested(newSpec))

    BspConnectionSupervisor.backoffSleep(record, queue)

    assertEquals(record.currentState, BspConnectionState.Reloading)
    assertEquals(record.bspFile.get().debounceMs, 999L, "bspFile must be updated to new spec")

    os.remove.all(spec.path / os.up / os.up)
  }

  test("backoffSleep with empty queue blocks for delay then transitions to Spawning") {
    val spec = createSpec()
    // attemptCounter = 1 → delayMs = 1000ms (2^(1-1) * 1000 = 1000)
    val record = freshRecord(spec, BspConnectionState.BackoffWait, attempts = 1)
    val queue = new LinkedBlockingQueue[ConnectionMessage]()

    val start = System.currentTimeMillis()
    BspConnectionSupervisor.backoffSleep(record, queue)
    val elapsed = System.currentTimeMillis() - start

    assertEquals(record.currentState, BspConnectionState.Spawning)
    // Should block for ~1000ms, not return instantly
    assert(elapsed >= 900, s"backoffSleep should block for ~1000ms, took only ${elapsed}ms")
    assert(elapsed < 2000, s"backoffSleep took too long: ${elapsed}ms")

    os.remove.all(spec.path / os.up / os.up)
  }

  // ── transitionToBackoff tests ──

  test("transitionToBackoff increments counter and transitions to BackoffWait") {
    val spec = createSpec()
    val record = freshRecord(spec, BspConnectionState.Connected, attempts = 0)

    BspConnectionSupervisor.transitionToBackoff(record)

    assertEquals(record.attemptCounter.get(), 1)
    assertEquals(record.currentState, BspConnectionState.BackoffWait)

    os.remove.all(spec.path / os.up / os.up)
  }

  test("transitionToBackoff transitions to Failed when MaxCrashRetries exceeded") {
    val spec = createSpec()
    // MaxCrashRetries is 5 (private val in BspConnectionSupervisor)
    val record = freshRecord(spec, BspConnectionState.BackoffWait, attempts = 5)

    BspConnectionSupervisor.transitionToBackoff(record)

    // attemptCounter incremented to 6, which exceeds MaxCrashRetries (5)
    assertEquals(record.attemptCounter.get(), 6)
    assertEquals(record.currentState, BspConnectionState.Failed)

    os.remove.all(spec.path / os.up / os.up)
  }

  test("transitionToBackoff is no-op when already Detached") {
    val spec = createSpec()
    val record = freshRecord(spec, BspConnectionState.Detached, attempts = 3)

    BspConnectionSupervisor.transitionToBackoff(record)

    // Counter must be unchanged — state must stay Detached
    assertEquals(record.attemptCounter.get(), 3)
    assertEquals(record.currentState, BspConnectionState.Detached)

    os.remove.all(spec.path / os.up / os.up)
  }

  test("transitionToBackoff is no-op when already Failed") {
    val spec = createSpec()
    val record = freshRecord(spec, BspConnectionState.Failed, attempts = 5)

    BspConnectionSupervisor.transitionToBackoff(record)

    // Counter unchanged, state stays Failed
    assertEquals(record.attemptCounter.get(), 5)
    assertEquals(record.currentState, BspConnectionState.Failed)

    os.remove.all(spec.path / os.up / os.up)
  }
}
