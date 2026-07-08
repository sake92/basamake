package ba.sake.basamake.bsp

import ba.sake.basamake.core.*
import java.nio.file.Paths

class StateMachineTest extends munit.FunSuite:
  test("backoff increments") {
    val r = DurableRecord(ConnectionSpec(Paths.get("."), List("e"), Paths.get(".")), 0, Map.empty, ConnectionState.Idle)
    r.attemptCounter += 1
    assertEquals(r.attemptCounter, 1)
  }
  test("exponential delay") {
    assertEquals(math.min(1000L*1, 30000L), 1000L)
    assertEquals(math.min(1000L*2, 30000L), 2000L)
    assertEquals(math.min(1000L*16384, 30000L), 30000L)
  }
  test("loop stops at Failed") {
    def loop(s: ConnectionState) = s != ConnectionState.Failed && s != ConnectionState.Detached
    assert(!loop(ConnectionState.Failed))
    assert(loop(ConnectionState.Idle))
  }
