package ba.sake.basamake.navigation.scalasrc

import munit.FunSuite
import ba.sake.basamake.navigation.{SymbolTable, InMemorySymbolTable, SymbolDefinition}

class ScalaDefinitionsExtractorTest extends FunSuite {

  private def extract(fileName: String, code: String): Set[SymbolDefinition] = {
    val table = new InMemorySymbolTable
    val extractor = new ScalaDefinitionsExtractor(table)
    extractor.extractFromContent(fileName, code, os.pwd / fileName)
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
  test("C.1 empty package, single class") {
    assertSymbols("c01_empty_class.scala", "class C", Set(
      sym("_empty_/C#", isType = true),
      sym("_empty_/C#`<init>`()."),
    ))
  }

  // ── C.2 One package, single class ──────────────────────────────
  test("C.2 one package, single class") {
    assertSymbols("c02_one_pkg.scala", "package a\nclass C", Set(
      sym("a/C#", isType = true),
      sym("a/C#`<init>`()."),
    ))
  }

  // ── C.3 Nested packages a.b.c ──────────────────────────────────
  test("C.3 nested packages a.b.c") {
    assertSymbols("c03_nested_pkg.scala", "package a.b.c\nclass C", Set(
      sym("a/b/c/C#", isType = true),
      sym("a/b/c/C#`<init>`()."),
    ))
  }

  // ── C.3b Nested package STATEMENTS (scala-library style) ───────
  test("C.3b nested package statements accumulate the outer prefix") {
    assertSymbols("c03b_nested_pkg_statements.scala", "package a\npackage b\nclass C", Set(
      sym("a/b/C#", isType = true),
      sym("a/b/C#`<init>`()."),
    ))
  }

  test("C.3c multi-segment first statement + nested second statement") {
    assertSymbols("c03c_nested_pkg_mixed.scala", "package a.b\npackage c\nclass D", Set(
      sym("a/b/c/D#", isType = true),
      sym("a/b/c/D#`<init>`()."),
    ))
  }

  // ── C.4 Empty package, multiple top-level classes ──────────────
  test("C.4 empty package, multiple top-level classes") {
    assertSymbols("c04_multi_class.scala", "class A; class B", Set(
      sym("_empty_/A#", isType = true),
      sym("_empty_/A#`<init>`()."),
      sym("_empty_/B#", isType = true),
      sym("_empty_/B#`<init>`()."),
    ))
  }

  // ── C.5 Trait + object + class with method ─────────────────────
  test("C.5 trait + object + class with method") {
    val code = """package com.example
trait T { def t: Int }
class C { def m(x: Int): Int = x }
object O { val v: Int = 1 }"""
    assertSymbols("c05_trait_obj_class.scala", code, Set(
      sym("com/example/T#", isType = true),
      sym("com/example/T#`<init>`()."),
      sym("com/example/T#t()."),
      sym("com/example/C#", isType = true),
      sym("com/example/C#`<init>`()."),
      sym("com/example/C#m()."),
      sym("com/example/C#m().(x)"), // param x of method m, so you can goto it
      sym("com/example/O."),
      sym("com/example/O.v."),
    ))
  }

  // ── C.6 Nested object -> class -> method ───────────────────────
  test("C.6 nested object -> class -> method") {
    val code = """package pkg
object Outer {
  class Inner {
    def m(): Int = 0
  }
}"""
    assertSymbols("c06_nested_obj.scala", code, Set(
      sym("pkg/Outer."),
      sym("pkg/Outer.Inner#", isType = true),
      sym("pkg/Outer.Inner#`<init>`()."),
      sym("pkg/Outer.Inner#m()."),
    ))
  }

  // ── C.7 Class inside a method body (locals skipped) ────────────
  test("C.7 class inside a method body") {
    val code = """package pkg
def top(): Unit = {
  class InMethod
  object AlsoInMethod
}"""
    assertSymbols("c07_method_body.scala", code, Set(
      sym("pkg/c07_method_body$package.top()."),
      sym("pkg/c07_method_body$package."),
    ))
  }

  // ── C.8 Method overloads ───────────────────────────────────────
  test("C.8 method overloads") {
    val code = """package p
class O {
  def f(): Int = 0
  def f(x: Int): Int = x
  def f(x: Int, y: Int): Int = x + y
  def g(): Int = 0
}"""
    assertSymbols("c08_overloads.scala", code, Set(
      sym("p/O#", isType = true),
      sym("p/O#`<init>`()."),
      sym("p/O#f()."),
      sym("p/O#f(+1)."),
      sym("p/O#f(+1).(x)"),
      sym("p/O#f(+2)."),
      sym("p/O#f(+2).(x)"),
      sym("p/O#f(+2).(y)"),
      sym("p/O#g()."),
    ))
  }

  // ── C.9 Secondary constructors ─────────────────────────────────
  test("C.9 secondary constructors") {
    val code = """package p
class C(x: Int) {
  def this() = this(0)
  def this(s: String) = this(s.length)
}"""
    assertSymbols("c09_secondary_ctors.scala", code, Set(
      sym("p/C#", isType = true),
      sym("p/C#x."),
      sym("p/C#`<init>`()."),
      sym("p/C#`<init>`().(x)"),
      sym("p/C#`<init>`(+1)."),
      sym("p/C#`<init>`(+2)."),
      sym("p/C#`<init>`(+2).(s)"),
    ))
  }

  // ── C.10 Package object ────────────────────────────────────────
  test("C.10 package object") {
    val code = """package scala.collection
package object mutable {
  val answer: Int = 42
  def hello(): String = "x"
}"""
    assertSymbols("c10_pkgobj.scala", code, Set(
      sym("scala/collection/mutable/package."),
      sym("scala/collection/mutable/package.answer."),
      sym("scala/collection/mutable/package.hello()."),
    ))
  }

  // ── C.11 Type aliases + opaque type ────────────────────────────
  test("C.11 type aliases + opaque type") {
    val code = """package com.example
type IntList = List[Int]
opaque type ID = String"""
    assertSymbols("c11_type_aliases.scala", code, Set(
      sym("com/example/c11_type_aliases$package.IntList#", isType = true),
      sym("com/example/c11_type_aliases$package.ID#", isType = true),
      sym("com/example/c11_type_aliases$package."),
    ))
  }

  // ── C.12 Enum (single + RepeatedEnumCase) ──────────────────────
  test("C.12 enum single + RepeatedEnumCase") {
    val code = """package com.example
enum Color { case Red, Blue }
enum Color2 { case Green; case Yellow }"""
    assertSymbols("c12_enums.scala", code, Set(
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
  test("C.13 named givens with body method") {
    val code = """package com.example
trait Show[T] { def show(t: T): String }
given stringShow: Show[String] with {
  def show(t: String): String = t
}
given intShow: Show[Int] = new Show[Int] { def show(t: Int): String = t.toString }"""
    assertSymbols("c13_givens.scala", code, Set(
      sym("com/example/Show#", isType = true),
      sym("com/example/Show#[T]"),
      sym("com/example/Show#`<init>`()."),
      sym("com/example/Show#show()."),
      sym("com/example/Show#show().(t)"),
      sym("com/example/c13_givens$package.stringShow."),
      sym("com/example/c13_givens$package.stringShow.show()."),
      sym("com/example/c13_givens$package.stringShow.show().(t)"),
      sym("com/example/c13_givens$package.intShow."),
      sym("com/example/c13_givens$package."),
    ))
  }

  // ── C.14 Extension method ──────────────────────────────────────
  test("C.14 extension method") {
    val code = """package com.example
extension (s: String) {
  def makeLoud(): String = s + "!"
  def doubled(): String = s + s
}"""
    assertSymbols("c14_extension.scala", code, Set(
      sym("com/example/c14_extension$package.makeLoud()."),
      sym("com/example/c14_extension$package.makeLoud().(s)"),
      sym("com/example/c14_extension$package.doubled()."),
      sym("com/example/c14_extension$package.doubled().(s)"),
      sym("com/example/c14_extension$package."),
    ))
  }

  // ── C.15 Case class synthetics ─────────────────────────────────
  test("C.15 case class synthetics") {
    val code = """package com.example
case class Person(name: String)
case class Empty()"""
    assertSymbols("c15_case_class.scala", code, Set(
      // Person
      sym("com/example/Person#", isType = true),
      sym("com/example/Person#`<init>`()."),
      sym("com/example/Person#`<init>`().(name)"),
      sym("com/example/Person#name."),
      sym("com/example/Person#copy()."),
      sym("com/example/Person#copy().(name)"),
      sym("com/example/Person."),
      sym("com/example/Person.apply()."),
      sym("com/example/Person.apply().(name)"),
      sym("com/example/Person.unapply()."),
      sym("com/example/Person.unapply().(x$1)"),
      sym("com/example/Person.toString()."),
      // Empty (no params)
      sym("com/example/Empty#", isType = true),
      sym("com/example/Empty#`<init>`()."),
      sym("com/example/Empty#copy()."),
      sym("com/example/Empty."),
      sym("com/example/Empty.apply()."),
      sym("com/example/Empty.unapply()."),
      sym("com/example/Empty.unapply().(x$1)"),
      sym("com/example/Empty.toString()."),
    ))
  }

  // ── C.16 Case class with user-defined apply/copy ───────────────
  test("C.16 case class with user-defined apply/copy") {
    val code = """package com.example
case class Person(name: String) {
  def apply(): Int = 0
  def copy(x: String): Person = this
}"""
    assertSymbols("c16_case_class_user.scala", code, Set(
      sym("com/example/Person#", isType = true),
      sym("com/example/Person#`<init>`()."),
      sym("com/example/Person#`<init>`().(name)"),
      sym("com/example/Person#name."),
      sym("com/example/Person#apply()."),
      sym("com/example/Person#copy()."),
      sym("com/example/Person#copy().(x)"),
      sym("com/example/Person."),
      sym("com/example/Person.apply()."),
      sym("com/example/Person.apply().(name)"),
      sym("com/example/Person.unapply()."),
      sym("com/example/Person.unapply().(x$1)"),
      sym("com/example/Person.toString()."),
    ))
  }

  // ── C.17 Top-level defs in Foo.scala -> X$package. wrapper ────
  test("C.17 top-level defs in Foo.scala -> X$$package. wrapper") {
    val code = """package com.example
def topLevelMethod(): Int = 42
val topLevelVal: Int = 1
class TopClass
object TopObject"""
    assertSymbols("Foo.scala", code, Set(
      sym("com/example/TopClass#", isType = true),
      sym("com/example/TopClass#`<init>`()."),
      sym("com/example/TopObject."),
      sym("com/example/Foo$package.topLevelMethod()."),
      sym("com/example/Foo$package.topLevelVal."),
      sym("com/example/Foo$package."),
    ))
  }

  // ── C.18 Top-level defs in package.scala -> package$package. ──
  test("C.18 top-level defs in package.scala -> package$$package. wrapper") {
    val code = """package com.example
def helper(): Int = 0
val default: Int = 1
class Inside"""
    assertSymbols("package.scala", code, Set(
      sym("com/example/Inside#", isType = true),
      sym("com/example/Inside#`<init>`()."),
      sym("com/example/package$package.helper()."),
      sym("com/example/package$package.default."),
      sym("com/example/package$package."),
    ))
  }

  // ── C.19 Top-level def with filename (renamed from empty-filename test) ──
  test("C.19 top-level def with filename") {
    val code = """package com.example
def helper(): Int = 0"""
    assertSymbols("c19_no_wrap.scala", code, Set(
      sym("com/example/c19_no_wrap$package.helper()."),
      sym("com/example/c19_no_wrap$package."),
    ))
  }

  // ── C.20 Operator-named methods (backtick escape) ──────────────
  test("C.20 operator-named methods") {
    val code = """package com.example
class C {
  def `+`(x: Int): Int = 0
  def `unary_!`: Int = 0
  def `==`(that: Any): Boolean = true
}"""
    assertSymbols("c20_operators.scala", code, Set(
      sym("com/example/C#", isType = true),
      sym("com/example/C#`<init>`()."),
      sym("com/example/C#`+`()."),
      sym("com/example/C#`+`().(x)"),
      sym("com/example/C#`unary_!`()."),
      sym("com/example/C#`==`()."),
      sym("com/example/C#`==`().(that)"),
    ))
  }

  // ── C.21 Full integration test ─────────────────────────────────
  test("C.21 full integration test") {
    val code = """package com.example
opaque type ID = String
enum Color { case Red, Blue }
trait Show[T] { def show(t: T): String }
given stringShow: Show[String] with { def show(t: String): String = t }
extension (s: String) { def makeLoud(): String = s + "!" }
case class Person(name: String)
def topLevelMethod(): Int = 42"""
    assertSymbols("Features.scala", code, Set(
      // ID
      sym("com/example/Features$package.ID#", isType = true),
      // Color
      sym("com/example/Color#", isType = true),
      sym("com/example/Color."),
      sym("com/example/Color#`<init>`()."),
      sym("com/example/Color.Red."),
      sym("com/example/Color.Blue."),
      // Show
      sym("com/example/Show#", isType = true),
      sym("com/example/Show#[T]"),
      sym("com/example/Show#`<init>`()."),
      sym("com/example/Show#show()."),
      sym("com/example/Show#show().(t)"),
      // stringShow
      sym("com/example/Features$package.stringShow."),
      sym("com/example/Features$package.stringShow.show()."),
      sym("com/example/Features$package.stringShow.show().(t)"),
      // extension
      sym("com/example/Features$package.makeLoud()."),
      sym("com/example/Features$package.makeLoud().(s)"),
      // Person (case class)
      sym("com/example/Person#", isType = true),
      sym("com/example/Person#`<init>`()."),
      sym("com/example/Person#`<init>`().(name)"),
      sym("com/example/Person#name."),
      sym("com/example/Person#copy()."),
      sym("com/example/Person#copy().(name)"),
      sym("com/example/Person."),
      sym("com/example/Person.apply()."),
      sym("com/example/Person.apply().(name)"),
      sym("com/example/Person.unapply()."),
      sym("com/example/Person.unapply().(x$1)"),
      sym("com/example/Person.toString()."),
      // topLevelMethod
      sym("com/example/Features$package.topLevelMethod()."),
      // wrapper
      sym("com/example/Features$package."),
    ))
  }
}
