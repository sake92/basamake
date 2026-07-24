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

  test("references: same-file term ref from method body") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
        |def f = A.g
        |""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A, got: $refSymbols")
  }

  test("references: toplevel val from method body") {
    val result = ScalaSourceParser(
      """def msg = "hi"
        |def f = msg
        |""".stripMargin,
      fileName = "Test.scala"
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/Test$package.msg()."),
      s"Expected ref to msg(), got: $refSymbols")
  }

  test("references: local val definition and usage in method body") {
    val result = ScalaSourceParser(
      """def f = {
        |  val x = 1
        |  x
        |}
        |""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.exists(_.startsWith("local")),
      s"Expected local var ref, got: $refSymbols")
    // Local definitions should NOT appear in definitions vector
    val defNames = result.definitions.map(_.name).toSet
    assert(!defNames.contains("x"), s"Local val 'x' should NOT be in definitions, got: $defNames")
  }

  test("references: param usage inside method body") {
    val result = ScalaSourceParser(
      """def f(x: Int) = x
        |""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.exists(_.startsWith("local")),
      s"Expected local param ref, got: $refSymbols")
  }

  test("references: method body traverses nested blocks") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
        |def f = {
        |  val x = {
        |    A.g
        |  }
        |  x
        |}
        |""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A from nested block, got: $refSymbols")
  }

  test("references: val rhs with term ref") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
        |val x = A.g
        |""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A from val rhs, got: $refSymbols")
  }

  test("references: var rhs with term ref") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
        |var x = A.g
        |""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A from var rhs, got: $refSymbols")
  }

  // ══════════════════════════════════════════════════════
  // DEBUG: AST dump for real files
  // ══════════════════════════════════════════════════════

  test("DEBUG: dump scalameta AST of sbt/Main.scala".ignore) {
    import scala.meta.Dialect
    import scala.meta.*
    import scala.meta.parsers.XtensionParseInputLike
    given Dialect = scala.meta.dialects.Scala3Future
    val src = """import upickle.default._
@main def hello(): Unit =
  val c = Array(1, 2, 3)
  println("Hello world!")
  println(msg)
  write(Seq(1, 2, 3))
  
def msg = 
  utils.getMsg()
"""
    src.parse[scala.meta.Source] match
      case scala.meta.Parsed.Success(tree) =>
        println(s"\n=== RAW AST STRUCTURE ===\n${tree.structure}")
        println(s"\n=== RAW AST SYNTAX ===\n${tree.syntax}")
      case scala.meta.Parsed.Error(pos, msg, _) =>
        println(s"PARSE ERROR: $msg at $pos")
        fail(s"Parse failed: $msg at $pos")

    // Our parser result
    val result = ScalaSourceParser(src, fileName = "Main.scala").parse()
    println(s"\n=== DEFINITIONS (${result.definitions.size}) ===")
    result.definitions.foreach { d =>
      println(s"  ${d.name} [${d.kind}] ${d.symbol} @ ${d.location.range}")
    }
    println(s"\n=== REFERENCES (${result.references.size}) ===")
    result.references.foreach { r =>
      println(s"  ${r.symbol} @ ${r.location.range}")
    }
  }

  // ══════════════════════════════════════════════════════
  // Test pyramid: isolate @main / indentation behavior
  // ══════════════════════════════════════════════════════

  test("references: indentation-based method body (no braces)") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
def f =
  A.g
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A from indented body, got: $refSymbols")
  }

  test("references: @main method body with braces") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
@main def hello(): Unit = { A.g }
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A from @main braced body, got: $refSymbols")
  }

  test("references: @main method body with indentation") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
@main def hello(): Unit =
  A.g
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A from @main indented body, got: $refSymbols")
  }

  // ══════════════════════════════════════════════════════
  // Branch coverage: all extractTermRefs cases
  // ══════════════════════════════════════════════════════

  test("references: forward reference from earlier def to later def") {
    val result = ScalaSourceParser(
      """def first = second
def second = 1
""".stripMargin,
      fileName = "Test.scala"
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/Test$package.second()."),
      s"Expected forward ref to second, got: $refSymbols")
  }

  test("references: ApplyInfix with same-file refs") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
