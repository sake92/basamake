package ba.sake.basamake.navigation

import munit.FunSuite

class DependencySourceParsingTest extends FunSuite {

  test("extractDefinitions parses scala definitions with symbol kinds") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "object Foo { def bar = 1; val baz = 2 }"
    )

    assertEquals(definitions.map(_.name), List("Foo", "bar", "baz"), clues(definitions))
    assert(definitions.exists(d => d.name == "Foo" && d.kind == org.eclipse.lsp4j.SymbolKind.Object), clues(definitions))
    assert(definitions.exists(d => d.name == "bar" && d.kind == org.eclipse.lsp4j.SymbolKind.Method), clues(definitions))
  }

  test("dependencyCacheKey includes maven coordinates when available") {
    val key = DependencySourceParsing.dependencyCacheKey(
      "jar:file:///tmp/maven2/com/lihaoyi/upickle_3/4.0.0/upickle_3-4.0.0-sources.jar!/upickle/Api.scala"
    )

    assert(key.startsWith("com.lihaoyi-upickle_3-4.0.0-"), clues(key))
    assert(key.matches(".*-[0-9a-f]{8}$"), clues(key))
  }
}
