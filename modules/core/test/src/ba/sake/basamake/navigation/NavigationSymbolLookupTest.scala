package ba.sake.basamake.navigation

import munit.FunSuite
import org.eclipse.lsp4j.{Location, Position, Range}

class NavigationSymbolLookupTest extends FunSuite {

  test("candidateSymbolKeys progressively trims symbol suffix") {
    val keys = NavigationSymbolLookup.candidateSymbolKeys("_empty_/foo.bar.baz().")
    assertEquals(keys, List("foo", "foo.bar", "foo.bar.baz"))
  }

  test("firstDefinitionInSlices returns first matching location using candidate keys") {
    val depUri = "file:///tmp/dep.scala"
    val depRange = new Range(new Position(0, 7), new Position(0, 14))
    val depSlice = SemanticdbFileSlice(
      sourceUri = depUri,
      occurrences = Nil,
      symbolDefinitions = Map("foo.bar" -> List(new Location(depUri, depRange))),
      symbolReferences = Map.empty,
      documentSymbols = Nil
    )

    val defn = NavigationSymbolLookup.firstDefinitionInSlices(
      symbol = "_empty_/foo.bar.baz().",
      slices = List(depSlice)
    )

    assert(defn.nonEmpty)
    assertEquals(defn.get.getUri, depUri)
  }
}
