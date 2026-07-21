package ba.sake.basamake.bsp

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import munit.FunSuite

class BspRouterConcurrencyTest extends FunSuite {

  test("concurrent route + registerBspRoot/unregisterBspRoot does not throw exceptions") {
    withTempDir: root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val router = BspRouter()
      val connId = BspConnectionId("sbt-conn")
      router.registerBspRoot(bspDir.toRealPath(), Set(connId))

      val srcDir = Files.createDirectories(root.resolve("src"))
      val fileUri = srcDir.resolve("Main.scala").toUri.toString
      val errors = ConcurrentLinkedQueue[Throwable]()
      val done = AtomicInteger(0)
      val totalThreads = 6

      // Writer threads: register/unregister/invalidate
      val writers = (1 to 3).map { i =>
        Thread.ofVirtual().start(() => {
          try {
            val tempConn = BspConnectionId(s"temp-$i")
            for _ <- 1 to 100 do
              router.registerBspRoot(bspDir.toRealPath(), Set(tempConn))
              router.invalidateBootstrapCache()
              router.unregisterBspRoot(bspDir.toRealPath(), tempConn)
          } catch { case e: Throwable => errors.add(e) }
          finally done.incrementAndGet()
        })
      }

      // Reader threads: route
      val readers = (1 to 3).map { _ =>
        Thread.ofVirtual().start(() => {
          try {
            for _ <- 1 to 100 do
              val result = router.route(fileUri)
              // Route should return a valid Option, never throw
              assert(result.isInstanceOf[Option[?]])
          } catch { case e: Throwable => errors.add(e) }
          finally done.incrementAndGet()
        })
      }

      while done.get() < totalThreads do Thread.sleep(10)
      assert(errors.isEmpty, s"Concurrent access threw ${errors.size} exception(s): ${errors.asScala.map(_.getMessage).mkString(", ")}")
  }

  test("route returns correct connection after concurrent topology changes") {
    withTempDir: root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val router = BspRouter()
      val connA = BspConnectionId("conn-a")
      val connB = BspConnectionId("conn-b")
      router.registerBspRoot(bspDir.toRealPath(), Set(connA, connB))

      val srcDir = Files.createDirectories(root.resolve("src"))
      val fileUri = srcDir.resolve("Test.scala").toUri.toString
      val finalConn = new java.util.concurrent.atomic.AtomicReference[BspConnectionId]()
      val errors = ConcurrentLinkedQueue[Throwable]()
      val done = AtomicInteger(0)
      val totalThreads = 4

      // Register ground truth for both connections
      router.registerGroundTruth(connA, List(root.toUri.toString))
      router.registerGroundTruth(connB, List(root.toUri.toString))

      // Writer: unregister/register ground truth
      val writer = Thread.ofVirtual().start(() => {
        try {
          for i <- 1 to 200 do
            if i % 2 == 0 then
              router.unregisterGroundTruth(connA)
              router.registerGroundTruth(connA, List(root.toUri.toString))
            else
              router.unregisterGroundTruth(connB)
              router.registerGroundTruth(connB, List(root.toUri.toString))
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      // Cache invalidator
      val invalidator = Thread.ofVirtual().start(() => {
        try {
          for _ <- 1 to 200 do
            router.invalidateBootstrapCache()
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      // Readers
      val reader1 = Thread.ofVirtual().start(() => {
        try {
          for _ <- 1 to 200 do
            val r = router.route(fileUri)
            if r.isDefined then finalConn.set(r.get)
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      val reader2 = Thread.ofVirtual().start(() => {
        try {
          for _ <- 1 to 200 do
            val r = router.route(fileUri)
            if r.isDefined then finalConn.set(r.get)
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      while done.get() < totalThreads do Thread.sleep(10)
      assert(errors.isEmpty, s"Concurrent access threw ${errors.size} exception(s)")

      // After stabilization, route should return a valid connection
      val stableResult = router.route(fileUri)
      assert(stableResult.isDefined, "Route should find a connection after topology stabilizes")
  }

  test("invalidateBootstrapCache does not corrupt concurrent route") {
    withTempDir: root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val router = BspRouter()
      val connId = BspConnectionId("test-conn")
      router.registerBspRoot(bspDir.toRealPath(), Set(connId))

      val subDir = Files.createDirectories(root.resolve("a/b/c"))
      val fileUri = subDir.resolve("test.scala").toUri.toString

      val errors = ConcurrentLinkedQueue[Throwable]()
      val done = AtomicInteger(0)

      // Pre-populate cache
      router.route(fileUri)

      val invalidator = Thread.ofVirtual().start(() => {
        try {
          for _ <- 1 to 500 do
            router.invalidateBootstrapCache()
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      val reader = Thread.ofVirtual().start(() => {
        try {
          for _ <- 1 to 500 do
            val result = router.route(fileUri)
            assert(result.isInstanceOf[Option[?]])
            // After invalidation, route should re-walk and find the connection
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      while done.get() < 2 do Thread.sleep(10)
      assert(errors.isEmpty, s"Concurrent access threw ${errors.size} exception(s)")

      // After all concurrent ops, route should still work
      val finalResult = router.route(fileUri)
      assertEquals(finalResult, Some(connId))
  }

  private def withTempDir[A](body: Path => A): A =
    val tmp = Files.createTempDirectory("bsprt-conc-")
    try body(tmp)
    finally Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
}
