package ba.sake.basamake.navigation

import munit.FunSuite
import org.eclipse.lsp4j.{Location, Position, Range}

class NavigationSymbolLookupTest extends FunSuite {

  test("isLocalSymbol matches compiler-produced local symbols") {
    assert(NavigationSymbolLookup.isLocalSymbol("local0"))
    assert(NavigationSymbolLookup.isLocalSymbol("local1"))
    assert(NavigationSymbolLookup.isLocalSymbol("local2+1"))
  }

  test("isLocalSymbol rejects global symbols starting with 'local'") {
    assert(!NavigationSymbolLookup.isLocalSymbol("localDate#"))
    assert(!NavigationSymbolLookup.isLocalSymbol("localMethod()."))
    assert(!NavigationSymbolLookup.isLocalSymbol("localVar."))
    assert(!NavigationSymbolLookup.isLocalSymbol("mylocal"))
  }

  test("isLocalSymbol rejects empty and descriptive strings") {
    assert(!NavigationSymbolLookup.isLocalSymbol(""))
    assert(!NavigationSymbolLookup.isLocalSymbol("local"))
    assert(!NavigationSymbolLookup.isLocalSymbol("localabc"))
  }

  test("firstDefinitionInSlices matches exact symbol") {
    val depUri = "file:///tmp/scala/Unit.scala"
    val depRange = new Range(new Position(0, 0), new Position(0, 4))
    val depSlice = SemanticdbFileSlice(
      sourceUri = depUri,
      occurrences = Nil,
      symbolDefinitions = Map("scala/Unit#" -> List(new Location(depUri, depRange))),
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

  test("firstDefinitionInSlices does NOT match via stripped keys") {
    val depUri = "file:///tmp/dep.scala"
    val depSlice = SemanticdbFileSlice(
      sourceUri = depUri,
      occurrences = Nil,
      symbolDefinitions = Map("upickle/Api#write()." -> List(new Location(depUri, new Range(new Position(0, 0), new Position(0, 5))))),
      symbolReferences = Map.empty,
      documentSymbols = Nil
    )

    // Stripped key should NOT match
    val defn = NavigationSymbolLookup.firstDefinitionInSlices(
      symbol = "upickle/Api.write",  // not in the index
      slices = List(depSlice)
    )
    assert(defn.isEmpty)

    // Exact key should match
    val exact = NavigationSymbolLookup.firstDefinitionInSlices(
      symbol = "upickle/Api#write().",
      slices = List(depSlice)
    )
    assert(exact.nonEmpty)
  }

  test("firstDefinitionInSlices matches overloaded method with exact disambiguator") {
    val depUri = "file:///tmp/pkg/Foo.scala"
    val exactSlice = SemanticdbFileSlice(
      sourceUri = depUri,
      occurrences = Nil,
      symbolDefinitions = Map(
        "pkg/Owner#run()." -> List(new Location(depUri, new Range(new Position(5, 0), new Position(5, 3)))),
        "pkg/Owner#run(+1)." -> List(new Location(depUri, new Range(new Position(6, 0), new Position(6, 3))))
      ),
      symbolReferences = Map.empty,
      documentSymbols = Nil
    )

    // First overload matches only run().
    val first = NavigationSymbolLookup.firstDefinitionInSlices("pkg/Owner#run().", List(exactSlice))
    assert(first.nonEmpty)

    // Second overload matches only run(+1).
    val second = NavigationSymbolLookup.firstDefinitionInSlices("pkg/Owner#run(+1).", List(exactSlice))
    assert(second.nonEmpty)

    // Stripped key does not match
    val stripped = NavigationSymbolLookup.firstDefinitionInSlices("pkg/Owner.run", List(exactSlice))
    assert(stripped.isEmpty)
  }

  test("firstDefinition returns empty when no exact match found") {
    val depUri = "file:///tmp/A.scala"
    val depSlice = SemanticdbFileSlice(
      sourceUri = depUri,
      occurrences = Nil,
      symbolDefinitions = Map("pkg/A#" -> List(new Location(depUri, new Range(new Position(0, 0), new Position(0, 1))))),
      symbolReferences = Map.empty,
      documentSymbols = Nil
    )

    val result = NavigationSymbolLookup.firstDefinition(
      symbols = List("pkg/A#nonexistent()."),
      currentFileUri = depUri,
      workspaceSlices = Nil,
      dependencySlices = List(depSlice)
    )
    assert(result.isEmpty)
  }
}
