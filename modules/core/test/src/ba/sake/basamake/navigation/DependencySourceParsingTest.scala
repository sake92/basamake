package ba.sake.basamake.navigation

import munit.FunSuite

class DependencySourceParsingTest extends FunSuite {

  test("extractDefinitions dispatches to scala parser for .scala files") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.scala",
      "object Foo { def bar = 1; val baz = 2 }"
    )

    assertEquals(definitions.map(_.name), List("Foo", "bar", "baz"), clues(definitions))
    assert(definitions.exists(d => d.name == "Foo" && d.kind == org.eclipse.lsp4j.SymbolKind.Object), clues(definitions))
    assert(definitions.exists(d => d.name == "bar" && d.kind == org.eclipse.lsp4j.SymbolKind.Method), clues(definitions))
    assert(definitions.exists(d => d.name == "baz" && d.kind == org.eclipse.lsp4j.SymbolKind.Property), clues(definitions))
  }

  test("extractDefinitions dispatches to java parser for .java files") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.java",
      "class Foo { int x; void bar() {} }"
    )

    assert(definitions.exists(d => d.name == "Foo" && d.kind == org.eclipse.lsp4j.SymbolKind.Class), clues(definitions))
    assert(definitions.exists(d => d.name == "bar" && d.kind == org.eclipse.lsp4j.SymbolKind.Method), clues(definitions))
    assert(definitions.exists(d => d.name == "x" && d.kind == org.eclipse.lsp4j.SymbolKind.Field), clues(definitions))
  }

  test("extractDefinitions returns empty for unsupported file types") {
    val definitions = DependencySourceParsing.extractDefinitions("Foo.txt", "class Foo")
    assertEquals(definitions, List.empty)
  }

  test("extractDefinitions returns empty for parse errors") {
    val definitions = DependencySourceParsing.extractDefinitions("Broken.scala", "class class class")
    assertEquals(definitions, List.empty)
  }

  test("symbol includes package prefix for scala files with package") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.scala",
      "package com.example\nclass Foo { def bar = 1 }"
    )

    val fooDef = definitions.find(_.name == "Foo")
    assert(fooDef.exists(_.symbol == "com/example/Foo"), clues(fooDef))
    val barDef = definitions.find(_.name == "bar")
    assert(barDef.exists(_.symbol == "com/example/bar"), clues(barDef))
  }

  test("symbol is bare name for scala files without package") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.scala",
      "class Foo"
    )

    assertEquals(definitions.map(_.symbol), List("Foo"))
  }

  test("dependencyCacheKey includes maven coordinates when available") {
    val key = DependencySourceParsing.dependencyCacheKey(
      "jar:file:///tmp/maven2/com/lihaoyi/upickle_3/4.0.0/upickle_3-4.0.0-sources.jar!/upickle/Api.scala"
    )

    assert(key.startsWith("com.lihaoyi-upickle_3-4.0.0-"), clues(key))
    assert(key.matches(".*-[0-9a-f]{8}$"), clues(key))
  }
}
