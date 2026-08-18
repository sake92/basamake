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
        val mgr = BspManager.forTesting(os.Path(root))
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
      val mgr = BspManager.forTesting(os.Path(root))
      mgr.shutdown()
      mgr.shutdown()  // no exception
    } finally {
      import scala.jdk.CollectionConverters.*
      Files.walk(root).iterator.asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
    }
  }
}
