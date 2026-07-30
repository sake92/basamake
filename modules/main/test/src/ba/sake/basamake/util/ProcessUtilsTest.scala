package ba.sake.basamake.util

import java.util.concurrent.TimeUnit
import munit.FunSuite

class ProcessUtilsTest extends FunSuite {

  test("terminateProcessTree kills a spawned long-running process") {
    val pb = new ProcessBuilder("sleep", "30")
    val proc = pb.start()
    assert(proc.isAlive, "process should be alive before termination")
    try {
      val signaled = ProcessUtils.terminateProcessTree(proc)
      assert(signaled >= 1, s"should have signaled at least 1 node, got $signaled")
      assert(proc.waitFor(3, TimeUnit.SECONDS), "process should exit within 3s")
      assert(!proc.isAlive, "process should be dead after termination")
    } finally {
      if proc.isAlive then proc.destroyForcibly()
    }
  }

  test("terminateProcessTree returns 0 for null without throwing") {
    assertEquals(ProcessUtils.terminateProcessTree(null), 0)
  }
}