def f =
  val x = A.g
  x + 1
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A, got: $refSymbols")
  }

  test("references: ApplyUnary with term ref") {
    val result = ScalaSourceParser(
      """def f =
  val flag = true
  !flag
""".stripMargin
    ).parse()
    // flag usage inside !flag should produce a local ref
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.exists(_.startsWith("local")),
      s"Expected local ref for flag, got: $refSymbols")
  }

  test("references: If condition and branches") {
    val result = ScalaSourceParser(
      """object A { def cond = true; def thenP = 1; def elseP = 1 }
def f = if A.cond then A.thenP else A.elseP
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in if/else, got: $refSymbols")
  }

  test("references: While body with term ref") {
    val result = ScalaSourceParser(
      """object A { def cond = true; def body = () }
def f = while A.cond do A.body
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in while, got: $refSymbols")
  }

  test("references: For comprehension with term ref") {
    val result = ScalaSourceParser(
      """object A { def xs = List(1); def g = () }
def f = for x <- A.xs do A.g
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in for, got: $refSymbols")
  }

  test("references: ForYield with term ref") {
    val result = ScalaSourceParser(
      """object A { def xs = List(1) }
def f = for x <- A.xs yield x
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in for-yield, got: $refSymbols")
  }

  test("references: Match with term refs in cases") {
    val result = ScalaSourceParser(
      """object A { def g = () }
def f =
  val x = 1
  x match
    case 1 => A.g
    case _ => ()
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in match case, got: $refSymbols")
  }

  test("references: New with constructor init") {
    val result = ScalaSourceParser(
      """class Foo
def f = new Foo
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/Foo#"),
      s"Expected ref to Foo from new, got: $refSymbols")
  }

  test("references: Function literal with param usage") {
    val result = ScalaSourceParser(
      """def f = (x: Int) => x
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.exists(_.startsWith("local")),
      s"Expected local param ref in lambda, got: $refSymbols")
  }

  test("references: Return with term ref") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
def f = return A.g
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in return, got: $refSymbols")
  }

  test("references: Throw with term ref") {
    val result = ScalaSourceParser(
      """object A { def ex = new Exception }
def f = throw A.ex
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in throw, got: $refSymbols")
  }

  test("references: Try/catch with term refs") {
    val result = ScalaSourceParser(
      """object A { def g = 1; def handler = () }
def f = try A.g catch case _ => A.handler
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in try/catch, got: $refSymbols")
  }

  test("references: Tuple with term refs") {
    val result = ScalaSourceParser(
      """object A { def a = 1; def b = 2 }
def f = (A.a, A.b)
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in tuple, got: $refSymbols")
  }

  test("references: Interpolated string with term ref") {
    val result = ScalaSourceParser(
      """def f =
  val name = "world"
  s"hello $name"
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.exists(_.startsWith("local")),
      s"Expected local ref in interpolated string, got: $refSymbols")
  }

  test("references: Assign with term refs on both sides") {
    val result = ScalaSourceParser(
      """object A { var x = 1 }
def f =
  var y = 0
  y = A.x
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in assign rhs, got: $refSymbols")
  }

  test("references: Ascribe with term ref") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
def f = (A.g: Int)
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in ascribe, got: $refSymbols")
  }

  test("references: Eta expansion of same-file method") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
def f = A.g _
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in eta expansion, got: $refSymbols")
  }

  test("references: Repeated vararg ref".ignore) {
    val result = ScalaSourceParser(
      """object A { def xs = List(1) }
def f(args: Int*) =
  A.xs ++ args
  args*
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.exists(_.startsWith("local")),
      s"Expected local ref for args*, got: $refSymbols")
  }

  test("references: Nested method in body calls outer scope") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
def f =
  def inner = A.g
  inner
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A from nested method, got: $refSymbols")
  }

  test("references: For-comprehension enumerator with Pat.Var binding") {
    val result = ScalaSourceParser(
      """object A { def xs = List((1,2)) }
def f = for (a, b) <- A.xs yield a
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in for enumerator, got: $refSymbols")
  }

  test("references: Match case with pattern binding") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
def f(x: Int) = x match
  case a => A.g + a
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A in match with pattern binding, got: $refSymbols")
  }

  // ══════════════════════════════════════════════════════
  // Namespace split: companion class + object coexist
  // ══════════════════════════════════════════════════════

  test("references: companion class and object both resolve") {
    val result = ScalaSourceParser(
      """class Foo
object Foo { def apply() = 1 }
def f = Foo(1)
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/Foo#"),
      s"Expected ref to class Foo#, got: $refSymbols")
    assert(refSymbols.contains("_empty_/Foo."),
      s"Expected ref to companion object Foo., got: $refSymbols")
  }

  test("references: class after object still coexists") {
    val result = ScalaSourceParser(
      """object Foo { def apply() = 1 }
class Foo
def f = Foo(1)
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/Foo#"),
      s"Expected ref to class Foo# after object, got: $refSymbols")
    assert(refSymbols.contains("_empty_/Foo."),
      s"Expected ref to companion object Foo., got: $refSymbols")
  }

  test("references: Term.Select emits both val and def candidates for member") {
    val result = ScalaSourceParser(
      """object A { def g = 1 }
def f = A.g
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A.g()."),
      s"Expected method candidate A.g(), got: $refSymbols")
    assert(refSymbols.contains("_empty_/A.g."),
      s"Expected val candidate A.g., got: $refSymbols")
  }

  test("references: Term.Select on complex qualifier skips member candidates") {
    val result = ScalaSourceParser(
      """object A { object B { def g = 1 } }
def f = A.B.g
""".stripMargin
    ).parse()
    // A.B is a qualified select, member candidates only emitted for simple qualifiers
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/A."),
      s"Expected ref to A from A.B.g, got: $refSymbols")
  }

  test("references: forward reference with companion preserves both") {
    val result = ScalaSourceParser(
      """def f = Foo(1)
class Foo
object Foo
""".stripMargin
    ).parse()
    val refSymbols = result.references.map(_.symbol.value).toSet
    assert(refSymbols.contains("_empty_/Foo#"),
      s"Expected ref to class Foo#, got: $refSymbols")
    assert(refSymbols.contains("_empty_/Foo."),
      s"Expected ref to companion object Foo., got: $refSymbols")
  }

}