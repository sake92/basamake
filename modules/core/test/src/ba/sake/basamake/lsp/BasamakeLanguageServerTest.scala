package ba.sake.basamake.lsp

import munit.FunSuite
import ba.sake.basamake.bsp.BuildServerManager

class BasamakeLanguageServerTest extends FunSuite:

  test("manager.shutdown is idempotent") {
    val manager = BuildServerManager()
    // First call should succeed
    manager.shutdown()
    // Second call should be no-op (not throw, not NPE)
    manager.shutdown()
  }
