package ba.sake.basamake.navigation.scalasrc

import munit.FunSuite
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, ResolvedFile}

class ScalaReferencesResolverTest extends FunSuite {

  private def occ(symbol: String): String = symbol

  private def assertOccurrences(
      rf: ResolvedFile,
      expected: Set[String]
  )(implicit loc: munit.Location): Unit = {
    val actual = rf.occurrences.map(o => o.symbol).toSet
    assertEquals(actual, expected, clues(actual))
  }

  private def assertHasOccurrence(
      rf: ResolvedFile,
      symbol: String
  )(implicit loc: munit.Location): Unit = {
    val matches = rf.occurrences.filter(o => o.symbol == symbol)
    assert(matches.nonEmpty, s"Expected occurrence ($symbol) not found in ${rf.occurrences.map(o => o.symbol)}")
  }

  private def assertLocals(
      rf: ResolvedFile,
      expected: Set[(String, Boolean)]
  )(implicit loc: munit.Location): Unit = {
    val actual = rf.locals.map(d => (d.symbol, d.isType)).toSet
    assertEquals(actual, expected, clues(actual))
  }

  // ── R.1 bare-name ref to workspace class with explicit import ──

  test("R.1 bare-name ref to workspace class with explicit import") {
    val st = new SymbolTable
    st.add(SymbolDefinition("a/b/C#", "C", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package x; import a.b.C; val v: C = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "a/b/C#")
  }

  // ── R.2 ref to class in same package (no import) ──────────────

  test("R.2 ref to class in same package (no import)") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; class Bar extends Foo"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/Foo#")
  }

  // ── R.3 wildcard import ───────────────────────────────────────

  test("R.3 wildcard import") {
    val st = new SymbolTable
    st.add(SymbolDefinition("a/b/Thing#", "Thing", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package x; import a.b.*; val t: Thing = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "a/b/Thing#")
  }

  // ── R.4 rename import ─────────────────────────────────────────

  test("R.4 rename import") {
    val st = new SymbolTable
    st.add(SymbolDefinition("a/b/C#", "C", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package x; import a.b.{C => D}; val d: D = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "a/b/C#")
  }

  // ── R.5 new C(args) — emits C# + ctor ref ─────────────────────

  test("R.5 new C(args) — emits C# + ctor ref") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/C#", "C", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/C#`<init>`().", "<init>", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; class C; object Main { val c = new C() }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/C#")
    assertHasOccurrence(rf, "pkg/C#`<init>`().")
  }

  // ── R.6 Foo(args) where Foo is object with apply ──────────────

  test("R.6 Foo(args) where Foo is object with apply") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Foo.", "Foo", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/Foo.apply().", "apply", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; object Main { val r = Foo(42) }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/Foo.")
    assertHasOccurrence(rf, "pkg/Foo.apply().")
  }

  // ── R.7 method call obj.meth(x) ───────────────────────────────

  test("R.7 method call obj.meth(x)") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Obj.", "Obj", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/Obj.m().", "m", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; class Main { def f(o: Obj): Int = o.m(7) }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    // Type annotation ref to Obj (resolved from param type)
    assertHasOccurrence(rf, "pkg/Obj.")
    // o resolves to param symbol (correct v1 behavior)
    assertHasOccurrence(rf, "pkg/Main#f().(o)")
    // Member resolution of m on `o` is beyond v1 — skip assert
  }

  // ── R.8 method param ref inside body ──────────────────────────

  test("R.8 method param ref inside body") {
    val code = """package pkg; class C { def m(x: Int): Int = x }"""
    val st = new SymbolTable
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    // ref occurrence at x in body (def occurrence lives in SymbolTable now)
    assertHasOccurrence(rf, "pkg/C#m().(x)")
    // param def is not emitted as occurrence — it's in SymbolTable
  }

  // ── R.9 method-local val — local<N> ───────────────────────────

  test("R.9 method-local val — local<N>") {
    val code = """package pkg; class C { def m(): Int = { val y = 1; y } }"""
    val st = new SymbolTable
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    // ref occurrence to y only (def occurrence now in locals, not occurrences)
    assertHasOccurrence(rf, "local0")
    // Should also have locals entry for the definition
    assertLocals(rf, Set(("local0", false)))
  }

  // ── R.10 unresolved name ──────────────────────────────────────

  test("R.10 unresolved name") {
    val code = """package pkg; class C { def m(): Nothing = doStuff() }"""
    val st = new SymbolTable
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "")
  }

  // ── R.11 Predef List(...) ─────────────────────────────────────

  // TODO List type
  test("R.11 Predef List(...)") {
    val st = new SymbolTable
    st.add(SymbolDefinition("scala/collection/immutable/List.", "List", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("scala/collection/immutable/List.apply().", "apply", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; object Main { val xs = List(1, 2) }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "scala/collection/immutable/List.")
    assertHasOccurrence(rf, "scala/collection/immutable/List.apply().")
  }

  // ── R.12 type-position ref val x: Foo ─────────────────────────

  test("R.12 type-position ref val x: Foo") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; class Main { val x: Foo = null }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/Foo#")
  }

  // ── R.13 nested a.b.c.d select ────────────────────────────────

  test("R.13 nested a.b.c.d select") {
    val st = new SymbolTable
    // NOTE: These are term symbols nested by dot (member) chain, matching the
    // SemanticDB convention for nested objects/vals: a., a.b., a.b.c., a.b.c.d.
    st.add(SymbolDefinition("_empty_/a.", "a", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("_empty_/a.b.", "b", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("_empty_/a.b.c.", "c", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("_empty_/a.b.c.d.", "d", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; object Main { val r = a.b.c.d }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "_empty_/a.")
    assertHasOccurrence(rf, "_empty_/a.b.")
    assertHasOccurrence(rf, "_empty_/a.b.c.")
    assertHasOccurrence(rf, "_empty_/a.b.c.d.")
  }

  // ── R.14 case class apply Person("x") ─────────────────────────

  test("R.14 case class apply Person(\"x\")") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Person.", "Person", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/Person.apply().", "apply", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; object Main { val p = Person("x") }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/Person.")
    assertHasOccurrence(rf, "pkg/Person.apply().")
  }

  // ── R.15 unimport ─────────────────────────────────────────────

  test("R.15 unimport") {
    val st = new SymbolTable
    st.add(SymbolDefinition("a/b/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("a/b/Bar#", "Bar", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package x; import a.b.{Foo => _, *}; val v: Bar = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "a/b/Bar#")
    // TODO check Foo is empty
  }

  // ── R.16 same-file top-level sibling def (no package) ────────

  test("R.16 same-file top-level sibling def (no package)") {
    val st = new SymbolTable
    new ScalaDefinitionsExtractor(st).extractFromContent(
      "bla.scala",
      """@main def blaMain(): Unit = println(add(2, 3))
        |def add(a: Int, b: Int): Int = a + b
        |""".stripMargin,
      os.pwd / "bla.scala"
    )
    val code = """@main def blaMain(): Unit = println(add(2, 3))
                |def add(a: Int, b: Int): Int = a + b
                |""".stripMargin
    val rf = new ScalaReferencesResolver(st).resolveFromContent("bla.scala", code, os.pwd / "bla.scala")
    // the call `add` must resolve to the wrapper-prefixed def symbol, NOT ""
    assertHasOccurrence(rf, "_empty_/bla$package.add().")
    assert(!rf.occurrences.exists(o => o.symbol.isEmpty && o.range.startLine == 0),
      s"expected no unresolved occurrence at the call site, got ${rf.occurrences}")
  }

  // ── R.17 cross-file top-level def (different wrapper) ─────────

  test("R.17 cross-file top-level def (different wrapper)") {
    val st = new SymbolTable
    new ScalaDefinitionsExtractor(st).extractFromContent(
      "Sib.scala",
      """def add(a: Int, b: Int): Int = a + b
        |""".stripMargin,
      os.pwd / "Sib.scala"
    )
    val code = """@main def main(): Unit = println(add(2, 3))
                |""".stripMargin
    val rf = new ScalaReferencesResolver(st).resolveFromContent("Main.scala", code, os.pwd / "Main.scala")
    assertHasOccurrence(rf, "_empty_/Sib$package.add().")
    assert(!rf.occurrences.exists(o => o.symbol.isEmpty && o.range.startLine == 0),
      s"expected no unresolved at call site, got ${rf.occurrences}")
  }

  // ── R.NAMED named arg emits param ref ─────────────────────────

  test("named arg emits param ref") {
    val st = new SymbolTable
    val dummyPath = os.pwd
    val r = new Range(0, 0, 0, 0)
    st.add(SymbolDefinition("pkg/Util.", "Util", isType = false, r, dummyPath / "Util.scala"))
    st.add(SymbolDefinition("pkg/Util.add().", "add", isType = false, r, dummyPath / "Util.scala"))
    st.add(SymbolDefinition("pkg/Util.add().(a)", "a", isType = false, r, dummyPath / "Util.scala"))
    st.add(SymbolDefinition("pkg/Util.add().(b)", "b", isType = false, r, dummyPath / "Util.scala"))
    val code = """package pkg; object Use { Util.add(a = 1, b = 2) }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, dummyPath / "test.scala")
    assertHasOccurrence(rf, "pkg/Util.add().")
    assertHasOccurrence(rf, "pkg/Util.add().(a)")
    assertHasOccurrence(rf, "pkg/Util.add().(b)")
  }

  // ── R.NEW method call on `new C().m` ───────────────────────────

  test("method call on new C().m resolves to C#m") {
    val st = new SymbolTable
    val r = new Range(0, 0, 0, 0)
    st.add(SymbolDefinition("pkg/C#", "C", isType = true, r, os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/C#m().", "m", isType = false, r, os.pwd / "dummy.scala"))
    val code = """package pkg; class U { val v = new C().m() }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/C#")
    assertHasOccurrence(rf, "pkg/C#m().")
  }
}
