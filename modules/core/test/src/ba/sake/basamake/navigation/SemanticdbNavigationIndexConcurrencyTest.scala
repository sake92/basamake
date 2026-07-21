package ba.sake.basamake.navigation

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.*
import munit.FunSuite
import org.eclipse.lsp4j.{Location, Position, Range}

class SemanticdbNavigationIndexConcurrencyTest extends FunSuite {

  private val targetId = new BuildTargetIdentifier("target://test")
  private val sourceUri = "file:///ws/Test.scala"

  private def mockBuildServer: BuildServer =
    val handler = new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        method.getName match
          case "buildTargetOutputPaths" =>
            CompletableFuture.completedFuture(
              new OutputPathsResult(java.util.Collections.emptyList())
            )
          case "buildTargetScalacOptions" =>
            CompletableFuture.completedFuture(
              new ScalacOptionsResult(java.util.Collections.emptyList())
            )
          case "buildTargetDependencySources" =>
            CompletableFuture.completedFuture(
              new DependencySourcesResult(java.util.Collections.emptyList())
            )
          case "toString" => "mock-concurrency-build-server"
          case _          => throw new UnsupportedOperationException(method.getName)
    }
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[BuildServer], classOf[ScalaBuildServer]),
        handler
      )
      .asInstanceOf[BuildServer]

  private def createTestSlice(counter: Int): SemanticdbFileSlice =
    val symbol = s"test/Symbol$counter."
    val range = new Range(new Position(counter, 0), new Position(counter, 10))
    SemanticdbFileSlice(
      sourceUri = sourceUri,
      occurrences = List(SemanticdbOccurrence(symbol, range, isDefinition = true)),
      symbolDefinitions = Map(symbol -> List(new Location(sourceUri, range))),
      symbolReferences = Map(symbol -> List(new Location(sourceUri, range))),
      documentSymbols = List(
        org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
          new org.eclipse.lsp4j.SymbolInformation(s"Symbol$counter", org.eclipse.lsp4j.SymbolKind.Class, new Location(sourceUri, range), sourceUri)
        )
      )
    )

  test("concurrent refresh and definition/references/documentSymbols does not throw") {
    val tmp = os.temp.dir(prefix = "nav-conc-")
    try
      val index = new NavigationIndex()
      val errors = ConcurrentLinkedQueue[Throwable]()
      val done = AtomicInteger(0)
      val totalThreads = 4
      val running = new java.util.concurrent.atomic.AtomicBoolean(true)

      // Inject initial data
      index.setTargetSlicesForTest(targetId, Map(sourceUri -> createTestSlice(0)))

      // Refresher thread — calls refresh with mock build server
      val refresher = Thread.ofVirtual().start(() => {
        try {
          var counter = 1
          while running.get() do
            index.refresh(tmp, mockBuildServer, List(targetId), Map.empty, Map.empty)
            // Inject new data to simulate refresh result
            index.setTargetSlicesForTest(targetId, Map(sourceUri -> createTestSlice(counter)))
            counter += 1
            Thread.sleep(5) // small delay to let readers interleave
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      // Reader threads
      val readers = (1 to 3).map { _ =>
        Thread.ofVirtual().start(() => {
          try {
            while running.get() do
              val _ = index.definition(sourceUri, new Position(0, 5))
              val _ = index.references(sourceUri, new Position(0, 5))
              val _ = index.documentSymbols(sourceUri)
              Thread.sleep(1)
          } catch { case e: Throwable => errors.add(e) }
          finally done.incrementAndGet()
        })
      }

      // Let them run for 2 seconds
      Thread.sleep(2000)
      running.set(false)

      // Wait for all to finish
      while done.get() < totalThreads do Thread.sleep(10)
      assert(errors.isEmpty, s"Concurrent access threw ${errors.size} exception(s): ${errors.asScala.map(_.getMessage).mkString(", ")}")
    finally
      os.remove.all(tmp)
  }

  test("readers never see corrupted data during concurrent refresh") {
    val tmp = os.temp.dir(prefix = "nav-conc2-")
    try
      val index = new NavigationIndex()
      val errors = ConcurrentLinkedQueue[Throwable]()
      val done = AtomicInteger(0)
      val running = new java.util.concurrent.atomic.AtomicBoolean(true)

      // Inject initial data
      index.setTargetSlicesForTest(targetId, Map(sourceUri -> createTestSlice(0)))

      // Refresher: rapid clear+set cycles
      val refresher = Thread.ofVirtual().start(() => {
        try {
          var i = 1
          while running.get() do
            index.setTargetSlicesForTest(targetId, Map(sourceUri -> createTestSlice(i)))
            i += 1
            if i % 10 == 0 then
              index.clear()
              index.setTargetSlicesForTest(targetId, Map(sourceUri -> createTestSlice(i)))
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      // Reader: validate that definition results are well-formed
      val reader = Thread.ofVirtual().start(() => {
        try {
          while running.get() do
            val defs = index.definition(sourceUri, new Position(0, 5))
            // Definitions should be a valid list (may be empty after clear)
            assert(defs.isInstanceOf[List[?]], s"definition must return List, got ${defs.getClass}")
            // Each location must have valid URI and range
            defs.foreach { loc =>
              assert(loc.getUri != null, "Location URI must not be null")
              assert(loc.getRange != null, "Location range must not be null")
            }

            val refs = index.references(sourceUri, new Position(0, 5))
            assert(refs.isInstanceOf[List[?]], s"references must return List")
            refs.foreach { loc =>
              assert(loc.getUri != null, "Reference URI must not be null")
            }

            val syms = index.documentSymbols(sourceUri)
            assert(syms.isInstanceOf[List[?]], s"documentSymbols must return List")

            Thread.sleep(2)
        } catch { case e: Throwable => errors.add(e) }
        finally done.incrementAndGet()
      })

      Thread.sleep(2000)
      running.set(false)

      while done.get() < 2 do Thread.sleep(10)
      assert(errors.isEmpty, s"Concurrent access threw ${errors.size} exception(s): ${errors.asScala.map(_.getMessage).mkString(", ")}")
    finally
      os.remove.all(tmp)
  }

  test("depSliceCache does not corrupt under concurrent population") {
    val tmp = os.temp.dir(prefix = "nav-conc3-")
    try
      val index = new NavigationIndex()
      val errors = ConcurrentLinkedQueue[Throwable]()
      val done = AtomicInteger(0)
      val running = new java.util.concurrent.atomic.AtomicBoolean(true)
      val totalThreads = 4

      index.setTargetSlicesForTest(targetId, Map(sourceUri -> createTestSlice(0)))

      // Multiple refresher threads
      val refreshers = (1 to 2).map { _ =>
        Thread.ofVirtual().start(() => {
          try {
            while running.get() do
              index.refresh(tmp, mockBuildServer, List(targetId), Map.empty, Map.empty)
              Thread.sleep(5)
          } catch { case e: Throwable => errors.add(e) }
          finally done.incrementAndGet()
        })
      }

      // Multiple reader threads
      val readers = (1 to 2).map { _ =>
        Thread.ofVirtual().start(() => {
          try {
            while running.get() do
              index.definition(sourceUri, new Position(0, 5))
              index.references(sourceUri, new Position(0, 5))
              index.documentSymbols(sourceUri)
              Thread.sleep(1)
          } catch { case e: Throwable => errors.add(e) }
          finally done.incrementAndGet()
        })
      }

      Thread.sleep(2000)
      running.set(false)

      while done.get() < totalThreads do Thread.sleep(10)
      assert(errors.isEmpty, s"Concurrent access threw ${errors.size} exception(s): ${errors.asScala.map(_.getMessage).mkString(", ")}")
    finally
      os.remove.all(tmp)
  }
}
