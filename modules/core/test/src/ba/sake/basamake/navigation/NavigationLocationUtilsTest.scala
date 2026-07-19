package ba.sake.basamake.navigation

import munit.FunSuite
import org.eclipse.lsp4j.{Location, Position, Range}

class NavigationLocationUtilsTest extends FunSuite {

  test("postProcessLocations drops non-existing ghost paths") {
    val tmp = os.temp.dir(prefix = "nav-loc")
    try {
      val existingPath = tmp / "sbt" / "src" / "main" / "scala" / "utils.scala"
      os.makeDir.all(existingPath / os.up)
      os.write(existingPath, "object utils {}")
      val existingUri = existingPath.toNIO.toUri.toString
      val ghostUri = existingUri.replace("/sbt/", "/")
      val range = new Range(new Position(1, 2), new Position(1, 6))

      val processed = NavigationLocationUtils.postProcessLocations(
        List(new Location(existingUri, range), new Location(ghostUri, range))
      )

      assertEquals(processed.map(_.getUri), List(existingUri))
    } finally {
      os.remove.all(tmp)
    }
  }

  test("postProcessLocations normalizes equivalent uris and deduplicates") {
    val tmp = os.temp.dir(prefix = "nav-loc-norm")
    try {
      val file = tmp / "Main.scala"
      os.write(file, "object Main")
      val canonical = file.toNIO.toUri.toString
      val nonCanonical = canonical.replace("file:///", "file:/")
      val range = new Range(new Position(0, 0), new Position(0, 4))

      val processed = NavigationLocationUtils.postProcessLocations(
        List(new Location(nonCanonical, range), new Location(canonical, range))
      )

      assertEquals(processed.size, 1)
      assertEquals(processed.head.getUri, canonical)
    } finally {
      os.remove.all(tmp)
    }
  }
}
