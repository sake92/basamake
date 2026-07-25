package ba.sake.basamake.navigation

import java.util.concurrent.Executors
import munit.FunSuite
import org.eclipse.lsp4j.SymbolKind

class ScalaSourceParserSemanticdbTest extends FunSuite {

  // ══════════════════════════════════════════════════════
  // Definitions
  // ══════════════════════════════════════════════════════

  test("scala default-package class emits _empty_/ owner with primary ctor") {
    val definitions = ScalaSourceParser("class Foo { def run = 1 }").parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "_empty_/Foo#",
      "_empty_/Foo#`<init>`().",
      "_empty_/Foo#run()."
    ), clues(definitions))
  }

  test("scala package-scoped class with overloaded methods and constructors") {
    val definitions = ScalaSourceParser(
      """|package com.example
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
      "com/example/Outer#Inner#`<init>`().",
      "local0"   // param x of run(x: Int)
    ), clues(definitions))
  }

  test("scala trait emits type descriptor") {
    val definitions = ScalaSourceParser(
      "package com.example\ntrait Api { def apply(): Unit }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set("com/example/Api#", "com/example/Api#apply()."), clues(definitions))
  }

  test("scala object emits term descriptor") {
    val definitions = ScalaSourceParser(
      "package com.example\nobject Api { def apply(): Int = 1 }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set("com/example/Api.", "com/example/Api.apply()."), clues(definitions))
  }

  test("scala enum with cases") {
    val definitions = ScalaSourceParser(
      "package com.example\nenum Color { case Red, Green }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Color#", "com/example/Color#`<init>`().",
      "com/example/Color#Red.", "com/example/Color#Green."
    ), clues(definitions))
  }

  test("scala enum with repeated case") {
    val definitions = ScalaSourceParser(
      "package com.example\nenum Color { case Red, Green, Blue }"
    ).parse().definitions
    val enumMemberNames = definitions.filter(_.kind == SymbolKind.EnumMember).map(_.name).toSet
    assertEquals(enumMemberNames, Set("Red", "Green", "Blue"), clues(definitions))
  }

  test("scala nested class with parent package owner") {
    val definitions = ScalaSourceParser(
      """|package com.example
         |class Outer(x: Int) {
         |  class Inner(val y: String) {
         |    def run(): Unit = ()
         |  }
         |}
         |""".stripMargin
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Outer#", "com/example/Outer#`<init>`().",
      "com/example/Outer#Inner#", "com/example/Outer#Inner#`<init>`().",
      "com/example/Outer#Inner#run().", "com/example/Outer#Inner#y."
    ), clues(definitions))
  }

  test("scala operator methods are backtick-wrapped") {
    val definitions = ScalaSourceParser(
      "package com.example\nclass Ops { def ++(x: Int): Int = x; def ++(x: Int, y: Int): Int = x + y }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Ops#", "com/example/Ops#`<init>`().",
      "com/example/Ops#`++`().", "com/example/Ops#`++`(+1).",
      "local0", "local1", "local2"
    ), clues(definitions))
  }

  test("scala constructors: primary and two secondary") {
    val definitions = ScalaSourceParser(
      """|package com.example
         |class Foo(x: Int) {
         |  def this() = this(0)
         |  def this(y: String) = this(0)
         |}
         |""".stripMargin
    ).parse().definitions
    val ctorDefs = definitions.filter(_.kind == SymbolKind.Constructor).map(_.symbol.value).toSet
    assertEquals(ctorDefs, Set(
      "com/example/Foo#`<init>`().", "com/example/Foo#`<init>`(+1).", "com/example/Foo#`<init>`(+2)."
    ), clues(definitions))
  }

  test("scala does not produce markerless or bare-name aliases") {
    val definitions = ScalaSourceParser(
      "package com.example\nclass Foo { def bar = 1 }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assert(!symbols.contains("bar"), clues(definitions))
  }

  test("scala top-level defs wrapped under X$package, classes unchanged") {
    val definitions = ScalaSourceParser(
      "class Foo\ndef bar = 1", fileName = "Bar.scala"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "_empty_/Foo#", "_empty_/Foo#`<init>`().", "_empty_/Bar$package.bar()."
    ), clues(definitions))
  }

  test("scala package object members under package.") {
    val definitions = ScalaSourceParser(
      """|package object pkg {
         |  val x = 1
         |  class Foo
         |}
         |""".stripMargin, fileName = "package.scala"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "pkg/package.", "pkg/package.x.", "pkg/package.Foo#", "pkg/package.Foo#`<init>`()."
    ), clues(definitions))
  }

  test("scala named given emits term descriptor") {
    val definitions = ScalaSourceParser(
      "package com.example\ngiven intOrd: Ordering[Int] with\n  def compare(x: Int, y: Int): Int = x - y",
      fileName = "intOrd.scala"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/intOrd$package.intOrd.", "com/example/intOrd$package.intOrd.compare().",
      "local0", "local1"
    ), clues(definitions))
  }

  test("scala type alias emits type descriptor") {
    val definitions = ScalaSourceParser(
      "package com.example\nclass Wrapper { type T = Int }"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Wrapper#", "com/example/Wrapper#`<init>`().", "com/example/Wrapper#T#"
    ), clues(definitions))
  }

  test("scala no false positives for local values inside method bodies") {
    val result = ScalaSourceParser(
      """|package com.example
         |object Foo {
         |  def bar = { val local = 1; local }
         |}
         |""".stripMargin
    ).parse()
    val names = result.definitions.map(_.name).toSet
    assert(names.contains("local"), clues(names))
    assert(names.contains("Foo"), clues(names))
    assert(names.contains("bar"), clues(names))
    val localSymbols = result.definitions.filter(d => SymbolUtils.isLocalSymbol(d.symbol.value))
    assert(localSymbols.nonEmpty, s"Expected local defs, got: ${result.definitions}")
    val localRefs = result.references.filter(r => SymbolUtils.isLocalSymbol(r.symbol.value))
    assert(localRefs.nonEmpty, s"Expected local refs, got: ${result.references}")
  }

  test("scala deeply nested package declarations") {
    val definitions = ScalaSourceParser(
      """|package com {
         |  package example {
         |    class Foo { def bar = 1 }
         |  }
         |}
         |""".stripMargin
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Foo#", "com/example/Foo#`<init>`().", "com/example/Foo#bar()."
    ), clues(definitions))
  }

  test("scala handles mix of def, val, var, type, given") {
    val definitions = ScalaSourceParser(
      """|package p
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
      "p/Mix#", "p/Mix#`<init>`().", "p/Mix#a.", "p/Mix#b.", "p/Mix#T#", "p/Mix#m().",
      "local0"
    ), clues(definitions))
  }

  test("scala object nested in object") {
    val definitions = ScalaSourceParser(
      """|package com.example
         |object Outer {
         |  object Inner {
         |    val x = 1
         |  }
         |}
         |""".stripMargin
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Outer.", "com/example/Outer.Inner.", "com/example/Outer.Inner.x."
    ), clues(definitions))
  }

  test("scala extension methods are extracted") {
    val definitions = ScalaSourceParser(
      """|package com.example
         |class Wrapper(val x: Int)
         |extension (w: Wrapper)
         |  def inc: Int = w.x + 1
         |  def show: String = w.toString
         |""".stripMargin, fileName = "Wrapper.scala"
    ).parse().definitions
    val symbols = definitions.map(_.symbol.value).toSet
    assertEquals(symbols, Set(
      "com/example/Wrapper#", "com/example/Wrapper#`<init>`().", "com/example/Wrapper#x.",
      "com/example/Wrapper$package.inc().", "com/example/Wrapper$package.show()."
    ), clues(definitions))
  }

  test("scala stats are in definition order for overload tracking") {
    val definitions = ScalaSourceParser(
      """|package com.example
         |class Foo {
         |  def bar = 1
         |  def bar(x: Int) = 2
         |}
         |""".stripMargin
    ).parse().definitions
    val barDefs = definitions.filter(_.name == "bar").sortBy(_.location.range.startLine)
    assertEquals(barDefs.size, 2, clues(barDefs))
    assertEquals(barDefs(0).symbol.value, "com/example/Foo#bar().", clues(barDefs))
    assertEquals(barDefs(1).symbol.value, "com/example/Foo#bar(+1).", clues(barDefs))
  }

  test("scala concurrent parse yields consistent results") {
    val scalaSource =
      """|package com.example
         |import scala.collection.immutable.List
         |class Foo(val count: Int, val name: String) {
         |  def this() = this(0, "")
         |  def getCount: Int = count
         |  def setCount(c: Int): Unit = ()
         |  def getName: String = name
         |  def getItems: List[String] = Nil
         |  class Bar(val flag: Boolean) {
         |    def isFlag: Boolean = flag
         |  }
         |  enum Color { case Red, Green, Blue }
         |}
         |""".stripMargin
    val parallelism = 50
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    try {
      val futures = (1 to parallelism).map { _ =>
        executor.submit(() => ScalaSourceParser(scalaSource).parse().definitions)
      }
      val results = futures.map(_.get())
      val first = results.head
      assert(first.nonEmpty, "expected definitions, got empty")
      results.foreach { r =>
        assertEquals(r.size, first.size, "concurrent parses produced different def counts")
        assertEquals(r.map(_.name).sorted, first.map(_.name).sorted, "concurrent parses diverged")
      }
    } finally executor.shutdown()
  }

  // ══════════════════════════════════════════════════════
  // References — full SemanticDB symbols everywhere
  // ══════════════════════════════════════════════════════

  test("references: same-file refs use known global symbols") {
    val result = ScalaSourceParser(
      """|object A { def g = 1 }
         |def f = A.g
         |""".stripMargin
    ).parse()
    val refSyms = result.references.map(_.symbol.value).toSet
    // A resolves from fileDefs → _empty_/A.
    assert(refSyms.contains("_empty_/A."), s"Expected _empty_/A., got: $refSyms")
    // Member guess: _empty_/A.g. + _empty_/A.g()
    assert(refSyms.contains("_empty_/A.g."), s"Expected _empty_/A.g., got: $refSyms")
    assert(refSyms.contains("_empty_/A.g()."), s"Expected _empty_/A.g(), got: $refSyms")
  }

  test("references: same-file toplevel def from method body") {
    val result = ScalaSourceParser(
      """|def msg = "hi"
         |def f = msg
         |""".stripMargin
    ).parse()
    // msg resolves from fileDefs → exact symbol
    val refSyms = result.references.map(_.symbol.value).toSet
    assert(refSyms.exists(_.contains("msg().")), s"Expected msg() ref, got: $refSyms")
  }

  test("references: local val definition and usage in method body") {
    val result = ScalaSourceParser(
      """|def f = {
         |  val x = 1
         |  x
         |}
         |""".stripMargin
    ).parse()
    val localRefs = result.references.filter(r => SymbolUtils.isLocalSymbol(r.symbol.value))
    assert(localRefs.nonEmpty, s"Expected local var ref, got: ${result.references}")
    val localDefs = result.definitions.filter(d => SymbolUtils.isLocalSymbol(d.symbol.value)).map(_.name)
    assert(localDefs.contains("x"), "Expected local def for x")
  }

  test("references: param usage inside method body") {
    val result = ScalaSourceParser("def f(x: Int) = x").parse()
    val localRefs = result.references.filter(r => SymbolUtils.isLocalSymbol(r.symbol.value))
    assert(localRefs.nonEmpty, s"Expected local param ref, got: ${result.references}")
  }

  test("references: method body traverses nested blocks") {
    val result = ScalaSourceParser(
      """|object A { def g = 1 }
         |def f = {
         |  val x = { A.g }
         |  x
         |}
         |""".stripMargin
    ).parse()
    val refSyms = result.references.map(_.symbol.value).toSet
    assert(refSyms.contains("_empty_/A."), s"Expected _empty_/A., got: $refSyms")
    assert(result.references.exists(r => SymbolUtils.isLocalSymbol(r.symbol.value)),
      s"Expected local ref for x, got: ${result.references}")
  }

  test("references: val rhs with term ref") {
    val result = ScalaSourceParser(
      """|object A { def g = 1 }
         |val x = A.g
         |""".stripMargin
    ).parse()
    val refSyms = result.references.map(_.symbol.value).toSet
    assert(refSyms.contains("_empty_/A."), s"Expected _empty_/A., got: $refSyms")
  }

  test("references: type annotation records guessed global symbols") {
    val result = ScalaSourceParser(
      """|class Foo
         |val x: Foo = new Foo
         |""".stripMargin
    ).parse()
    val refSyms = result.references.map(_.symbol.value).toSet
    assert(refSyms.contains("_empty_/Foo#"), s"Expected _empty_/Foo#, got: $refSyms")
    assert(refSyms.contains("_empty_/Foo."), s"Expected _empty_/Foo., got: $refSyms")
  }

  test("references: import records guessed global symbols") {
    val result = ScalaSourceParser(
      """|import scala.collection.immutable.List
         |class Wrapper(list: List[Int])
         |""".stripMargin
    ).parse()
    val refSyms = result.references.map(_.symbol.value).toSet
    assert(refSyms.contains("_empty_/List."), s"Expected _empty_/List., got: $refSyms")
    assert(refSyms.contains("_empty_/List#"), s"Expected _empty_/List#, got: $refSyms")
  }

  test("references: new Foo records guessed global symbols") {
    val result = ScalaSourceParser("class Foo\ndef f = new Foo").parse()
    val refSyms = result.references.map(_.symbol.value).toSet
    assert(refSyms.contains("_empty_/Foo#"), s"Expected _empty_/Foo#, got: $refSyms")
  }

  test("references: cross-file refs use _empty_/ guess") {
    val result = ScalaSourceParser("def f = someObject.someMethod()").parse()
    val refSyms = result.references.map(_.symbol.value).toSet
    assert(refSyms.contains("_empty_/someObject."), s"Expected term guess, got: $refSyms")
    assert(refSyms.contains("_empty_/someObject#"), s"Expected type guess, got: $refSyms")
    assert(refSyms.contains("_empty_/someObject.someMethod."), s"Expected val member guess, got: $refSyms")
    assert(refSyms.contains("_empty_/someObject.someMethod()."), s"Expected def member guess, got: $refSyms")
  }

  test("references: function literal records guessed param type symbols") {
    val result = ScalaSourceParser(
      """|class Foo
         |def f = (x: Foo) => x
         |""".stripMargin
    ).parse()
    val refSyms = result.references.map(_.symbol.value).toSet
    assert(refSyms.contains("_empty_/Foo#"), s"Expected _empty_/Foo#, got: $refSyms")
  }

  test("references: if/while/for/match all record guessed symbols") {
    val result = ScalaSourceParser(
      """|object A { def cond = true; def thenP = 1; def g = () }
         |def f =
         |  if A.cond then A.thenP else 0
         |  while A.cond do A.g
         |  (1: A.thenP) match { case 1 => A.g }
         |""".stripMargin
    ).parse()
    val refSyms = result.references.map(_.symbol.value).toSet
    assert(refSyms.contains("_empty_/A."), s"Expected _empty_/A., got: $refSyms")
  }

  test("references: throw/return/try/tuple/interpolate all record names") {
    val result = ScalaSourceParser(
      """|object A { def ex = new Exception; def g = 1 }
         |def f =
         |  return A.g
         |  throw A.ex
         |  try A.g catch case _ => A.g
         |  (A.g, A.g)
         |  val x = 1; s"$x"
         |""".stripMargin
    ).parse()
    val refSyms = result.references.map(_.symbol.value).toSet
    assert(refSyms.contains("_empty_/A."), s"Expected _empty_/A., got: $refSyms")
  }
}
