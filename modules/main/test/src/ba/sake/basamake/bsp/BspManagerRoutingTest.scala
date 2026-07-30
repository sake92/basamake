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
}
