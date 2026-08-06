package ba.sake.basamake.bsp

import java.nio.file.Files
import munit.FunSuite

class BspManagerRoutingTest extends FunSuite {

  test("two nested .bsp dirs → router.route selects different connections") {
    val root = Files.createTempDirectory("bsp-routing")
    try {
      val outerBsp = Files.createDirectories(root.resolve("outer/.bsp"))
      val innerBsp = Files.createDirectories(root.resolve("outer/inner/.bsp"))
      Files.writeString(outerBsp.resolve("sbt.json"),
        """{"name":"outer","version":"1","bspVersion":"2.1.0","languages":["scala"],"argv":["true"]}""")
      Files.writeString(innerBsp.resolve("mill.json"),
        """{"name":"inner","version":"1","bspVersion":"2.1.0","languages":["scala"],"argv":["true"]}""")

      val mgr = BspManager.forTesting(os.Path(root))
      mgr.initializeForTestingOnlyDiscover()

      val innerFile = innerBsp.getParent.resolve("src/Main.scala").toUri.toString
      val outerFile = outerBsp.getParent.resolve("other/Util.scala").toUri.toString

      val innerOpt = mgr.routeForTesting(innerFile)
      assert(innerOpt.isDefined, "inner file should route to a connection")
      val outerOpt = mgr.routeForTesting(outerFile)
      assert(outerOpt.isDefined, "outer file should route to a connection")
      assertNotEquals(innerOpt.get.value, outerOpt.get.value)
    } finally {
      import scala.jdk.CollectionConverters.*
      Files.walk(root).iterator.asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
    }
  }

  test("a .bsp dir deletion does not kill the debounce — a later new .bsp config is still attached") {
    val root = os.Path(Files.createTempDirectory("bsp-debounce"))
    val mgr = BspManager.forTesting(root)
    try {
      val proj = root / "proj"
      val bspDir = proj / ".bsp"
      os.makeDir.all(bspDir)
      val sbtJson = bspDir / "sbt.json"
      os.write.over(sbtJson,
        """{"name":"sbt","version":"1","bspVersion":"2.1.0","languages":["scala"],"argv":["true"]}""")
      mgr.initializeForTestingOnlyDiscover()

      val projFile = proj / "src/Main.scala"
      assertEquals(mgr.routeForTesting(projFile.toURI.toString), Some(BspConnectionId(sbtJson.toString)),
        "file under proj should route to the sbt connection")

      // watcher reports the whole .bsp dir + its config deleted
      os.remove.all(bspDir)
      mgr.onFileChanged(Set(bspDir, sbtJson))
      Thread.sleep(800) // let the debounce process the deletion

      // a new .bsp config appears at the workspace root
      val newBspDir = root / ".bsp"
      os.makeDir.all(newBspDir)
      val dederJson = newBspDir / "deder-bsp.json"
      os.write.over(dederJson,
        """{"name":"deder","version":"1","bspVersion":"2.1.0","languages":["scala"],"argv":["true"]}""")
      mgr.onFileChanged(Set(newBspDir, dederJson))
      Thread.sleep(800) // let the debounce attach the new config

      val newFile = root / "test-utils/src/Foo.scala"
      assertEquals(mgr.routeForTesting(newFile.toURI.toString), Some(BspConnectionId(dederJson.toString)),
        "new .bsp config must be attached even after a deleted-dir batch")
    } finally {
      mgr.shutdown()
      import scala.jdk.CollectionConverters.*
      Files.walk(root.toNIO).iterator.asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
    }
  }
}
