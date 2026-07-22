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

    // canonical semanticdb keys
    val barDef = definitions.find(_.name == "bar")
    assert(barDef.exists(_.symbol == "_empty_/Foo.bar()."), clues(barDef))
    val bazDef = definitions.find(_.name == "baz")
    assert(bazDef.exists(_.symbol == "_empty_/Foo.baz."), clues(bazDef))
  }

  test("extractDefinitions dispatches to java parser for .java files") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.java",
      "class Foo { int x; void bar() {} }"
    )

    assert(definitions.exists(d => d.name == "Foo" && d.kind == org.eclipse.lsp4j.SymbolKind.Class), clues(definitions))
    assert(definitions.exists(d => d.name == "bar" && d.kind == org.eclipse.lsp4j.SymbolKind.Method), clues(definitions))
    assert(definitions.exists(d => d.name == "x" && d.kind == org.eclipse.lsp4j.SymbolKind.Field), clues(definitions))

    // Java symbols still old-style until Task 3
    val barDef = definitions.find(_.name == "bar")
    assert(barDef.exists(_.symbol == "Foo.bar"), clues(barDef))
    val xDef = definitions.find(_.name == "x")
    assert(xDef.exists(_.symbol == "Foo.x"), clues(xDef))
  }

  test("extractDefinitions returns empty for unsupported file types") {
    val definitions = DependencySourceParsing.extractDefinitions("Foo.txt", "class Foo")
    assertEquals(definitions, List.empty)
  }

  test("extractDefinitions returns empty for parse errors") {
    val definitions = DependencySourceParsing.extractDefinitions("Broken.scala", "class class class")
    assertEquals(definitions, List.empty)
  }

  test("symbol includes package prefix and owner for scala files with package") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.scala",
      "package com.example\nclass Foo { def bar = 1 }"
    )

    val fooDef = definitions.find(_.name == "Foo")
    assert(fooDef.exists(_.symbol == "com/example/Foo#"), clues(fooDef))
    val barDef = definitions.find(_.name == "bar")
    assert(barDef.exists(_.symbol == "com/example/Foo#bar()."), clues(barDef))
  }

  test("symbol is bare name for scala files without package") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.scala",
      "class Foo"
    )

    // class FoFo + primary constructor (constructors always emitted)
    assertEquals(definitions.map(_.symbol), List("_empty_/Foo#", "_empty_/Foo#`<init>`()."))
  }

  test("parses Scala 2.13 syntax via dialect fallback") {
    val scala2Code = "class Foo { def foo: Unit = { println(\"hello\") } }"
    val definitions = DependencySourceParsing.extractDefinitions("Foo.scala", scala2Code)
    assert(definitions.nonEmpty, clues(definitions))
    assert(definitions.exists(_.name == "Foo"), clues(definitions))
    assert(definitions.exists(d => d.name == "foo" && d.symbol == "_empty_/Foo#foo()."), clues(definitions))
  }

  test("parses Scala 3 givens and enums") {
    val scala3Code =
      """package pkg
        |enum Color { case Red, Blue }
        |given x: Int = 1
        |""".stripMargin
    val definitions = DependencySourceParsing.extractDefinitions("Color.scala", scala3Code)

    assert(definitions.exists(d => d.name == "Color" && d.kind == org.eclipse.lsp4j.SymbolKind.Enum), clues(definitions))
    assert(definitions.exists(d => d.name == "Red" && d.kind == org.eclipse.lsp4j.SymbolKind.EnumMember), clues(definitions))
    assert(definitions.exists(d => d.name == "Blue" && d.kind == org.eclipse.lsp4j.SymbolKind.EnumMember), clues(definitions))
    assert(definitions.exists(d => d.name == "x"), clues(definitions))

    // enum cases have canonical symbols
    val redDef = definitions.find(_.name == "Red")
    assert(redDef.exists(_.symbol == "pkg/Color#Red."), clues(redDef))
  }

  test("skips local defs inside method bodies") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.scala",
      "object Foo { def bar = { val local = 1; local } }"
    )

    // local should NOT be indexed
    assert(!definitions.exists(_.name == "local"), clues(definitions))
    // bar and Foo should be indexed
    assert(definitions.exists(_.name == "Foo"), clues(definitions))
    assert(definitions.exists(_.name == "bar"), clues(definitions))
  }

  test("handles deeply nested owners") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Nested.scala",
      "package com.example\nclass Outer { object Inner { def baz = 1 } }"
    )

    val bazDef = definitions.find(_.name == "baz")
    assert(bazDef.exists(_.symbol == "com/example/Outer#Inner.baz()."), clues(bazDef))
  }

  test("handles brace-delimited nested packages") {
    val code = "package com { package example { class Foo { def bar = 1 } } }"
    val definitions = DependencySourceParsing.extractDefinitions("Foo.scala", code)

    val fooDef = definitions.find(_.name == "Foo")
    assert(fooDef.exists(_.symbol == "com/example/Foo#"), clues(fooDef))
    val barDef = definitions.find(_.name == "bar")
    assert(barDef.exists(_.symbol == "com/example/Foo#bar()."), clues(barDef))
  }

  test("Java methods and fields have owner-qualified symbols") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.java",
      "class Foo { void bar() {} int x; }"
    )

    val barDef = definitions.find(_.name == "bar")
    assert(barDef.exists(_.symbol == "Foo.bar"), clues(barDef))
    val xDef = definitions.find(_.name == "x")
    assert(xDef.exists(_.symbol == "Foo.x"), clues(xDef))
  }

  test("Java nested classes track owner chain") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Nested.java",
      "class Outer { class Inner { void run() {} } }"
    )

    val runDef = definitions.find(_.name == "run")
    assert(runDef.exists(_.symbol == "Outer.Inner.run"), clues(runDef))
  }

  test("Java enum constants have owner-qualified symbols") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Color.java",
      "enum Color { RED, GREEN }"
    )

    val redDef = definitions.find(_.name == "RED")
    assert(redDef.exists(_.symbol == "Color.RED"), clues(redDef))
    assert(redDef.exists(_.kind == org.eclipse.lsp4j.SymbolKind.EnumMember), clues(redDef))
  }

  test("dependencyCacheKey includes maven coordinates when available") {
    val key = DependencySourceParsing.dependencyCacheKey(
      "jar:file:///tmp/maven2/com/lihaoyi/upickle_3/4.0.0/upickle_3-4.0.0-sources.jar!/upickle/Api.scala"
    )

    assert(key.startsWith("com.lihaoyi-upickle_3-4.0.0-"), clues(key))
    assert(key.matches(".*-[0-9a-f]{8}$"), clues(key))
  }

  test("package object members indexed under <pkg>/package.* (Bug A)") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "package.scala",
      "package object scala { type Seq[+A] = collection.immutable.Seq[A]; val Seq = collection.immutable.Seq }"
    )

    val seqDefs = definitions.filter(_.name == "Seq")
    assertEquals(seqDefs.size, 2, clues(definitions))
    assertEquals(seqDefs.map(_.symbol).toSet, Set("scala/package.Seq#", "scala/package.Seq."))
  }

  test("top-level defs in package.scala indexed under <pkg>/package.* (Bug B)") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "package.scala",
      "package scala\npackage compiletime\ndef error(msg: String): Nothing = ???"
    )

    val errorDef = definitions.find(_.name == "error")
    assert(errorDef.nonEmpty, clues(definitions))
    // package.scala → top-level defs wrapped under package.
    assertEquals(errorDef.get.symbol, "scala/compiletime/package.error().", clues(errorDef))
  }

  test("top-level defs in X.scala indexed under <pkg>/X$package.*, classes unchanged (Bug C)") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.scala",
      "package a.b\ndef foo = 1\nclass C"
    )

    val fooDef = definitions.find(_.name == "foo")
    assert(fooDef.nonEmpty, clues(definitions))
    assertEquals(fooDef.get.symbol, "a/b/Foo$package.foo().", clues(fooDef))

    val cDef = definitions.find(_.name == "C")
    assert(cDef.nonEmpty, clues(definitions))
    assertEquals(cDef.get.symbol, "a/b/C#", clues(cDef))
  }

  test("empty fileName → no top-level wrapping (guards test-compat path)") {
    val definitions = ScalaSourceParser.extractDefinitions(
      "package a.b\ndef foo = 1",
      ""
    )

    val fooDef = definitions.find(_.name == "foo")
    assert(fooDef.nonEmpty, clues(definitions))
    assertEquals(fooDef.get.symbol, "a/b/foo().", clues(fooDef))
  }

  // ── Canonical SemanticDB key contract tests (will fail until Tasks 2-3) ──

  test("scala package-scoped class emits canonical keys") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Outer.scala",
      """package com.example
        |class Outer {
        |  val field = 1
        |  def run(): Unit = ()
        |  def run(x: Int): Unit = ()
        |  class Inner
        |}
        |object Api { def apply(): Api = new Api }
        |""".stripMargin
    )

    val symbols = definitions.map(_.symbol).toSet
    assertEquals(symbols, Set(
      "com/example/Outer#",
      "com/example/Outer#`<init>`().",
      "com/example/Outer#field.",
      "com/example/Outer#run().",
      "com/example/Outer#run(+1).",
      "com/example/Outer#Inner#",
      "com/example/Outer#Inner#`<init>`().",
      "com/example/Api.",
      "com/example/Api.apply()."
    ), clues(definitions))
  }

  test("scala default-package class emits _empty_/ owner") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.scala",
      "class Foo { def run = 1 }"
    )

    val symbols = definitions.map(_.symbol).toSet
    assertEquals(symbols, Set(
      "_empty_/Foo#",
      "_empty_/Foo#`<init>`().",
      "_empty_/Foo#run()."
    ), clues(definitions))
  }

  test("java default-package class emits _empty_/ owner") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.java",
      "class Foo { void run() {} }"
    )

    val symbols = definitions.map(_.symbol).toSet
    assertEquals(symbols, Set(
      "_empty_/Foo#",
      "_empty_/Foo#run()."
    ), clues(definitions))
  }

  test("scala package object members emit canonical keys with package. owner") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "package.scala",
      "package object scala { type Seq[+A] = collection.immutable.Seq[A]; val Seq = collection.immutable.Seq }"
    )

    val seqDefs = definitions.filter(_.name == "Seq")
    assertEquals(seqDefs.size, 2, clues(definitions))
    assertEquals(seqDefs.map(_.symbol).toSet, Set(
      "scala/package.Seq#",  // type alias → type descriptor
      "scala/package.Seq."   // val → term descriptor
    ), clues(seqDefs))
  }

  test("scala top-level defs under X$package owner emit canonical keys") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.scala",
      "package a.b\ndef foo = 1\nclass C"
    )

    val fooDef = definitions.find(_.name == "foo")
    assert(fooDef.nonEmpty, clues(definitions))
    assertEquals(fooDef.get.symbol, "a/b/Foo$package.foo().", clues(fooDef))

    val cDef = definitions.find(_.name == "C")
    assert(cDef.nonEmpty, clues(definitions))
    assertEquals(cDef.get.symbol, "a/b/C#", clues(cDef))
  }

  test("scala named given emits term descriptor") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Givens.scala",
      "package pkg\ngiven x: Int = 1"
    )

    val xDef = definitions.find(_.name == "x")
    assert(xDef.nonEmpty, clues(definitions))
    // given in Givens.scala → wrapped under Givens$package
    assertEquals(xDef.get.symbol, "pkg/Givens$package.x.", clues(xDef))
  }

  test("scala constructors: primary and overloaded") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Foo.scala",
      """package com.example
        |class Foo(x: Int) {
        |  def this() = this(0)
        |  def this(y: Int, z: Int) = this(y + z)
        |}
        |""".stripMargin
    )

    val symbols = definitions.map(_.symbol).toSet
    assertEquals(symbols, Set(
      "com/example/Foo#",
      "com/example/Foo#`<init>`().",
      "com/example/Foo#`<init>`(+1).",
      "com/example/Foo#`<init>`(+2)."
    ), clues(definitions))
  }

  test("scala nested and operator symbols") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Ops.scala",
      """package com.example
        |class Outer {
        |  object Inner {
        |    def ++(x: Int): Int = x
        |    def ++(x: Int, y: Int): Int = x + y
        |  }
        |}
        |""".stripMargin
    )

    val symbols = definitions.map(_.symbol).toSet
    assertEquals(symbols, Set(
      "com/example/Outer#",
      "com/example/Outer#`<init>`().",
      "com/example/Outer#Inner.",
      "com/example/Outer#Inner.`++`().",
      "com/example/Outer#Inner.`++`(+1)."
    ), clues(definitions))
  }
}
