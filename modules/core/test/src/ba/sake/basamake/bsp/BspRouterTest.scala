package ba.sake.basamake.bsp

import java.nio.file.Files
import java.nio.file.Path
import munit.FunSuite

class BspRouterTest extends FunSuite {

  test("bootstrap cache — miss triggers walk, hit uses cached result") {
    withTempDir: root =>
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

  test("ground truth wins over bootstrap heuristic") {
    withTempDir: root =>
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

  test("route returns None when no .bsp found") {
    withTempDir: root =>
      val router = BspRouter()
      val subDir = Files.createDirectories(root.resolve("deep/nested"))
      val fileUri = subDir.resolve("orphan.scala").toUri.toString
      assertEquals(router.route(fileUri), None)
  }

  test("invalidateBootstrapCache clears cache — next route re-walks") {
    withTempDir: root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val connId = BspConnectionId("sbt-conn")
      val router = BspRouter()
      router.registerBspRoot(bspDir.toRealPath(), Set(connId))

      val subDir = Files.createDirectories(root.resolve("x/y"))
      val _ = router.route(subDir.resolve("test.scala").toUri.toString)
      router.invalidateBootstrapCache()
      router.unregisterBspRoot(bspDir.toRealPath(), connId)

      assertEquals(router.route(subDir.resolve("test.scala").toUri.toString), None,
        "After cache invalidation + root removal, should find nothing")
  }

  test("nearest .bsp wins — deeper .bsp beats shallower") {
    withTempDir: root =>
      val rootBspDir = Files.createDirectory(root.resolve(".bsp"))
      val subProjectDir = Files.createDirectories(root.resolve("sub"))
      val subBspDir = Files.createDirectory(subProjectDir.resolve(".bsp"))

      val rootConn = BspConnectionId("root-conn")
      val subConn = BspConnectionId("sub-conn")
      val router = BspRouter()
      router.registerBspRoot(rootBspDir.toRealPath(), Set(rootConn))
      router.registerBspRoot(subBspDir.toRealPath(), Set(subConn))

      val srcDir = Files.createDirectories(subProjectDir.resolve("src"))
      val fileUri = srcDir.resolve("SubTest.scala").toUri.toString
      assertEquals(router.route(fileUri), Some(subConn), "Nearest .bsp (sub/) should win")
  }

  test("unregistering one connection keeps other connections from same .bsp root") {
    withTempDir: root =>
      val bspDir = Files.createDirectory(root.resolve(".bsp"))
      val connA = BspConnectionId("conn-a")
      val connB = BspConnectionId("conn-b")
      val router = BspRouter()
      router.registerBspRoot(bspDir.toRealPath(), Set(connA))
      router.registerBspRoot(bspDir.toRealPath(), Set(connB))

      val srcDir = Files.createDirectories(root.resolve("src"))
      val fileUri = srcDir.resolve("A.scala").toUri.toString
      val _ = router.route(fileUri)

      router.unregisterBspRoot(bspDir.toRealPath(), connA)
      router.invalidateBootstrapCache()

      assertEquals(router.route(fileUri), Some(connB))
  }

  test("ground-truth overlap tie-break prefers nearest BSP root ancestor") {
    withTempDir: root =>
      val rootBspDir = Files.createDirectory(root.resolve(".bsp"))
      val subProjectDir = Files.createDirectories(root.resolve("sbt"))
      val subBspDir = Files.createDirectory(subProjectDir.resolve(".bsp"))

      val rootConn = BspConnectionId("root-conn")
      val subConn = BspConnectionId("sub-conn")
      val router = BspRouter()
      router.registerBspRoot(rootBspDir.toRealPath(), Set(rootConn))
      router.registerBspRoot(subBspDir.toRealPath(), Set(subConn))

      val sharedSource = List(root.toUri.toString)
      router.registerGroundTruth(rootConn, sharedSource)
      router.registerGroundTruth(subConn, sharedSource)

      val fileUri = subProjectDir.resolve("src/main/scala/Main.scala").toUri.toString
      assertEquals(router.route(fileUri), Some(subConn))
  }

  private def withTempDir[A](body: Path => A): A =
    val tmp = Files.createTempDirectory("bsprt-test-")
    try body(tmp)
    finally Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
}