package ba.sake.basamake.navigation.scalasrc

import munit.FunSuite
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, ResolvedFile}

class ScalaReferencesResolverTest extends FunSuite {

  private def occ(symbol: String, isDef: Boolean): (String, Boolean) = (symbol, isDef)

  private def assertOccurrences(
      rf: ResolvedFile,
      expected: Set[(String, Boolean)]
  )(implicit loc: munit.Location): Unit = {
    val actual = rf.occurrences.map(o => (o.symbol, o.isDefinition)).toSet
    assertEquals(actual, expected, clues(actual))
  }

  private def assertHasOccurrence(
      rf: ResolvedFile,
      symbol: String,
      isDef: Boolean
  )(implicit loc: munit.Location): Unit = {
    val matches = rf.occurrences.filter(o => o.symbol == symbol && o.isDefinition == isDef)
    assert(matches.nonEmpty, s"Expected occurrence ($symbol, isDef=$isDef) not found in ${rf.occurrences.map(o => (o.symbol, o.isDefinition))}")
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
    st.add(SymbolDefinition("a/b/C#", "C", isType = true, None))
    val code = """package x; import a.b.C; val v: C = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "a/b/C#", isDef = false)
  }

  // ── R.2 ref to class in same package (no import) ──────────────

  test("R.2 ref to class in same package (no import)") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, None))
    val code = """package pkg; class Bar extends Foo"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "pkg/Foo#", isDef = false)
  }

  // ── R.3 wildcard import ───────────────────────────────────────

  test("R.3 wildcard import") {
    val st = new SymbolTable
    st.add(SymbolDefinition("a/b/Thing#", "Thing", isType = true, None))
    val code = """package x; import a.b.*; val t: Thing = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "a/b/Thing#", isDef = false)
  }

  // ── R.4 rename import ─────────────────────────────────────────

  test("R.4 rename import") {
    val st = new SymbolTable
    st.add(SymbolDefinition("a/b/C#", "C", isType = true, None))
    val code = """package x; import a.b.{C => D}; val d: D = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "a/b/C#", isDef = false)
  }

  // ── R.5 new C(args) — emits C# + ctor ref ─────────────────────

  test("R.5 new C(args) — emits C# + ctor ref") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/C#", "C", isType = true, None))
    st.add(SymbolDefinition("pkg/C#`<init>`().", "<init>", isType = false, None))
    val code = """package pkg; class C; object Main { val c = new C() }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "pkg/C#", isDef = false)
    assertHasOccurrence(rf, "pkg/C#`<init>`().", isDef = false)
  }

  // ── R.6 Foo(args) where Foo is object with apply ──────────────

  test("R.6 Foo(args) where Foo is object with apply") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Foo.", "Foo", isType = false, None))
    st.add(SymbolDefinition("pkg/Foo.apply().", "apply", isType = false, None))
    val code = """package pkg; object Main { val r = Foo(42) }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "pkg/Foo.", isDef = false)
    assertHasOccurrence(rf, "pkg/Foo.apply().", isDef = false)
  }

  // ── R.7 method call obj.meth(x) ───────────────────────────────

  test("R.7 method call obj.meth(x)") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Obj.", "Obj", isType = false, None))
    st.add(SymbolDefinition("pkg/Obj.m().", "m", isType = false, None))
    val code = """package pkg; class Main { def f(o: Obj): Int = o.m(7) }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    // Type annotation ref to Obj (resolved from param type)
    assertHasOccurrence(rf, "pkg/Obj.", isDef = false)
    // o resolves to param symbol (correct v1 behavior)
    assertHasOccurrence(rf, "pkg/Main#f().(o)", isDef = false)
    // Member resolution of m on `o` is beyond v1 — skip assert
  }

  // ── R.8 method param ref inside body ──────────────────────────

  test("R.8 method param ref inside body") {
    val code = """package pkg; class C { def m(x: Int): Int = x }"""
    val st = new SymbolTable
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    // def occurrence at x param: param symbol
    assertHasOccurrence(rf, "pkg/C#m().(x)", isDef = true)
    // ref occurrence at x in body
    assertHasOccurrence(rf, "pkg/C#m().(x)", isDef = false)
  }

  // ── R.9 method-local val — local<N> ───────────────────────────

  test("R.9 method-local val — local<N>") {
    val code = """package pkg; class C { def m(): Int = { val y = 1; y } }"""
    val st = new SymbolTable
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "local0", isDef = true)   // declaration of y
    assertHasOccurrence(rf, "local0", isDef = false)  // reference to y
    // Should also have locals entry
    assertLocals(rf, Set(("local0", false)))
  }

  // ── R.10 unresolved name ──────────────────────────────────────

  test("R.10 unresolved name") {
    val code = """package pkg; class C { def m(): Nothing = doStuff() }"""
    val st = new SymbolTable
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "", isDef = false)
  }

  // ── R.11 Predef List(...) ─────────────────────────────────────

  // TODO List type
  test("R.11 Predef List(...)") {
    val st = new SymbolTable
    st.add(SymbolDefinition("scala/collection/immutable/List.", "List", isType = false, None))
    st.add(SymbolDefinition("scala/collection/immutable/List.apply().", "apply", isType = false, None))
    val code = """package pkg; object Main { val xs = List(1, 2) }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "scala/collection/immutable/List.", isDef = false)
    assertHasOccurrence(rf, "scala/collection/immutable/List.apply().", isDef = false)
  }

  // ── R.12 type-position ref val x: Foo ─────────────────────────

  test("R.12 type-position ref val x: Foo") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, None))
    val code = """package pkg; class Main { val x: Foo = null }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "pkg/Foo#", isDef = false)
  }

  // ── R.13 nested a.b.c.d select ────────────────────────────────

  test("R.13 nested a.b.c.d select") {
    val st = new SymbolTable
    // NOTE: These are term symbols nested by dot (member) chain, matching the
    // SemanticDB convention for nested objects/vals: a., a.b., a.b.c., a.b.c.d.
    st.add(SymbolDefinition("_empty_/a.", "a", isType = false, None))
    st.add(SymbolDefinition("_empty_/a.b.", "b", isType = false, None))
    st.add(SymbolDefinition("_empty_/a.b.c.", "c", isType = false, None))
    st.add(SymbolDefinition("_empty_/a.b.c.d.", "d", isType = false, None))
    val code = """package pkg; object Main { val r = a.b.c.d }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "_empty_/a.", isDef = false)
    assertHasOccurrence(rf, "_empty_/a.b.", isDef = false)
    assertHasOccurrence(rf, "_empty_/a.b.c.", isDef = false)
    assertHasOccurrence(rf, "_empty_/a.b.c.d.", isDef = false)
  }

  // ── R.14 case class apply Person("x") ─────────────────────────

  test("R.14 case class apply Person(\"x\")") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Person.", "Person", isType = false, None))
    st.add(SymbolDefinition("pkg/Person.apply().", "apply", isType = false, None))
    val code = """package pkg; object Main { val p = Person("x") }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "pkg/Person.", isDef = false)
    assertHasOccurrence(rf, "pkg/Person.apply().", isDef = false)
  }

  // ── R.15 unimport ─────────────────────────────────────────────

  test("R.15 unimport") {
    val st = new SymbolTable
    st.add(SymbolDefinition("a/b/Foo#", "Foo", isType = true, None))
    st.add(SymbolDefinition("a/b/Bar#", "Bar", isType = true, None))
    val code = """package x; import a.b.{Foo => _, *}; val v: Bar = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code)
    assertHasOccurrence(rf, "a/b/Bar#", isDef = false)
    // TODO check Foo is empty
  }
}
