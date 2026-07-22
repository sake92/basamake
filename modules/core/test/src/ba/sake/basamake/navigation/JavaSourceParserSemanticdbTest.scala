package ba.sake.basamake.navigation

import munit.FunSuite

class JavaSourceParserSemanticdbTest extends FunSuite {

  test("java default-package class emits _empty_/ owner") {
    val definitions = JavaSourceParser.extractDefinitions(
      "class Foo { void run() {} }"
    )
    val symbols = definitions.map(_.symbol).toSet
    // No explicit constructor → no constructor symbol emitted
    assertEquals(symbols, Set(
      "_empty_/Foo#",
      "_empty_/Foo#run()."
    ), clues(definitions))
  }

  test("java package-scoped class with overloaded methods and constructors") {
    val definitions = JavaSourceParser.extractDefinitions(
      """package com.example;
        |class Outer {
        |  int field;
        |  Outer() {}
        |  Outer(int x) {}
        |  void run() {}
        |  void run(int x) {}
        |  class Inner {}
        |}
        |""".stripMargin
    )
    val symbols = definitions.map(_.symbol).toSet
    assertEquals(symbols, Set(
      "com/example/Outer#",
      "com/example/Outer#field.",
      "com/example/Outer#`<init>`().",
      "com/example/Outer#`<init>`(+1).",
      "com/example/Outer#run().",
      "com/example/Outer#run(+1).",
      "com/example/Outer#Inner#"
    ), clues(definitions))
  }

  test("java interface emits type descriptor") {
    val definitions = JavaSourceParser.extractDefinitions(
      "package com.example;\ninterface Api { void apply(); }"
    )
    val symbols = definitions.map(_.symbol).toSet
    assertEquals(symbols, Set(
      "com/example/Api#",
      "com/example/Api#apply()."
    ), clues(definitions))
  }

  test("java enum with constants") {
    val definitions = JavaSourceParser.extractDefinitions(
      "package com.example;\nenum Color { RED, GREEN }"
    )
    val symbols = definitions.map(_.symbol).toSet
    // Enum implicit constructor not emitted (compiler-generated)
    assertEquals(symbols, Set(
      "com/example/Color#",
      "com/example/Color#RED.",
      "com/example/Color#GREEN."
    ), clues(definitions))
  }

  test("java nested class with parent package owner") {
    val definitions = JavaSourceParser.extractDefinitions(
      "package com.example;\nclass Outer { class Inner { void run() {} } }"
    )
    val symbols = definitions.map(_.symbol).toSet
    assertEquals(symbols, Set(
      "com/example/Outer#",
      "com/example/Outer#Inner#",
      "com/example/Outer#Inner#run()."
    ), clues(definitions))
  }

  test("java does not produce markerless or bare-name aliases") {
    val definitions = JavaSourceParser.extractDefinitions(
      "package com.example;\nclass Foo { void bar() {} }"
    )
    val symbols = definitions.map(_.symbol).toSet
    // No old-style dotted-owner keys like "Foo.bar" or "bar"
    assert(!symbols.contains("Foo.bar"), clues(symbols))
    assert(!symbols.contains("bar"), clues(symbols))
    assert(symbols.contains("com/example/Foo#"), clues(symbols))
    assert(symbols.contains("com/example/Foo#bar()."), clues(symbols))
  }
}
