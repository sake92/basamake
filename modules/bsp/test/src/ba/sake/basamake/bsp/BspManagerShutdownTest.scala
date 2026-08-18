package ba.sake.basamake.bsp

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import munit.FunSuite

class BspManagerShutdownTest extends FunSuite {

  test("after shutdown() no lingering descendant processes of this JVM") {
    val root = Files.createTempDirectory("bsp-shutdown")
    try {
      val sleep = new ProcessBuilder("sleep", "30").start()
      try {
        assert(sleep.isAlive, "child process should be alive before shutdown")
        val mgr = BspManagerTestSupport.managerFor(os.Path(root), new CapturingLanguageClient)
        mgr.shutdown()
        assert(sleep.waitFor(5, TimeUnit.SECONDS), "child should be killed within 5s of shutdown")
        assert(!sleep.isAlive, "child process should be dead after shutdown")
      } finally {
        if sleep.isAlive then sleep.destroyForcibly()
      }
    } finally {
      import scala.jdk.CollectionConverters.*
      Files.walk(root).iterator.asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
    }
  }

  test("shutdown is idempotent — calling twice does not throw") {
    val root = Files.createTempDirectory("bsp-shutdown-id")
    try {
      val mgr = BspManagerTestSupport.managerFor(os.Path(root), new CapturingLanguageClient)
      mgr.shutdown()
      mgr.shutdown()  // no exception
    } finally {
      import scala.jdk.CollectionConverters.*
      Files.walk(root).iterator.asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
    }
  }

  test("shutdown before initialize() is safe (no watcher started yet)") {
    val root = os.temp.dir(prefix = "bsp-shutdown-preinit-")
    try {
      val symbolTable = new ba.sake.basamake.index.InMemorySymbolTable
      val depsTable = new ba.sake.basamake.index.indexing.IndexedSymbolTable()
      val index = new ba.sake.basamake.index.indexing.WorkspaceIndex(root, symbolTable, Some(depsTable))
      val mgr = new BspManager(root, index, depsTable, ba.sake.basamake.config.BasamakeConfig.load(root))
      mgr.shutdown()
      mgr.shutdown()
    } finally os.remove.all(root)
  }
}
