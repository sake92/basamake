package ba.sake.basamake.navigation

import munit.FunSuite

class ScalaDefinitionsExtractorTest extends FunSuite {

  private def extract(fileName: String, code: String): Set[SymbolDefinition] = {
    val table = new SymbolTable
    val extractor = new ScalaDefinitionsExtractor(table)
    extractor.extractFromContent(fileName, code)
    // filter to just symbol + isType for assertion clarity
    table.all
  }

  private def sym(symbol: String, isType: Boolean = false): (String, Boolean) =
    (symbol, isType)

  private def assertSymbols(
    fileName: String,
    code: String,
    expected: Set[(String, Boolean)]
  )(implicit loc: munit.Location): Unit = {
    val actual = extract(fileName, code).map(d => (d.symbol, d.isType))
    assertEquals(actual, expected, clues(actual))
  }

  // ── C.1 Empty package, single class ────────────────────────────
  test("empty package, single class") {
    assertSymbols("", "class C", Set(
      sym("_empty_/C#", isType = true),
      sym("_empty_/C#`<init>`()."),
    ))
  }

  // ── C.2 One package, single class ──────────────────────────────
  test("one package, single class") {
    assertSymbols("", "package a\nclass C", Set(
      sym("a/C#", isType = true),
      sym("a/C#`<init>`()."),
    ))
  }

  // ── C.3 Nested packages a.b.c ──────────────────────────────────
  test("nested packages a.b.c") {
    assertSymbols("", "package a.b.c\nclass C", Set(
      sym("a/b/c/C#", isType = true),
      sym("a/b/c/C#`<init>`()."),
    ))
  }

  // ── C.4 Empty package, multiple top-level classes ──────────────
  test("empty package, multiple top-level classes") {
    assertSymbols("", "class A; class B", Set(
      sym("_empty_/A#", isType = true),
      sym("_empty_/A#`<init>`()."),
      sym("_empty_/B#", isType = true),
      sym("_empty_/B#`<init>`()."),
    ))
  }

  // ── C.5 Trait + object + class with method ─────────────────────
  test("trait + object + class with method") {
    val code = """package com.example
trait T { def t: Int }
class C { def m(x: Int): Int = x }
object O { val v: Int = 1 }"""
    assertSymbols("", code, Set(
      sym("com/example/T#", isType = true),
      sym("com/example/T#t()."),
      sym("com/example/C#", isType = true),
      sym("com/example/C#`<init>`()."),
      sym("com/example/C#m()."),
      sym("com/example/O."),
      sym("com/example/O.v."),
    ))
  }

  // ── C.6 Nested object -> class -> method ───────────────────────
  test("nested object -> class -> method") {
    val code = """package pkg
object Outer {
  class Inner {
    def m(): Int = 0
  }
}"""
    assertSymbols("", code, Set(
      sym("pkg/Outer."),
      sym("pkg/Outer.Inner#", isType = true),
      sym("pkg/Outer.Inner#`<init>`()."),
      sym("pkg/Outer.Inner#m()."),
    ))
  }

  // ── C.7 Class inside a method body ─────────────────────────────
  test("class inside a method body") {
    val code = """package pkg
def top(): Unit = {
  class InMethod
  object AlsoInMethod
}"""
    assertSymbols("", code, Set(
      sym("pkg/top()."),
      sym("pkg/top().InMethod#", isType = true),
      sym("pkg/top().InMethod#`<init>`()."),
      sym("pkg/top().AlsoInMethod."),
    ))
  }

  // ── C.8 Method overloads ───────────────────────────────────────
  test("method overloads") {
    val code = """package p
class O {
  def f(): Int = 0
  def f(x: Int): Int = x
  def f(x: Int, y: Int): Int = x + y
  def g(): Int = 0
}"""
    assertSymbols("", code, Set(
      sym("p/O#", isType = true),
      sym("p/O#`<init>`()."),
      sym("p/O#f()."),
      sym("p/O#f(+1)."),
      sym("p/O#f(+2)."),
      sym("p/O#g()."),
    ))
  }

  // ── C.9 Secondary constructors ─────────────────────────────────
  test("secondary constructors") {
    val code = """package p
class C(x: Int) {
  def this() = this(0)
  def this(s: String) = this(s.length)
}"""
    assertSymbols("", code, Set(
      sym("p/C#", isType = true),
      sym("p/C#`<init>`()."),
      sym("p/C#`<init>`(+1)."),
      sym("p/C#`<init>`(+2)."),
    ))
  }

  // ── C.10 Package object ────────────────────────────────────────
  test("package object") {
    val code = """package scala.collection
package object mutable {
  val answer: Int = 42
  def hello(): String = "x"
}"""
    assertSymbols("", code, Set(
      sym("scala/collection/mutable/package."),
      sym("scala/collection/mutable/package.answer."),
      sym("scala/collection/mutable/package.hello()."),
    ))
  }

  // ── C.11 Type aliases + opaque type ────────────────────────────
  test("type aliases + opaque type") {
    val code = """package com.example
type IntList = List[Int]
opaque type ID = String"""
    assertSymbols("", code, Set(
      sym("com/example/IntList#", isType = true),
      sym("com/example/ID#", isType = true),
    ))
  }

  // ── C.12 Enum (single + RepeatedEnumCase) ──────────────────────
  test("enum single + RepeatedEnumCase") {
    val code = """package com.example
enum Color { case Red, Blue }
enum Color2 { case Green; case Yellow }"""
    assertSymbols("", code, Set(
      sym("com/example/Color#", isType = true),
      sym("com/example/Color."),
      sym("com/example/Color#`<init>`()."),
      sym("com/example/Color.Red."),
      sym("com/example/Color.Blue."),
      sym("com/example/Color2#", isType = true),
      sym("com/example/Color2."),
      sym("com/example/Color2#`<init>`()."),
      sym("com/example/Color2.Green."),
      sym("com/example/Color2.Yellow."),
    ))
  }

