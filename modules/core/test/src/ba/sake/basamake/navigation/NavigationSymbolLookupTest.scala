package ba.sake.basamake.navigation

import munit.FunSuite
import org.eclipse.lsp4j.{Location, Position, Range}

class NavigationSymbolLookupTest extends FunSuite {

  test("candidateSymbolKeys returns markerless qualified key + dotted inits") {
    val keys = NavigationSymbolLookup.candidateSymbolKeys("_empty_/foo.bar.baz().")
    assertEquals(keys, List("_empty_/foo.bar.baz", "foo.bar", "foo.bar.baz"))
  }

  test("candidateSymbolKeys includes qualified key, excludes bare name in inits") {
    val keys = NavigationSymbolLookup.candidateSymbolKeys("com/example/Foo.bar().")
    assert(!keys.contains("Foo")) // bare name excluded from inits
    assert(keys.contains("com/example/Foo.bar")) // qualified markerless key
    assert(keys.contains("Foo.bar")) // owner.name included
  }

  test("candidateSymbolKeys for packageless dotted symbol") {
    val keys = NavigationSymbolLookup.candidateSymbolKeys("Foo.bar().")
    // clean == "Foo.bar", inits == ["Foo.bar"] -> distinct -> ["Foo.bar"]
    assertEquals(keys, List("Foo.bar"))
  }

  test("candidateSymbolKeys for single-segment symbol returns clean key") {
    // Was: expected Nil (bare name excluded). Now: clean key prepended.
    val keys = NavigationSymbolLookup.candidateSymbolKeys("Foo")
    assertEquals(keys, List("Foo"))
  }

  test("candidateSymbolKeys for scala/Unit# returns qualified markerless key") {
    val keys = NavigationSymbolLookup.candidateSymbolKeys("scala/Unit#")
    // clean = "scala/Unit", afterPackage = "Unit", single segment -> inits = Nil
    assertEquals(keys, List("scala/Unit"))
  }

  test("candidateSymbolKeys for scala/Predef.println(). returns qualified + dotted") {
    val keys = NavigationSymbolLookup.candidateSymbolKeys("scala/Predef.println().")
    // clean = "scala/Predef.println", inits = ["Predef.println"]
    assertEquals(keys, List("scala/Predef.println", "Predef.println"))
  }

  test("firstDefinitionInSlices matches scala/Unit# against dep slice keyed scala/Unit") {
    val depUri = "file:///tmp/scala/Unit.scala"
    val depRange = new Range(new Position(0, 0), new Position(0, 4))
    val depSlice = SemanticdbFileSlice(
      sourceUri = depUri,
      occurrences = Nil,
      symbolDefinitions = Map("scala/Unit" -> List(new Location(depUri, depRange))),
      symbolReferences = Map.empty,
      documentSymbols = Nil
    )

    val defn = NavigationSymbolLookup.firstDefinitionInSlices(
      symbol = "scala/Unit#",
      slices = List(depSlice)
    )

    assert(defn.nonEmpty)
    assertEquals(defn.get.getUri, depUri)
  }

  test("firstDefinitionInSlices matches scala/Predef.println(). against dep slice keyed scala/Predef.println") {
    val depUri = "file:///tmp/scala/Predef.scala"
    val depRange = new Range(new Position(100, 0), new Position(100, 7))
    val depSlice = SemanticdbFileSlice(
      sourceUri = depUri,
      occurrences = Nil,
      symbolDefinitions = Map("scala/Predef.println" -> List(new Location(depUri, depRange))),
      symbolReferences = Map.empty,
      documentSymbols = Nil
    )

    val defn = NavigationSymbolLookup.firstDefinitionInSlices(
      symbol = "scala/Predef.println().",
      slices = List(depSlice)
    )

    assert(defn.nonEmpty)
    assertEquals(defn.get.getUri, depUri)
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
