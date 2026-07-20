package ba.sake.basamake.lsp

import munit.FunSuite
import ba.sake.basamake.bsp.BuildServerManager

class BasamakeLanguageServerTest extends FunSuite:

  test("manager.shutdown is idempotent") {
    val manager = BuildServerManager()
    manager.shutdown()
    // second call should not throw
    manager.shutdown()
  }