  // ── C.13 Named givens with body method ─────────────────────────
  test("named givens with body method") {
    val code = """package com.example
trait Show[T] { def show(t: T): String }
given stringShow: Show[String] with {
  def show(t: String): String = t
}
given intShow: Show[Int] = new Show[Int] { def show(t: Int): String = t.toString }"""
    assertSymbols("", code, Set(
      sym("com/example/Show#", isType = true),
      sym("com/example/Show#show()."),
      sym("com/example/stringShow."),
      sym("com/example/stringShow.show()."),
      sym("com/example/intShow."),
    ))
  }

  // ── C.14 Extension method ──────────────────────────────────────
  test("extension method") {
    val code = """package com.example
extension (s: String) {
  def makeLoud(): String = s + "!"
  def doubled(): String = s + s
}"""
    assertSymbols("", code, Set(
      sym("com/example/makeLoud()."),
      sym("com/example/doubled()."),
    ))
  }

  // ── C.15 Case class synthetics ─────────────────────────────────
  test("case class synthetics") {
    val code = """package com.example
case class Person(name: String)
case class Empty()"""
    assertSymbols("", code, Set(
      sym("com/example/Person#", isType = true),
      sym("com/example/Person#`<init>`()."),
      sym("com/example/Person."),
      sym("com/example/Person.apply()."),
      sym("com/example/Person#copy()."),
      sym("com/example/Empty#", isType = true),
      sym("com/example/Empty#`<init>`()."),
      sym("com/example/Empty."),
      sym("com/example/Empty.apply()."),
      sym("com/example/Empty#copy()."),
    ))
  }

  // ── C.16 Case class with user-defined apply/copy ───────────────
  test("case class with user-defined apply/copy") {
    val code = """package com.example
case class Person(name: String) {
  def apply(): Int = 0
  def copy(x: String): Person = this
}"""
    assertSymbols("", code, Set(
      sym("com/example/Person#", isType = true),
      sym("com/example/Person#`<init>`()."),
      sym("com/example/Person."),
      sym("com/example/Person.apply()."),
      sym("com/example/Person.apply(+1)."),
      sym("com/example/Person#copy()."),
      sym("com/example/Person#copy(+1)."),
    ))
  }

  // ── C.17 Top-level defs in Foo.scala -> X$package. wrapper ────
  test("top-level defs in Foo.scala -> X$$package. wrapper") {
    val code = """package com.example
def topLevelMethod(): Int = 42
val topLevelVal: Int = 1
class TopClass
object TopObject"""
    assertSymbols("Foo.scala", code, Set(
      sym("com/example/Foo$package.topLevelMethod()."),
      sym("com/example/Foo$package.topLevelVal."),
      sym("com/example/TopClass#", isType = true),
      sym("com/example/TopClass#`<init>`()."),
      sym("com/example/TopObject."),
    ))
  }

  // ── C.18 Top-level defs in package.scala -> package. wrapper ──
  test("top-level defs in package.scala -> package. wrapper") {
    val code = """package com.example
def helper(): Int = 0
val default: Int = 1
class Inside"""
    assertSymbols("package.scala", code, Set(
      sym("com/example/package.helper()."),
      sym("com/example/package.default."),
      sym("com/example/Inside#", isType = true),
      sym("com/example/Inside#`<init>`()."),
    ))
  }

  // ── C.19 Empty filename -> no wrapping ─────────────────────────
  test("empty filename -> no wrapping") {
    val code = """package com.example
def helper(): Int = 0"""
    assertSymbols("", code, Set(
      sym("com/example/helper()."),
    ))
  }

  // ── C.20 Operator-named methods (backtick escape) ──────────────
  test("operator-named methods") {
    val code = """package com.example
class C {
  def + (x: Int): Int = 0
  def `unary_!`: Int = 0
  def ==(that: Any): Boolean = true
}"""
    assertSymbols("", code, Set(
      sym("com/example/C#", isType = true),
      sym("com/example/C#`<init>`()."),
      sym("com/example/C#`+`()."),
      sym("com/example/C#`unary_!`()."),
      sym("com/example/C#`==`()."),
    ))
  }

  // ── C.21 Full integration test ─────────────────────────────────
  test("full integration test from scala_defs_parser.md") {
    val code = """package com.example
opaque type ID = String
enum Color { case Red, Blue }
trait Show[T] { def show(t: T): String }
given stringShow: Show[String] with { def show(t: String): String = t }
extension (s: String) { def makeLoud(): String = s + "!" }
case class Person(name: String)
def topLevelMethod(): Int = 42"""
    assertSymbols("Features.scala", code, Set(
      sym("com/example/ID#", isType = true),
      sym("com/example/Color#", isType = true),
      sym("com/example/Color."),
      sym("com/example/Color#`<init>`()."),
      sym("com/example/Color.Red."),
      sym("com/example/Color.Blue."),
      sym("com/example/Show#", isType = true),
      sym("com/example/Show#show()."),
      sym("com/example/stringShow."),
      sym("com/example/stringShow.show()."),
      sym("com/example/makeLoud()."),
      sym("com/example/Person#", isType = true),
      sym("com/example/Person#`<init>`()."),
      sym("com/example/Person."),
      sym("com/example/Person.apply()."),
      sym("com/example/Person#copy()."),
      sym("com/example/Features$package.topLevelMethod()."),
    ))
  }

}
