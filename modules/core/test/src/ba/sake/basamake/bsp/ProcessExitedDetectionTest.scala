package ba.sake.basamake.bsp

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import ba.sake.basamake.core.ConnectionMessage

class ProcessExitedDetectionTest extends FunSuite {

  /** Helper: register an onExit callback that offers ProcessExited to the queue,
    * then wait for it to fire. Mirrors what BspConnectionSupervisor does at line 104-106:
    *   process.onExit().thenAccept { _ => queue.offer(ConnectionMessage.ProcessExited) }
    */
  private def assertProcessExitedQueued(queue: LinkedBlockingQueue[ConnectionMessage], proc: Process): Unit =
    proc.onExit().thenAccept(_ => queue.offer(ConnectionMessage.ProcessExited))
    proc.waitFor(5, TimeUnit.SECONDS)
    var waited = 0L
    val deadline = System.currentTimeMillis() + 3000
    while queue.peek() == null && System.currentTimeMillis() < deadline do
      Thread.sleep(10)
      waited += 10

  // ── ProcessExited → queue integration tests ──

  test("process exit offers ProcessExited message to queue within 5s") {
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    val proc = new ProcessBuilder("true").start()
    val start = System.currentTimeMillis()

    assertProcessExitedQueued(queue, proc)

    val elapsed = System.currentTimeMillis() - start
    val msg = queue.poll()
    assert(msg != null, s"Queue must contain ProcessExited after process exit (${elapsed}ms elapsed)")
    assertEquals(msg, ConnectionMessage.ProcessExited)
    assert(elapsed < 5000, s"ProcessExited detection took ${elapsed}ms, must be < 5000ms")
  }

  test("ProcessExited message arrives faster than 30s health probe window") {
    // Supervisor HealthTtlSec = 30s. ProcessExit must be much faster.
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    val proc = new ProcessBuilder("sleep", "0.1").start()
    val start = System.currentTimeMillis()

    assertProcessExitedQueued(queue, proc)

    val elapsed = System.currentTimeMillis() - start
    val msg = queue.poll()
    assertEquals(msg, ConnectionMessage.ProcessExited)
    // Critical: must be well under 30s (health probe TTL in BspConnectionSupervisor)
    assert(elapsed < 5000, s"ProcessExited took ${elapsed}ms — must be < 5000ms (not 30s)")
  }

  test("exited process still fires callback when onExit registered after exit") {
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    val proc = new ProcessBuilder("true").start()
    proc.waitFor(5, TimeUnit.SECONDS)

    // Register onExit AFTER process has already exited
    proc.onExit().thenAccept(_ => queue.offer(ConnectionMessage.ProcessExited))

    var waited = 0L
    val deadline = System.currentTimeMillis() + 3000
    while queue.peek() == null && System.currentTimeMillis() < deadline do
      Thread.sleep(10)
      waited += 10

    val msg = queue.poll()
    assertEquals(msg, ConnectionMessage.ProcessExited,
      "ProcessExited must be offered even when onExit registered after process exit")
  }

  test("multiple concurrent process exits each deliver ProcessExited") {
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    val numProcesses = 3
    val procs = (1 to numProcesses).map(_ => new ProcessBuilder("true").start())

    // Register onExit for all processes
    procs.foreach { p =>
      p.onExit().thenAccept(_ => queue.offer(ConnectionMessage.ProcessExited))
    }

    // Wait for all to finish
    procs.foreach(_.waitFor(5, TimeUnit.SECONDS))

    // Poll for messages with timeout
    var count = 0
    val deadline = System.currentTimeMillis() + 5000
    while count < numProcesses && System.currentTimeMillis() < deadline do
      val msg = queue.poll(500, TimeUnit.MILLISECONDS)
      if msg != null then
        assertEquals(msg, ConnectionMessage.ProcessExited, s"Message #${count + 1}")
        count += 1

    assertEquals(count, numProcesses, s"All $numProcesses processes must deliver ProcessExited messages")
  }

  test("process with non-zero exit code still delivers ProcessExited") {
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    val proc = new ProcessBuilder("bash", "-c", "exit 42").start()

    assertProcessExitedQueued(queue, proc)

    val msg = queue.poll()
    assertEquals(msg, ConnectionMessage.ProcessExited,
      "ProcessExited must be delivered regardless of exit code")
  }

  test("queue is not corrupted when ProcessExited arrives concurrently with other messages") {
    val queue = new LinkedBlockingQueue[ConnectionMessage]()
    val proc = new ProcessBuilder("sleep", "0.05").start()

    // Register onExit
    proc.onExit().thenAccept(_ => queue.offer(ConnectionMessage.ProcessExited))

    // Concurrently offer other messages
    val textDoc = new org.eclipse.lsp4j.TextDocumentIdentifier("file:///ws/Main.scala")
    val didSave = ConnectionMessage.DidSave(
      new org.eclipse.lsp4j.DidSaveTextDocumentParams(textDoc)
    )

    val offerThread = Thread.ofVirtual().start(() => {
      for _ <- 1 to 50 do
        queue.offer(didSave)
        Thread.sleep(1)
    })

    proc.waitFor(5, TimeUnit.SECONDS)
    var waited = 0L
    val deadline = System.currentTimeMillis() + 3000
    while queue.peek() == null && System.currentTimeMillis() < deadline do
      Thread.sleep(10)
      waited += 10

    offerThread.join(2000)

    // Drain queue — verify ProcessExited and DidSave messages coexist without corruption
    var foundExited = false
    var foundDidSave = false
    var msg = queue.poll()
    while msg != null do
      if msg == ConnectionMessage.ProcessExited then foundExited = true
      else if msg.isInstanceOf[ConnectionMessage.DidSave] then foundDidSave = true
      else fail(s"Unexpected message type: ${msg.getClass.getSimpleName}")
      msg = queue.poll()

    assert(foundExited, "ProcessExited must be in the queue")
    assert(foundDidSave, "DidSave messages must coexist with ProcessExited in queue")
  }
}
