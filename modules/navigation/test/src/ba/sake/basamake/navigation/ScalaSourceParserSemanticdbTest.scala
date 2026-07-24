package ba.sake.basamake.navigation

import java.util.concurrent.Executors
import munit.FunSuite

class ScalaSourceParserSemanticdbTest extends FunSuite {

  test("scala default-package class emits _empty_/ owner with primary ctor") {
    val definitions = ScalaSourceParser(
      "class Foo { def run = 1 }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    // Scala always emits primary constructor
    assertEquals(symbols, Set(
      "_empty_/Foo#",
      "_empty_/Foo#`<init>`().",
      "_empty_/Foo#run()."
    ), clues(definitions))
  }

  test("scala package-scoped class with overloaded methods and constructors") {
    val definitions = ScalaSourceParser(
      """package com.example
        |class Outer {
        |  val field = 1
        |  def run(): Unit = ()
        |  def run(x: Int): Unit = ()
        |  class Inner
        |}
        |""".stripMargin
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Outer#",
      "com/example/Outer#`<init>`().",
      "com/example/Outer#field.",
      "com/example/Outer#run().",
      "com/example/Outer#run(+1).",
      "com/example/Outer#Inner#",
      "com/example/Outer#Inner#`<init>`()."
    ), clues(definitions))
  }

