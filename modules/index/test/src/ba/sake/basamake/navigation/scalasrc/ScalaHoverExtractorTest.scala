package ba.sake.basamake.index.scalasrc

import munit.FunSuite
import scala.meta.internal.semanticdb.Range

class ScalaHoverExtractorTest extends FunSuite {

  /** Parse `content`, then extract hover at the line containing `marker`
    * for the definition named `name`. */
  private def extractAt(content: String, name: String, marker: String): Option[(String, Option[String])] = {
    val src = ScalaHoverExtractor.parse(content).get
    val line = content.linesIterator.indexWhere(_.contains(marker))
    assert(line >= 0, s"marker not found: $marker")
    ScalaHoverExtractor.extractSource(src, name, new Range(line, 0, line, 0))
  }

  test("def with params and return type") {
    val code = "object A {\n  def add(x: Int, y: Int): Int = x + y\n}"
    val res = extractAt(code, "add", "def add(x: Int")
    assertEquals(res.map(_._1), Some("def add(x: Int, y: Int): Int"))
  }

  test("def without return type") {
    val code = "object A {\n  def msg() = \"bla\"\n}"
    assertEquals(extractAt(code, "msg", "def msg").map(_._1), Some("def msg()"))
  }

  test("def with implicit params") {
    val code = "object A {\n  def foo(implicit x: Int): String = x.toString\n}"
    assertEquals(extractAt(code, "foo", "def foo").map(_._1), Some("def foo(implicit x: Int): String"))
  }

  test("def with by-name and varargs params") {
    val code = "object A {\n  def run(f: => Unit, xs: Int*): Unit = ()\n}"
    assertEquals(extractAt(code, "run", "def run").map(_._1), Some("def run(f: => Unit, xs: Int*): Unit"))
  }

  test("def with modifiers") {
    val code = "class A {\n  final override def id(x: Int): Int = x\n}"
    assertEquals(extractAt(code, "id", "def id").map(_._1), Some("final override def id(x: Int): Int"))
  }

  test("abstract decl def") {
    val code = "trait A {\n  def abs(x: Int): Int\n}"
    assertEquals(extractAt(code, "abs", "def abs").map(_._1), Some("def abs(x: Int): Int"))
  }

  test("val with and without type") {
    val code = "object A {\n  val typed: Int = 5\n  val inferred = \"x\"\n}"
    assertEquals(extractAt(code, "typed", "val typed").map(_._1), Some("val typed: Int"))
    assertEquals(extractAt(code, "inferred", "val inferred").map(_._1), Some("val inferred"))
  }

  test("case class with type params and ctor params") {
    val code = "case class Person(name: String, age: Int)"
    assertEquals(extractAt(code, "Person", "case class Person").map(_._1),
      Some("case class Person(name: String, age: Int)"))
  }

  test("class with extends") {
    val code = "class Foo extends Bar\nclass Bar"
    assertEquals(extractAt(code, "Foo", "class Foo").map(_._1), Some("class Foo extends Bar"))
  }

  test("trait with type params") {
    val code = "trait Container[T]"
    assertEquals(extractAt(code, "Container", "trait Container").map(_._1), Some("trait Container[T]"))
  }

  test("object") {
    val code = "object utils"
    assertEquals(extractAt(code, "utils", "object utils").map(_._1), Some("object utils"))
  }

  test("type alias and opaque type") {
    val code = "object A {\n  type MyInt = Int\n  opaque type Id = Int\n}"
    assertEquals(extractAt(code, "MyInt", "type MyInt").map(_._1), Some("type MyInt = Int"))
    assertEquals(extractAt(code, "Id", "opaque type Id").map(_._1), Some("opaque type Id = Int"))
  }

  test("enum and enum case") {
    val code = "enum Color {\n  case Red, Green\n}"
    assertEquals(extractAt(code, "Color", "enum Color").map(_._1), Some("enum Color"))
    assertEquals(extractAt(code, "Red", "case Red").map(_._1), Some("case Red"))
  }

  test("given alias") {
    val code = "object A {\n  given intOrder: Ordering[Int] = Ordering.Int\n}"
    assertEquals(extractAt(code, "intOrder", "given intOrder").map(_._1), Some("given intOrder: Ordering[Int]"))
  }

  test("package object") {
    val code = "package object mypkg"
    assertEquals(extractAt(code, "mypkg", "package object").map(_._1), Some("package object mypkg"))
  }

  test("multi-line signature collapses") {
    val code = "object A {\n  def long(\n    a: Int,\n    b: String\n  ): Boolean = true\n}"
    assertEquals(extractAt(code, "long", "def long").map(_._1), Some("def long(a: Int, b: String): Boolean"))
  }

  test("param hover shows name and type") {
    val code = "object A {\n  def foo(x: Int): Int = x\n}"
    assertEquals(extractAt(code, "x", "x: Int").map(_._1), Some("x: Int"))
  }

  test("no match on wrong line") {
    val code = "object A {\n  def foo(): Int = 1\n}"
    val src = ScalaHoverExtractor.parse(code).get
    val res = ScalaHoverExtractor.extractSource(src, "foo", new Range(0, 0, 0, 0))
    assertEquals(res, None)
  }

  // ── doc comments ─────────────────────────────────────────────

  test("scaladoc directly above def is attached") {
    val code = "object A {\n  /** Doc line. */\n  def foo(): Int = 1\n}"
    assertEquals(extractAt(code, "foo", "def foo").map(_._2), Some(Some("Doc line.")))
  }

  test("scaladoc with one blank line gap is attached") {
    val code = "object A {\n  /** Doc line. */\n\n  def foo(): Int = 1\n}"
    assertEquals(extractAt(code, "foo", "def foo").map(_._2), Some(Some("Doc line.")))
  }

  test("comment two lines away is not attached") {
    val code = "object A {\n  /** Far comment. */\n\n\n  def foo(): Int = 1\n}"
    assertEquals(extractAt(code, "foo", "def foo").map(_._2), Some(None))
  }

  test("multi-line scaladoc is cleaned") {
    val code = "object A {\n  /**\n   * First line.\n   * Second line.\n   */\n  def foo(): Int = 1\n}"
    assertEquals(extractAt(code, "foo", "def foo").map(_._2), Some(Some("First line.\nSecond line.")))
  }

  test("doc of the wrong def is not attached") {
    val code = "/** Docs for bar. */\nobject A {\n  def bar(): Int = 1\n  def foo(): Int = 2\n}"
    assertEquals(extractAt(code, "foo", "def foo").map(_._2), Some(None))
  }

  test("sbt: build.sbt parses and extracts val signature") {
    val src = ScalaHoverExtractor.parse("build.sbt", "lazy val root = project").get
    val res = ScalaHoverExtractor.extractSource(src, "root", new Range(0, 0, 0, 0))
    assert(res.isDefined, s"expected hover for root, got $res")
    assertEquals(res.map(_._1), Some("lazy val root"))
  }
}
