package ba.sake.basamake.bsp

import java.nio.file.Files
import munit.FunSuite

class BspRouterTest extends FunSuite {

  private def withTempDir(f: java.nio.file.Path => Unit): Unit = {
    val tmp = Files.createTempDirectory("bsp-router-test")
    try f(tmp) finally {
      import scala.jdk.CollectionConverters.*
      Files.walk(tmp).iterator.asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
    }
  }

  test("bootstrap cache — miss triggers walk, hit uses cached result") {
    withTempDir { root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val connId = BspConnectionId("sbt-conn")
      val router = BspRouter()
      router.registerBspRoot(bspDir.toRealPath(), Set(connId))

      val subDir = Files.createDirectories(root.resolve("a/b/c"))
      val fileUri = subDir.resolve("test.scala").toUri.toString

      val result1 = router.route(fileUri)
      assertEquals(result1, Some(connId), "First route should find BSP via walk")

      val result2 = router.route(subDir.resolve("test2.scala").toUri.toString)
      assertEquals(result2, Some(connId), "Second route should hit cache")
    }
  }

  test("route returns None when no .bsp found") {
    withTempDir { root =>
      val router = BspRouter()
      val subDir = Files.createDirectories(root.resolve("deep/nested"))
      val fileUri = subDir.resolve("orphan.scala").toUri.toString
      assertEquals(router.route(fileUri), None)
    }
  }

  test("unregisterBspRoot tolerates a deleted .bsp directory") {
    withTempDir { root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val connId = BspConnectionId("sbt-conn")
      val router = BspRouter()
      router.registerBspRoot(bspDir.toRealPath(), Set(connId))

      Files.createDirectories(root.resolve("a/b"))
      val fileUri = root.resolve("a/b/Test.scala").toUri.toString
      assertEquals(router.route(fileUri), Some(connId), "route should find BSP before deletion")

      // whole .bsp directory removed (e.g. test-fixture cleanup)
      Files.delete(bspDir)
      router.unregisterBspRoot(bspDir, connId) // must not throw NoSuchFileException

      assertEquals(router.route(fileUri), None, "no .bsp root left after unregister")
    }
  }
}
