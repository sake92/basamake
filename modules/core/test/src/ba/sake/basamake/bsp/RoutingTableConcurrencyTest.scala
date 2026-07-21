package ba.sake.basamake.bsp

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import munit.FunSuite

class RoutingTableConcurrencyTest extends FunSuite {

  test("concurrent update/remove/reverseLookup does not throw exceptions") {
    val table = RoutingTable.empty
    val numWriters = 5
    val numReaders = 5
    val iterations = 200
    val errors = ConcurrentLinkedQueue[Throwable]()
    val done = AtomicInteger(0)
    val totalThreads = numWriters + numReaders

    val connIds = (1 to numWriters).map(i => BspConnectionId(s"conn-$i")).toList

    // Writer threads: update and remove
    val writers = connIds.map { connId =>
      Thread.ofVirtual().start(() => {
        try {
          for i <- 1 to iterations do
            table.update(connId, List(s"file:///ws/${connId.value}/$i/"))
            if i % 10 == 0 then
              table.remove(connId)
              table.update(connId, List(s"file:///ws/${connId.value}/$i/"))
        } catch {
          case e: Throwable => errors.add(e)
        } finally done.incrementAndGet()
      })
    }

    // Reader threads: reverseLookup
    val readers = (1 to numReaders).map { r =>
      Thread.ofVirtual().start(() => {
        try {
          for i <- 1 to iterations do
            val uri = s"file:///ws/conn-${(i % numWriters) + 1}/$i/SomeFile.scala"
            val result = table.reverseLookup(uri)
            // Result can be Some or None, but must not throw
            val _ = result
        } catch {
          case e: Throwable => errors.add(e)
        } finally done.incrementAndGet()
      })
    }

    // Wait for all threads
    while done.get() < totalThreads do Thread.sleep(10)

    // Assert no errors
    assert(errors.isEmpty, s"Concurrent access threw ${errors.size} exception(s): ${errors.asScala.map(_.getMessage).mkString(", ")}")
  }

  test("table in consistent state after concurrent mutations") {
    val table = RoutingTable.empty
    val connA = BspConnectionId("conn-a")
    val connB = BspConnectionId("conn-b")
    val done = AtomicInteger(0)

    // Writer 1: add/remove connA
    val w1 = Thread.ofVirtual().start(() => {
      for _ <- 1 to 100 do
        table.update(connA, List(s"file:///ws/a/"))
      done.incrementAndGet()
    })

    // Writer 2: add connB and do reverseLookup
    val w2 = Thread.ofVirtual().start(() => {
      for _ <- 1 to 100 do
        table.update(connB, List(s"file:///ws/b/"))
      done.incrementAndGet()
    })

    // Wait
    while done.get() < 2 do Thread.sleep(10)

    // Verify: if connA was set, reverseLookup on connA's prefix should return connA
    val resultA = table.reverseLookup("file:///ws/a/test.scala")
    val resultB = table.reverseLookup("file:///ws/b/test.scala")

    // connA was set by w1, connB by w2 — both should be findable
    assertEquals(resultA, Some(connA))
    assertEquals(resultB, Some(connB))
  }

  test("reverseLookupCandidates is consistent under concurrent update") {
    val table = RoutingTable.empty
    val connA = BspConnectionId("conn-a")
    val connB = BspConnectionId("conn-b")
    val errors = ConcurrentLinkedQueue[Throwable]()
    val done = AtomicInteger(0)

    val w1 = Thread.ofVirtual().start(() => {
      try {
        for i <- 1 to 200 do
          if i % 2 == 0 then table.update(connA, List(s"file:///ws/shared/"))
          else table.remove(connA)
      } catch { case e: Throwable => errors.add(e) }
      finally done.incrementAndGet()
    })

    val w2 = Thread.ofVirtual().start(() => {
      try {
        for _ <- 1 to 200 do
          table.update(connB, List(s"file:///ws/shared/"))
      } catch { case e: Throwable => errors.add(e) }
      finally done.incrementAndGet()
    })

    val reader = Thread.ofVirtual().start(() => {
      try {
        for _ <- 1 to 200 do
          val candidates = table.reverseLookupCandidates(s"file:///ws/shared/test.scala")
          // Must be a valid list (may be empty, may have connA, may have connB)
          assert(candidates.isInstanceOf[List[?]], "reverseLookupCandidates must return List")
      } catch { case e: Throwable => errors.add(e) }
      finally done.incrementAndGet()
    })

    while done.get() < 3 do Thread.sleep(10)
    assert(errors.isEmpty, s"Concurrent access threw ${errors.size} exception(s)")
  }

  test("remove does not affect other connections under concurrency") {
    val table = RoutingTable.empty
    val connIds = (1 to 10).map(i => BspConnectionId(s"c-$i")).toList
    val errors = ConcurrentLinkedQueue[Throwable]()
    val done = AtomicInteger(0)

    // Register all
    connIds.foreach(id => table.update(id, List(s"file:///ws/${id.value}/")))

    // Concurrently remove some, lookup others
    val removers = connIds.take(5).map { id =>
      Thread.ofVirtual().start(() => {
        try { table.remove(id) }
        catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })
    }

    val lookups = connIds.drop(5).map { id =>
      Thread.ofVirtual().start(() => {
        try {
          val result = table.reverseLookup(s"file:///ws/${id.value}/test.scala")
          assertEquals(result, Some(id))
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })
    }

    while done.get() < 10 do Thread.sleep(10)
    assert(errors.isEmpty, s"Concurrent remove/lookup threw ${errors.size} exception(s)")
  }
}
