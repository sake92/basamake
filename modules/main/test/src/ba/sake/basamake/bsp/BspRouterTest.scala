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

  test("ground truth wins over bootstrap heuristic") {
    withTempDir { root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val connId = BspConnectionId("sbt-conn")
      val router = BspRouter()
      router.registerBspRoot(bspDir.toRealPath(), Set(connId))

      val groundTruthConn = BspConnectionId("mill-conn")
      val sourceDirs = List(root.resolve("src").toUri.toString)
      router.registerGroundTruth(groundTruthConn, sourceDirs)

      Files.createDirectories(root.resolve("src"))
      val fileUri = root.resolve("src/Test.scala").toUri.toString
      assertEquals(router.route(fileUri), Some(groundTruthConn),
        "Ground truth (RoutingTable) must win over bootstrap cache")
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
}
