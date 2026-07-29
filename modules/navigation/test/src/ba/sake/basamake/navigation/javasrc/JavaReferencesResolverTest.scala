package ba.sake.basamake.navigation.javasrc

import munit.FunSuite
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, ResolvedFile}
import scala.meta.internal.semanticdb.Range

class JavaReferencesResolverTest extends FunSuite {

  private val testPath = os.pwd
  private val dummyRange = new Range(0, 0, 0, 0)

  private def defn(symbol: String, name: String, isType: Boolean): SymbolDefinition =
    SymbolDefinition(symbol, name, isType, dummyRange, testPath)

  private def resolveWith(st: SymbolTable, code: String): ResolvedFile =
    new JavaReferencesResolver(st).resolveFromContent("test.java", code, os.pwd)

  private def resolve(code: String): ResolvedFile =
    resolveWith(new SymbolTable, code)

  private def occ(symbol: String, isDef: Boolean): (String, Boolean) = (symbol, isDef)

  private def assertOccurrences(
      rf: ResolvedFile,
      expected: Set[(String, Boolean)]
  )(implicit loc: munit.Location): Unit = {
    val actual = rf.occurrences.map(o => (o.symbol, o.isDefinition)).toSet
    val actualWithoutLocal = actual.filterNot(_._1.startsWith("local"))
    val expectedWithoutLocal = expected.filterNot(_._1.startsWith("local"))
    assertEquals(actualWithoutLocal, expectedWithoutLocal, clues(actual))
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

  // ── R.1 bare type ref same-package (no import) ───────────────

  test("R.1 bare type ref same-package") {
    val st = new SymbolTable
    st.add(defn("a/b/C#", "C", isType = true))
    val code = """package a.b; class D { C field; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "a/b/C#", isDef = false)
  }

  // ── R.2 explicit import ──────────────────────────────────────

  test("R.2 explicit import") {
    val st = new SymbolTable
    st.add(defn("a/b/C#", "C", isType = true))
    val code = """package x; import a.b.C; class D { C field; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "a/b/C#", isDef = false)
  }

  // ── R.3 on-demand import ─────────────────────────────────────

  test("R.3 on-demand import") {
    val st = new SymbolTable
    st.add(defn("a/b/C#", "C", isType = true))
    val code = """package x; import a.b.*; class D { C field; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "a/b/C#", isDef = false)
  }

  // ── R.4 qualified type ref ───────────────────────────────────

  test("R.4 qualified type ref") {
    val st = new SymbolTable
    st.add(defn("pkg/C#", "C", isType = true))
    val code = """package x; import pkg.C; class D { pkg.C field; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/C#", isDef = false)
  }

  // ── R.5 new C() ──────────────────────────────────────────────

  test("R.5 new C()") {
    val st = new SymbolTable
    st.add(defn("pkg/C#", "C", isType = true))
    st.add(defn("pkg/C#`<init>`().", "<init>", isType = false))
    val code = """package pkg; class Test { Object m() { return new C(); } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/C#", isDef = false)
    assertHasOccurrence(rf, "pkg/C#`<init>`().", isDef = false)
  }

  // ── R.6 static call Class.method() ───────────────────────────

  test("R.6 static call Class.method()") {
    val st = new SymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#doStuff().", "doStuff", isType = false))
    val code = """package pkg; class Test { void m() { Util.doStuff(); } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#doStuff().", isDef = false)
  }

  // ── R.7 field access obj.f ───────────────────────────────────

  test("R.7 field access obj.f") {
    val st = new SymbolTable
    st.add(defn("pkg/Foo#", "Foo", isType = true))
    st.add(defn("pkg/Foo#f.", "f", isType = false))
    val code = """package pkg; class Test { int m(Foo obj) { return obj.f; } }"""
    val rf = resolveWith(st, code)
    // v1: chained field access not fully resolved without type tracking
    // obj resolves to param, but we can't know its type to resolve .f
    assertHasOccurrence(rf, "", isDef = false)
  }

  // ── R.8 local var defs in method body ────────────────────────

  test("R.8 local var defs in method body") {
    val code = """package pkg; class Test { void m() { int x = 1; x = 2; } }"""
    val rf = resolve(code)
    assertLocals(rf, Set(("local0", false)))
    // ref occurrence only (def in locals, not occurrences)
    assertHasOccurrence(rf, "local0", isDef = false)
  }

  // ── R.9 method param defs + reference inside body ────────────

  test("R.9 method param defs + reference inside body") {
    val code = """package pkg; class C { int m(int p) { return p; } }"""
    val st = new SymbolTable
    val rf = resolveWith(st, code)
    // ref occurrence to param (def in SymbolTable now, not occurrences)
    assertHasOccurrence(rf, "pkg/C#m().(p)", isDef = false)
  }

  // ── R.10 bare String s resolves via JavaLangSymbols ──────────

  test("R.10 bare String ref resolves via JavaLangSymbols") {
    val code = """package pkg; class C { String s; }"""
    val rf = resolve(code)
    assertHasOccurrence(rf, "java/lang/String#", isDef = false)
  }

  // ── R.11 unresolved name ─────────────────────────────────────

  test("R.11 unresolved name") {
    val code = """package pkg; class C { UnknownType x; }"""
    val rf = resolve(code)
    assertHasOccurrence(rf, "", isDef = false)
  }

  // ── R.12 enum constant ref ───────────────────────────────────

  test("R.12 enum constant ref") {
    val st = new SymbolTable
    st.add(defn("pkg/Color#", "Color", isType = true))
    st.add(defn("pkg/Color#RED.", "RED", isType = false))
    val code = """package pkg; class Test { Color c = Color.RED; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Color#RED.", isDef = false)
  }

  // ── R.13 method called on resolved qual ─────────────────────

  test("R.13 method called on resolved qual — v1 chained call unresolved") {
    val st = new SymbolTable
    st.add(defn("pkg/Obj#", "Obj", isType = true))
    st.add(defn("pkg/Obj#method().", "method", isType = false))
    val code = """package pkg; class Test { void m(Obj obj) { obj.method(); } }"""
    val rf = resolveWith(st, code)
    // v1: chained method call not resolved, expect unresolved for method name
    assertHasOccurrence(rf, "", isDef = false)
  }

  // ── R.14 record accessor call ───────────────────────────────

  test("R.14 record accessor call — v1 chained call unresolved") {
    val st = new SymbolTable
    st.add(defn("pkg/R#", "R", isType = true))
    st.add(defn("pkg/R#x().", "x", isType = false))
    val code = """package pkg; class Test { int m(R r) { return r.x(); } }"""
    val rf = resolveWith(st, code)
    // v1 non-goal: chained call not resolved
    assertHasOccurrence(rf, "", isDef = false)
  }

  // ── R.15 ref to nested class ─────────────────────────────────

  test("R.15 ref to nested class") {
    val st = new SymbolTable
    st.add(defn("pkg/Outer#", "Outer", isType = true))
    st.add(defn("pkg/Outer#Inner#", "Inner", isType = true))
    val code = """package pkg; class Test { Outer.Inner v; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Outer#Inner#", isDef = false)
  }
}