  test("scala trait emits type descriptor") {
    val definitions = ScalaSourceParser(
      "package com.example\ntrait Api { def apply(): Unit }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Api#",
      "com/example/Api#apply()."
    ), clues(definitions))
  }

  test("scala object emits term descriptor") {
    val definitions = ScalaSourceParser(
      "package com.example\nobject Api { def apply(): Int = 1 }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Api.",
      "com/example/Api.apply()."
    ), clues(definitions))
  }

  test("scala enum with cases") {
    val definitions = ScalaSourceParser(
      "package com.example\nenum Color { case Red, Green }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Color#",
      "com/example/Color#`<init>`().",
      "com/example/Color#Red.",
      "com/example/Color#Green."
    ), clues(definitions))
  }

  test("scala enum with repeated case") {
    val definitions = ScalaSourceParser(
      "package com.example\nenum Direction { case North, South, East, West }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Direction#",
      "com/example/Direction#`<init>`().",
      "com/example/Direction#North.",
      "com/example/Direction#South.",
      "com/example/Direction#East.",
      "com/example/Direction#West."
    ), clues(definitions))
  }

  test("scala nested class with parent package owner") {
    val definitions = ScalaSourceParser(
      "package com.example\nclass Outer { class Inner { def run = 1 } }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Outer#",
      "com/example/Outer#`<init>`().",
      "com/example/Outer#Inner#",
      "com/example/Outer#Inner#`<init>`().",
      "com/example/Outer#Inner#run()."
    ), clues(definitions))
  }

  test("scala operator methods are backtick-wrapped") {
    val definitions = ScalaSourceParser(
      "package com.example\nclass Ops { def ++(x: Int): Int = x; def ++(x: Int, y: Int): Int = x + y }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Ops#",
      "com/example/Ops#`<init>`().",
      "com/example/Ops#`++`().",
      "com/example/Ops#`++`(+1)."
    ), clues(definitions))
  }

  test("scala constructors: primary and two secondary") {
    val definitions = ScalaSourceParser(
      """package com.example
        |class Foo(x: Int) {
        |  def this() = this(0)
        |  def this(y: Int, z: Int) = this(y + z)
        |}
        |""".stripMargin
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Foo#",
      "com/example/Foo#`<init>`().",
      "com/example/Foo#`<init>`(+1).",
      "com/example/Foo#`<init>`(+2)."
    ), clues(definitions))
  }

  test("scala does not produce markerless or bare-name aliases") {
    val definitions = ScalaSourceParser(
      "package com.example\nclass Foo { def bar(): Unit = () }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    // No old-style dotted-owner keys
    assert(!symbols.contains("Foo.bar"), clues(symbols))
    assert(!symbols.contains("bar"), clues(symbols))
    assert(symbols.contains("com/example/Foo#"), clues(symbols))
    assert(symbols.contains("com/example/Foo#bar()."), clues(symbols))
  }

  test("scala top-level defs wrapped under X$package, classes unchanged") {
    val definitions = ScalaSourceParser(
      str = """package a.b
        |def foo = 1
        |class C
        |""".stripMargin,
      fileName = "Foo.scala"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "a/b/C#",
      "a/b/C#`<init>`().",
      "a/b/Foo$package.foo()."
    ), clues(definitions))
  }

  test("scala package object members under package.") {
    val definitions = ScalaSourceParser(
      """package object scala {
        |  type Seq[+A] = collection.immutable.Seq[A]
        |  val Seq = collection.immutable.Seq
        |}
        |""".stripMargin
    ).parse().definitions
    val seqDefs = definitions.filter(_.name == "Seq")
    assertEquals(seqDefs.size, 2, clues(definitions))
    assertEquals(seqDefs.map(_.symbol.value).toSet, Set(
      "scala/package.Seq#",
      "scala/package.Seq."
    ), clues(seqDefs))
  }

  // this is semanticdb convention for scala3 top-level defs,
  // it puts Filename$package as the owner of top-level defs
  test("scala named given emits term descriptor") {
    val definitions = ScalaSourceParser(
      str = """package pkg
        |given x: Int = 1
        |given stringList: List[String] = Nil
        |""".stripMargin,
      fileName = "Givens.scala"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "pkg/Givens$package.x.",
      "pkg/Givens$package.stringList."
    ), clues(definitions))
  }

  test("scala type alias emits type descriptor") {
    val definitions = ScalaSourceParser(
      "package com.example\nclass Wrapper { type T = Int }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Wrapper#",
      "com/example/Wrapper#`<init>`().",
      "com/example/Wrapper#T#"
    ), clues(definitions))
  }

  test("scala references: same-file type and term refs") {
    val result = ScalaSourceParser(
      """package com.example
        |class Foo
        |object Bar {
        |  val x: Foo = new Foo
        |}
        |""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    // Should have references to com/example/Foo# from type annotation and new
    assert(refSymbols.contains("com/example/Foo#"),
      s"Expected reference to Foo#, got: $refSymbols")
  }

  test("scala references: explicit imports") {
    val result = ScalaSourceParser(
      """package com.example
        |import scala.collection.immutable.List
        |import java.util.Map
        |class Wrapper(list: List[Int], map: Map[String, Int])
        |""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    // Should have references to imported types
    assert(refSymbols.contains("scala/collection/immutable/List#"),
      s"Expected import ref to List#, got: $refSymbols")
    assert(refSymbols.contains("java/util/Map#"),
      s"Expected import ref to Map#, got: $refSymbols")
  }

  test("scala no false positives for local values inside method bodies") {
    val definitions = ScalaSourceParser(
      """package com.example
        |object Foo {
        |  def bar = { val local = 1; local }
        |}
        |""".stripMargin
    ).parse().definitions
    val names = definitions.map(_.name).toSet
    // local val should NOT be indexed as a definition
    assert(!names.contains("local"), clues(names))
    assert(names.contains("Foo"), clues(names))
    assert(names.contains("bar"), clues(names))
  }

  test("scala deeply nested package declarations") {
    val definitions = ScalaSourceParser(
      """package com {
        |  package example {
        |    class Foo { def bar = 1 }
        |  }
        |}
        |""".stripMargin
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Foo#",
      "com/example/Foo#`<init>`().",
      "com/example/Foo#bar()."
    ), clues(definitions))
  }

  test("scala handles mix of def, val, var, type, given") {
    val definitions = ScalaSourceParser(
      """package p
        |class Mix {
        |  val a: Int = 1
        |  var b: String = ""
        |  type T = Int
        |  def m(x: Int): Int = x
        |}
        |""".stripMargin
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "p/Mix#",
      "p/Mix#`<init>`().",
      "p/Mix#a.",
      "p/Mix#b.",
      "p/Mix#T#",
      "p/Mix#m()."
    ), clues(definitions))
  }

  test("scala object nested in object") {
    val definitions = ScalaSourceParser(
      """package com.example
        |object Outer {
        |  object Inner {
        |    def run = 1
        |  }
        |}
        |""".stripMargin
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Outer.",
      "com/example/Outer.Inner.",
      "com/example/Outer.Inner.run()."
    ), clues(definitions))
  }

  test("scala stats are in definition order for overload tracking") {
    val definitions = ScalaSourceParser(
      """package com.example
        |class Foo {
        |  def bar(s: String): Unit = ()
        |  val x = 1
        |  def bar(i: Int): Unit = ()
        |}
        |""".stripMargin
    ).parse().definitions
    val barDefs = definitions.filter(_.name == "bar").sortBy(_.location.range.startLine)
    assertEquals(barDefs.size, 2, clues(barDefs))
    // First bar gets index 0, second gets index 1 (source order)
    assertEquals(barDefs(0).symbol.value, "com/example/Foo#bar().", clues(barDefs))
    assertEquals(barDefs(1).symbol.value, "com/example/Foo#bar(+1).", clues(barDefs))
  }

  test("scala concurrent parse yields consistent results") {
    val scalaSource = """package com.example
    |
    |import scala.collection.immutable.List
    |
    |class Foo(val count: Int, val name: String) {
    |  def this() = this(0, "")
    |
    |  def getCount: Int = count
    |  def setCount(c: Int): Unit = ()
    |  def getName: String = name
    |  def getItems: List[String] = Nil
    |
    |  class Bar(val flag: Boolean) {
    |    def isFlag: Boolean = flag
    |  }
    |
    |  enum Color { case Red, Green, Blue }
    |}
    """.stripMargin
    val parallelism = 50
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    try {
      val futures = (1 to parallelism).map { _ =>
        executor.submit(() => ScalaSourceParser(scalaSource).parse().definitions)
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

  test("scala extension methods are extracted") {
    val definitions = ScalaSourceParser(
      """package com.example
        |class Wrapper(val x: Int)
        |extension (w: Wrapper)
        |  def inc: Int = w.x + 1
        |  def show: String = w.toString
        |""".stripMargin,
      fileName = "Wrapper.scala"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Wrapper#",
      "com/example/Wrapper#`<init>`().",
      "com/example/Wrapper$package.inc().",
      "com/example/Wrapper$package.show()."
    ), clues(definitions))
  }
}
