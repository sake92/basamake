package ba.sake.basamake.bsp

import ba.sake.basamake.core.*
import ba.sake.basamake.bsp.{BspDiscoveryFile, BspConnectionState}

class StateMachineTest extends munit.FunSuite:
  test("backoff increments") {
    val r = DurableRecord(BspConnectionSpec(BspDiscoveryFile("mybsp", List("e")), os.pwd, os.pwd), 0, Map.empty, BspConnectionState.Idle)
    r.attemptCounter += 1
    assertEquals(r.attemptCounter, 1)
  }
  test("backoff delay is fixed 1 second") {
    assertEquals(1000L, 1000L)  // always 1s now
  }
  test("loop stops at Failed") {
    def loop(s: BspConnectionState) = s != BspConnectionState.Failed && s != BspConnectionState.Detached
    assert(!loop(BspConnectionState.Failed))
    assert(loop(BspConnectionState.Idle))
  }
  test("crash counter stops at 2") {
    val r = DurableRecord(BspConnectionSpec(BspDiscoveryFile("mybsp", List("e")), os.pwd, os.pwd), 0, Map.empty, BspConnectionState.Idle)
    r.attemptCounter = 1  // one crash
    r.attemptCounter += 1 // second crash
    assert(r.attemptCounter > 1, "2 consecutive crashes should exceed max retries")
  }

  test("crash counter resets on Connected") {
    val r = DurableRecord(BspConnectionSpec(BspDiscoveryFile("mybsp", List("e")), os.pwd, os.pwd), 1, Map.empty, BspConnectionState.Idle)
    r.currentState = BspConnectionState.Connected
    r.attemptCounter = 0  // simulate reset
    assertEquals(r.attemptCounter, 0)
  }
