package ba.sake.basamake.bsp

import ba.sake.basamake.core.DurableRecord
import ba.sake.basamake.bsp.BspDiscoveryFile

class BspConnectionSupervisorLifecycleTest extends munit.FunSuite {
  test("handshake failure cleanup clears process and marks failed") {
    val process = new java.lang.ProcessBuilder("bash", "-lc", "sleep 60").start()
    assert(process.isAlive(), "test setup must start a live process")

    val durable = DurableRecord(
      BspConnectionSpec(BspDiscoveryFile("test-bsp", List("bash", "-lc", "sleep 60")), os.pwd),
      0,
      Map.empty,
      BspConnectionState.Handshaking,
      Some(process)
    )

    BspConnectionSupervisor.handleHandshakeFailureForTest(durable)

    val exited = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
    assert(exited, "cleanup should terminate spawned BSP process")
    assertEquals(durable.bspProcess, None)
    assertEquals(durable.currentState, BspConnectionState.Failed)
  }
}
