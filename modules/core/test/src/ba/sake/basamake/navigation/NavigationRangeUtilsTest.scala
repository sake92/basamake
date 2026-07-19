package ba.sake.basamake.navigation

import munit.FunSuite
import org.eclipse.lsp4j.{Position, Range}

class NavigationRangeUtilsTest extends FunSuite {

  test("contains includes start and end boundaries") {
    val range = new Range(new Position(2, 3), new Position(2, 7))
    assert(NavigationRangeUtils.contains(range, new Position(2, 3)))
    assert(NavigationRangeUtils.contains(range, new Position(2, 7)))
  }

  test("contains rejects positions outside range") {
    val range = new Range(new Position(2, 3), new Position(2, 7))
    assert(!NavigationRangeUtils.contains(range, new Position(2, 2)))
    assert(!NavigationRangeUtils.contains(range, new Position(2, 8)))
  }
}
