package ba.sake.basamake.navigation

import munit.FunSuite

/**
 * Tests ScalaSourceParser against realistic Scala 3.8.x stdlib syntax
 * including experimental features like capture checking, transparent
 * traits, erased definitions, and named tuples.
 *
 * Scala3Future dialect (scalameta 4.17.2) supports capture checking (^) syntax.
 */
class ScalaSourceParserRealisticTest extends FunSuite {

  // ── Supported: scalameta 4.17.2 handles these ──────────────

  test("parses capture checking import + trait (no ^ syntax)") {
    val code =
      """package scala
        |import language.experimental.captureChecking
        |trait Capability extends Any
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "Capability"), clues(defs))
    assertEquals(defs.map(_.name).sorted, List("Capability"))
  }

  test("parses sealed trait with packages") {
    val code =
      """package scala
        |package caps
        |import language.experimental.captureChecking
        |sealed trait Capability extends Any
        |trait Classifier
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "Capability"), clues(defs))
    assert(defs.exists(_.name == "Classifier"), clues(defs))
  }

  test("parses opaque type with tuple bounds") {
    val code =
      """package scala
        |object NamedTuple {
        |  opaque type NamedTuple[N <: Tuple, +V <: Tuple] = V
        |  opaque type AnyNamedTuple = Any
        |}
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "NamedTuple"), clues(defs))
    // NamedTuple object has package scala prefix
    assert(defs.exists(d => d.name == "NamedTuple" && d.symbol == "scala/NamedTuple."), clues(defs))
  }

  test("parses @experimental annotation + class") {
    val code =
      """package scala.annotation
        |import language.experimental.captureChecking
        |@experimental
        |class retains[Elems] extends annotation.StaticAnnotation
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "retains"), clues(defs))
  }

  test("parses multiple experimental imports + trait") {
    val code =
      """package scala
        |import language.experimental.captureChecking
        |import language.experimental.erasedDefinitions
        |import annotation.experimental
        |trait Pure extends Any { this: Pure => }
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "Pure"), clues(defs))
  }

  test("parses transparent trait") {
    val code =
      """package scala.collection.immutable
        |transparent trait SetOps[A, +CC[X], +C <: SetOps[A, CC, C]]
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "SetOps"), clues(defs))
  }

  test("parses transparent trait with pure bound") {
    val code =
      """package scala.collection.immutable
        |transparent trait StrictOptimizedSeqOps[+A, +CC[B] <: caps.Pure, +C]
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "StrictOptimizedSeqOps"), clues(defs))
  }

  test("parses erased import + extends compiletime.Erased") {
    val code =
      """package scala
        |import language.experimental.erasedDefinitions
        |trait Precise extends compiletime.Erased
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "Precise"), clues(defs))
  }

  test("parses given with type lambda context bound") {
    val code =
      """package scala
        |object NamedTuple {
        |  given namedTupleOrdering: [N <: Tuple, V <: Tuple] => (ord: Ordering[V]) => Ordering[NamedTuple[N, V]]:
        |    ???
        |}
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "NamedTuple"), clues(defs))
  }

  test("parses caps/Capability trait from stdlib") {
    val code =
      """package scala
        |package caps
        |import language.experimental.captureChecking
        |sealed trait Capability extends Any
        |trait Classifier
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "Capability"), clues(defs))
    assert(defs.exists(_.name == "Classifier"), clues(defs))
  }

  // ── Capture checking (^) — now supported via Scala3Future ──

  test("parses capture caret in return type (Scala3Future)") {
    val code =
      """package scala
        |import language.experimental.captureChecking
        |trait Foo[A] { def bar(x: A^): A^ }
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "Foo"), clues(defs))
  }

  test("parses capture sets with refs (Scala3Future)") {
    val code =
      """package scala
        |import language.experimental.captureChecking
        |trait Foo[A, B] { def compose(f: A^, g: B^): Foo[A, B]^{f, g} }
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "Foo"), clues(defs))
  }

  test("parses self-type with capture (Scala3Future)") {
    val code =
      """package scala
        |import language.experimental.captureChecking
        |trait PartialFunction[-A, +B] { self: PartialFunction[A, B]^ =>
        |  def isDefinedAt(x: A): Boolean
        |}
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "PartialFunction"), clues(defs))
  }

  test("parses capture in constructor param (Scala3Future)") {
    val code =
      """package scala
        |import language.experimental.captureChecking
        |trait Foo[A] extends Any
        |class Bar[A](pf: Foo[A]^) extends Foo[A]
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "Foo"), clues(defs))
    assert(defs.exists(_.name == "Bar"), clues(defs))
  }

  test("parses real PartialFunction header from stdlib (Scala3Future)") {
    // Actual scala.PartialFunction head from Scala 3.8.4 — ^ syntax now parses
    val code =
      """/*
        | * Scala (https://www.scala-lang.org)
        | */
        |package scala
        |import language.experimental.captureChecking
        |trait PartialFunction[-A, +B] extends Function1[A, B] { self: PartialFunction[A, B]^ =>
        |  def isDefinedAt(x: A): Boolean
        |  def orElse[A1 <: A, B1 >: B](that: PartialFunction[A1, B1]^): PartialFunction[A1, B1]^{this, that}
        |}
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "PartialFunction"), clues(defs))
  }

  test("parses capture checking method with AnyRef^ params") {
    val code =
      """package foo
        |
        |object Bar:
        |  def copy(src: AnyRef^, dest: AnyRef^): Unit = {}
        |""".stripMargin
    val defs = ScalaSourceParser.extractDefinitions(code)
    assert(defs.exists(_.name == "Bar"), clues(defs))
    assert(defs.exists(d => d.name == "copy" && d.symbol == "foo/Bar.copy()."), clues(defs))
  }

  // ── Graceful degradation for genuinely unparsable input ──

  test("gracefully degrades on completely unparseable input") {
    val code = "class class class"
    val defs = ScalaSourceParser.extractDefinitions(code)
    assertEquals(defs, List.empty)
  }
}
