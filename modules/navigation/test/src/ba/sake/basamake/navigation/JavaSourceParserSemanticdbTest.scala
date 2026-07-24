package ba.sake.basamake.navigation

import java.util.concurrent.Executors
import munit.FunSuite

class JavaSourceParserSemanticdbTest extends FunSuite {

  test("java default-package class emits _empty_/ owner") {
    val definitions = JavaSourceParser(
      "class Foo { void run() {} }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    // No explicit constructor → no constructor symbol emitted
    assertEquals(symbols, Set(
      "_empty_/Foo#",
      "_empty_/Foo#run()."
    ), clues(definitions))
  }

  test("java package-scoped class with overloaded methods and constructors") {
    val definitions = JavaSourceParser(
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
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
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
    val definitions = JavaSourceParser(
      "package com.example;\ninterface Api { void apply(); }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Api#",
      "com/example/Api#apply()."
    ), clues(definitions))
  }

  test("java enum with constants") {
    val definitions = JavaSourceParser(
      "package com.example;\nenum Color { RED, GREEN }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    // Enum implicit constructor not emitted (compiler-generated)
    assertEquals(symbols, Set(
      "com/example/Color#",
      "com/example/Color#RED.",
      "com/example/Color#GREEN."
    ), clues(definitions))
  }

  test("java nested class with parent package owner") {
    val definitions = JavaSourceParser(
      "package com.example;\nclass Outer { class Inner { void run() {} } }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Outer#",
      "com/example/Outer#Inner#",
      "com/example/Outer#Inner#run()."
    ), clues(definitions))
  }

  test("java does not produce markerless or bare-name aliases") {
    val definitions = JavaSourceParser(
      "package com.example;\nclass Foo { void bar() {} }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    // No old-style dotted-owner keys like "Foo.bar" or "bar"
    assert(!symbols.contains("Foo.bar"), clues(symbols))
    assert(!symbols.contains("bar"), clues(symbols))
    assert(symbols.contains("com/example/Foo#"), clues(symbols))
    assert(symbols.contains("com/example/Foo#bar()."), clues(symbols))
  }

  test("concurrent Java parse yields consistent results") {
    val javaSource = """package com.example;
    |
    |import java.util.List;
    |
    |public class Foo {
    |    private int count;
    |    private String name;
    |
    |    public Foo(int count, String name) {
    |        this.count = count;
    |        this.name = name;
    |    }
    |
    |    public int getCount() { return count; }
    |    public void setCount(int c) { this.count = c; }
    |    public String getName() { return name; }
    |    public List<String> getItems() { return null; }
    |
    |    public static class Bar {
    |        private boolean flag;
    |        public Bar() { this.flag = true; }
    |        public boolean isFlag() { return flag; }
    |    }
    |
    |    public enum Color { RED, GREEN, BLUE }
    |}
    """.stripMargin
    val parallelism = 50
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    try {
      val futures = (1 to parallelism).map { _ =>
        executor.submit(() => JavaSourceParser(javaSource).parse().definitions)
      }
      val results = futures.map(_.get())
      val first = results.head
      assert(first.nonEmpty, "expected definitions, got empty — shared parser corrupt?")
      results.foreach { r =>
        assertEquals(r.size, first.size, "concurrent parses produced different def counts")
        assertEquals(r.map(_.name).sorted, first.map(_.name).sorted, "concurrent parses diverged")
      }
    } finally executor.shutdown()
  }
}
