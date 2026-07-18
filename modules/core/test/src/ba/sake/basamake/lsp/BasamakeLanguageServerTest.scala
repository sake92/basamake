package ba.sake.basamake.lsp

import ba.sake.basamake.manager.BuildServerManager
import munit.FunSuite

class BasamakeLanguageServerTest extends FunSuite:

  private final class CountingManager extends BuildServerManager:
    var shutdownCalls = 0
    var killCalls = 0

    override def shutdown(): Unit =
      shutdownCalls += 1

    override def killBspProcesses(): Unit =
      killCalls += 1

  test("cleanup is idempotent for shutdown + kill") {
    val manager = CountingManager()
    val server = BasamakeLanguageServer(manager)

    server.cleanup()
    server.cleanup()

    assertEquals(manager.shutdownCalls, 1)
    assertEquals(manager.killCalls, 1)
  }

  test("shutdown then cleanup still performs exactly one kill") {
    val manager = CountingManager()
    val server = BasamakeLanguageServer(manager)

    server.shutdown().get()
    server.cleanup()
    server.cleanup()

    assertEquals(manager.shutdownCalls, 1)
    assertEquals(manager.killCalls, 1)
  }
