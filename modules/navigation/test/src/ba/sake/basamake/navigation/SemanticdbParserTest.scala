package ba.sake.basamake.navigation

import scala.meta.internal.semanticdb
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import munit.FunSuite

class SemanticdbParserTest extends FunSuite {

  test("parses definitions with name and kind from SymbolInformation") {
    val doc = semanticdb.TextDocument(
      uri = "Foo.scala",
      language = semanticdb.Language.SCALA,
      occurrences = Seq(
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(0, 6, 0, 9)),
          symbol = "com/example/Foo#",
          role = semanticdb.SymbolOccurrence.Role.DEFINITION
        ),
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(1, 6, 1, 9)),
          symbol = "com/example/Foo#run().",
          role = semanticdb.SymbolOccurrence.Role.DEFINITION
        )
      ),
      symbols = Seq(
        semanticdb.SymbolInformation(
          symbol = "com/example/Foo#",
          language = semanticdb.Language.SCALA,
          kind = Kind.CLASS,
          displayName = "Foo"
        ),
        semanticdb.SymbolInformation(
          symbol = "com/example/Foo#run().",
          language = semanticdb.Language.SCALA,
          kind = Kind.METHOD,
          displayName = "run"
        )
      )
    )
    val td = new semanticdb.TextDocuments(List(doc))
    val result = SemanticdbParser(td.toByteArray).parse()

    assertEquals(result.definitions.size, 2, clues(result.definitions))

    val fooDef = result.definitions.find(_.name == "Foo").get
    assertEquals(fooDef.kind, org.eclipse.lsp4j.SymbolKind.Class, clues(fooDef))
    assertEquals(fooDef.symbol.value, "com/example/Foo#", clues(fooDef))

    val runDef = result.definitions.find(_.name == "run").get
    assertEquals(runDef.kind, org.eclipse.lsp4j.SymbolKind.Method, clues(runDef))
    assertEquals(runDef.symbol.value, "com/example/Foo#run().", clues(runDef))
  }

  test("parses references") {
    val doc = semanticdb.TextDocument(
      uri = "Main.scala",
      language = semanticdb.Language.SCALA,
      occurrences = Seq(
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(0, 6, 0, 9)),
          symbol = "com/example/Foo#",
          role = semanticdb.SymbolOccurrence.Role.DEFINITION
        ),
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(2, 4, 2, 7)),
          symbol = "com/example/Foo#",
          role = semanticdb.SymbolOccurrence.Role.REFERENCE
        ),
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(3, 0, 3, 3)),
          symbol = "com/example/Foo#run().",
          role = semanticdb.SymbolOccurrence.Role.REFERENCE
        )
      ),
      symbols = Seq(
        semanticdb.SymbolInformation(
          symbol = "com/example/Foo#",
          language = semanticdb.Language.SCALA,
          kind = Kind.CLASS,
          displayName = "Foo"
        )
      )
    )
    val td = new semanticdb.TextDocuments(List(doc))
    val result = SemanticdbParser(td.toByteArray).parse()

    assertEquals(result.definitions.size, 1, clues(result.definitions))
    assertEquals(result.references.size, 2, clues(result.references))
    assertEquals(result.references.map(_.symbol.value).toSet, Set(
      "com/example/Foo#",
      "com/example/Foo#run()."
    ))
  }

  test("filters empty-symbol occurrences") {
    val doc = semanticdb.TextDocument(
      uri = "Foo.scala",
      language = semanticdb.Language.SCALA,
      occurrences = Seq(
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(0, 0, 0, 3)),
          symbol = "", // unresolvable SUID
          role = semanticdb.SymbolOccurrence.Role.REFERENCE
        ),
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(1, 0, 1, 3)),
          symbol = "com/example/Foo#",
          role = semanticdb.SymbolOccurrence.Role.REFERENCE
        )
      ),
      symbols = Seq.empty
    )
    val td = new semanticdb.TextDocuments(List(doc))
    val result = SemanticdbParser(td.toByteArray).parse()

    // Empty symbol filtered, only one reference emitted
    assertEquals(result.references.size, 1)
    assertEquals(result.references.head.symbol.value, "com/example/Foo#")
  }

  test("handles multiple documents in one file") {
    val doc1 = semanticdb.TextDocument(
      uri = "Foo.scala",
      language = semanticdb.Language.SCALA,
      occurrences = Seq(
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(0, 0, 0, 3)),
          symbol = "com/example/Foo#",
          role = semanticdb.SymbolOccurrence.Role.DEFINITION
        )
      ),
      symbols = Seq(
        semanticdb.SymbolInformation(symbol = "com/example/Foo#", language = semanticdb.Language.SCALA, kind = Kind.CLASS, displayName = "Foo")
      )
    )
    val doc2 = semanticdb.TextDocument(
      uri = "Bar.scala",
      language = semanticdb.Language.SCALA,
      occurrences = Seq(
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(0, 0, 0, 3)),
          symbol = "com/example/Bar#",
          role = semanticdb.SymbolOccurrence.Role.DEFINITION
        )
      ),
      symbols = Seq(
        semanticdb.SymbolInformation(symbol = "com/example/Bar#", language = semanticdb.Language.SCALA, kind = Kind.CLASS, displayName = "Bar")
      )
    )
    val td = new semanticdb.TextDocuments(List(doc1, doc2))
    val result = SemanticdbParser(td.toByteArray).parse()

    assertEquals(result.definitions.size, 2)
    assertEquals(result.definitions.map(_.name).toSet, Set("Foo", "Bar"))
  }

  test("extractName falls back to symbol parsing when no displayName") {
    val doc = semanticdb.TextDocument(
      uri = "Foo.scala",
      language = semanticdb.Language.SCALA,
      occurrences = Seq(
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(0, 0, 0, 3)),
          symbol = "com/example/Outer#Inner#",
          role = semanticdb.SymbolOccurrence.Role.DEFINITION
        )
      ),
      symbols = Seq(
        semanticdb.SymbolInformation(
          symbol = "com/example/Outer#Inner#",
          language = semanticdb.Language.SCALA,
          kind = Kind.CLASS,
          displayName = "" // empty displayName
        )
      )
    )
    val td = new semanticdb.TextDocuments(List(doc))
    val result = SemanticdbParser(td.toByteArray).parse()

    assertEquals(result.definitions.size, 1)
    assertEquals(result.definitions.head.name, "Inner")
  }

  test("handles operator names from symbol") {
    val doc = semanticdb.TextDocument(
      uri = "Foo.scala",
      language = semanticdb.Language.SCALA,
      occurrences = Seq(
        semanticdb.SymbolOccurrence(
          range = Some(semanticdb.Range(0, 0, 0, 3)),
          symbol = "scala/Any#`==`().",
          role = semanticdb.SymbolOccurrence.Role.DEFINITION
        )
      ),
      symbols = Seq(
        semanticdb.SymbolInformation(
          symbol = "scala/Any#`==`().",
          language = semanticdb.Language.SCALA,
          kind = Kind.METHOD,
          displayName = "=="
        )
      )
    )
    val td = new semanticdb.TextDocuments(List(doc))
    val result = SemanticdbParser(td.toByteArray).parse()

    assertEquals(result.definitions.head.name, "==")
  }
}
