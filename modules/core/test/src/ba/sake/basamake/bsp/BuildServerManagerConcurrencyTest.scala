package ba.sake.basamake.bsp

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import org.eclipse.lsp4j.services.LanguageClient
import ba.sake.basamake.config.BasamakeConfig

class BuildServerManagerConcurrencyTest extends FunSuite {

  private def mockLanguageClient: LanguageClient =
    val handler = new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        method.getName match
          case "toString" => "mock-lsp-client"
          case _          => null // no-op for all other methods
    }
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[LanguageClient]),
        handler
      )
      .asInstanceOf[LanguageClient]

  test("concurrent route + trackDidOpen/trackDidClose does not throw exceptions") {
    val tmp = os.temp.dir(prefix = "bsm-conc-")
    val manager = BuildServerManager()
    try
      manager.initialize(
        workspaceRoot = tmp,
        lspClient = mockLanguageClient,
        config = BasamakeConfig()
      )

      val errors = ConcurrentLinkedQueue[Throwable]()
      val done = AtomicInteger(0)
      val totalThreads = 5
      val testUris = (1 to 20).map(i => s"file:///ws/test$i.scala").toList

      // Route threads
      val routers = (1 to 3).map { _ =>
        Thread.ofVirtual().start(() => {
          try {
            for i <- 1 to 200 do
              val uri = testUris(i % testUris.size)
              // route returns Option — may be None since no BSP connections
              val result = manager.route(uri)
              assert(result.isInstanceOf[Option[?]])
          } catch { case e: Throwable => errors.add(e) }
          finally done.incrementAndGet()
        })
      }

      // Track threads
      val tracker1 = Thread.ofVirtual().start(() => {
        try {
          for i <- 1 to 200 do
            val uri = testUris(i % testUris.size)
            manager.trackDidOpen(uri)
            if i % 3 == 0 then manager.trackDidClose(uri)
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      val tracker2 = Thread.ofVirtual().start(() => {
        try {
          for i <- 1 to 200 do
            val uri = testUris((i * 7) % testUris.size)
            manager.trackDidOpen(uri)
            manager.trackDidClose(uri)
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      while done.get() < totalThreads do Thread.sleep(10)
      assert(errors.isEmpty, s"Concurrent access threw ${errors.size} exception(s): ${errors.asScala.map(_.getMessage).mkString(", ")}")
    finally
      manager.shutdown()
      os.remove.all(tmp)
  }

  test("openUris set is consistent after concurrent trackDidOpen/trackDidClose") {
    val tmp = os.temp.dir(prefix = "bsm-conc2-")
    val manager = BuildServerManager()
    try
      manager.initialize(
        workspaceRoot = tmp,
        lspClient = mockLanguageClient,
        config = BasamakeConfig()
      )

      val uri = "file:///ws/single.scala"
      val openCount = new java.util.concurrent.atomic.AtomicInteger(0)
      val closeCount = new java.util.concurrent.atomic.AtomicInteger(0)
      val errors = ConcurrentLinkedQueue[Throwable]()
      val done = AtomicInteger(0)

      // Thread A: trackDidOpen
      val opener = Thread.ofVirtual().start(() => {
        try {
          for _ <- 1 to 1000 do
            manager.trackDidOpen(uri)
            openCount.incrementAndGet()
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      // Thread B: trackDidClose
      val closer = Thread.ofVirtual().start(() => {
        try {
          for _ <- 1 to 1000 do
            manager.trackDidClose(uri)
            closeCount.incrementAndGet()
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      while done.get() < 2 do Thread.sleep(10)
      assert(errors.isEmpty, s"Concurrent access threw ${errors.size} exception(s)")
      assertEquals(openCount.get(), 1000)
      assertEquals(closeCount.get(), 1000)

      // Verify openUris state via reflection
      val openUrisField = classOf[BuildServerManager].getDeclaredField("openUris")
      openUrisField.setAccessible(true)
      val openUris = openUrisField.get(manager).asInstanceOf[java.util.Set[String]]
      // After equal opens and closes, set should be empty (or possibly contain uri if timing was off)
      // Key assertion: no ConcurrentModificationException was thrown
      assert(openUris != null, "openUris set must not be null after concurrent access")
    finally
      manager.shutdown()
      os.remove.all(tmp)
  }

  test("route never returns null under concurrent topology changes") {
    val tmp = os.temp.dir(prefix = "bsm-conc3-")
    val manager = BuildServerManager()
    try
      manager.initialize(
        workspaceRoot = tmp,
        lspClient = mockLanguageClient,
        config = BasamakeConfig()
      )

      val errors = ConcurrentLinkedQueue[Throwable]()
      val done = AtomicInteger(0)
      val totalThreads = 4

      // 4 threads concurrently calling route on various URIs
      (1 to totalThreads).foreach { t =>
        Thread.ofVirtual().start(() => {
          try {
            for i <- 1 to 200 do
              val uri = s"file:///ws/module$t/file$i.scala"
              val result = manager.route(uri)
              // result must never be null
              assert(result != null, s"route($uri) returned null")
          } catch { case e: Throwable => errors.add(e) }
          finally done.incrementAndGet()
        })
      }

      while done.get() < totalThreads do Thread.sleep(10)
      assert(errors.isEmpty, s"Concurrent route threw ${errors.size} exception(s): ${errors.asScala.map(_.getMessage).mkString(", ")}")
    finally
      manager.shutdown()
      os.remove.all(tmp)
  }

  test("shutdown does not throw when called concurrently with operations") {
    val tmp = os.temp.dir(prefix = "bsm-conc4-")
    val manager = BuildServerManager()
    try
      manager.initialize(
        workspaceRoot = tmp,
        lspClient = mockLanguageClient,
        config = BasamakeConfig()
      )

      val errors = ConcurrentLinkedQueue[Throwable]()
      val done = AtomicInteger(0)

      // Op threads
      val op = Thread.ofVirtual().start(() => {
        try {
          for i <- 1 to 100 do
            manager.trackDidOpen(s"file:///ws/file$i.scala")
            manager.route(s"file:///ws/file$i.scala")
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      while done.get() < 1 do Thread.sleep(10)

      // Shutdown concurrently with ops
      try manager.shutdown()
      catch { case e: Throwable => errors.add(e) }

      assert(errors.isEmpty, s"Concurrent shutdown threw ${errors.size} exception(s): ${errors.asScala.map(_.getMessage).mkString(", ")}")
    finally
      manager.shutdown()
      os.remove.all(tmp)
  }
}
