package ba.sake.basamake.bsp

import ba.sake.basamake.core.*
import ba.sake.basamake.bsp.{BspConnectionFile, BspDiscoveryFile, BspConnectionState}

class StateMachineTest extends munit.FunSuite:
  test("backoff increments") {
    val r = DurableRecord(BspConnectionSpec(BspDiscoveryFile("mybsp", List("e")), os.pwd, os.pwd), 0, Map.empty, BspConnectionState.Idle)
    r.attemptCounter += 1
    assertEquals(r.attemptCounter, 1)
  }
  test("exponential delay") {
    assertEquals(math.min(1000L*1, 30000L), 1000L)
    assertEquals(math.min(1000L*2, 30000L), 2000L)
    assertEquals(math.min(1000L*16384, 30000L), 30000L)
  }
  test("loop stops at Failed") {
    def loop(s: BspConnectionState) = s != BspConnectionState.Failed && s != BspConnectionState.Detached
    assert(!loop(BspConnectionState.Failed))
    assert(loop(BspConnectionState.Idle))
  }
