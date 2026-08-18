package ba.sake.basamake.index.javasrc

import munit.FunSuite
import ba.sake.basamake.index.{SymbolTable, InMemorySymbolTable, SymbolDefinition, ResolvedFile}
import scala.meta.internal.semanticdb.Range

class JavaReferencesResolverTest extends FunSuite {

  private val testPath = os.pwd
  private val dummyRange = new Range(0, 0, 0, 0)

  private def defn(symbol: String, name: String, isType: Boolean): SymbolDefinition =
    SymbolDefinition(symbol, name, isType, dummyRange, testPath)

  private def resolveWith(st: SymbolTable, code: String): ResolvedFile =
    new JavaReferencesResolver(st).resolveFromContent("test.java", code, os.pwd)

  private def resolve(code: String): ResolvedFile =
    resolveWith(new InMemorySymbolTable, code)

  private def occ(symbol: String): String = symbol

  private def assertOccurrences(
      rf: ResolvedFile,
      expected: Set[String]
  )(implicit loc: munit.Location): Unit = {
    val actual = rf.occurrences.map(o => o.symbol).toSet
    val actualWithoutLocal = actual.filterNot(_.startsWith("local"))
    val expectedWithoutLocal = expected.filterNot(_.startsWith("local"))
    assertEquals(actualWithoutLocal, expectedWithoutLocal, clues(actual))
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

  // ── R.1 bare type ref same-package (no import) ───────────────

  test("R.1 bare type ref same-package") {
    val st = new InMemorySymbolTable
    st.add(defn("a/b/C#", "C", isType = true))
    val code = """package a.b; class D { C field; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "a/b/C#")
  }

  // ── R.2 explicit import ──────────────────────────────────────

  test("R.2 explicit import") {
    val st = new InMemorySymbolTable
    st.add(defn("a/b/C#", "C", isType = true))
    val code = """package x; import a.b.C; class D { C field; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "a/b/C#")
  }

  // ── R.3 on-demand import ─────────────────────────────────────

  test("R.3 on-demand import") {
    val st = new InMemorySymbolTable
    st.add(defn("a/b/C#", "C", isType = true))
    val code = """package x; import a.b.*; class D { C field; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "a/b/C#")
  }

  // ── R.4 qualified type ref ───────────────────────────────────

  test("R.4 qualified type ref") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/C#", "C", isType = true))
    val code = """package x; import pkg.C; class D { pkg.C field; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/C#")
  }

  // ── R.5 new C() ──────────────────────────────────────────────

  test("R.5 new C()") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/C#", "C", isType = true))
    st.add(defn("pkg/C#`<init>`().", "<init>", isType = false))
    val code = """package pkg; class Test { Object m() { return new C(); } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/C#")
    assertHasOccurrence(rf, "pkg/C#`<init>`().")
  }

  // ── R.6 static call Class.method() ───────────────────────────

  test("R.6 static call Class.method()") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#doStuff().", "doStuff", isType = false))
    val code = """package pkg; class Test { void m() { Util.doStuff(); } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#doStuff().")
  }

  test("R.6b static call resolves when the only overload is beyond index 8 (sparse table)") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    // 0..8 missing, only overload (+9) present — the old fixed 0..8 scan missed it
    st.add(defn("pkg/Util#doStuff(+9).", "doStuff", isType = false))
    val code = """package pkg; class Test { void m() { Util.doStuff(); } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#doStuff(+9).")
  }

  test("R.6c static call with overloads 0..12 resolves to the lowest index") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    (0 to 12).foreach { i =>
      val dis = if (i == 0) "" else s"+$i"
      st.add(defn(s"pkg/Util#doStuff($dis).", "doStuff", isType = false))
    }
    val code = """package pkg; class Test { void m() { Util.doStuff(); } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#doStuff().")
  }

  // ── R.7 field access obj.f ───────────────────────────────────

  test("R.7 field access obj.f") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Foo#", "Foo", isType = true))
    st.add(defn("pkg/Foo#f.", "f", isType = false))
    val code = """package pkg; class Test { int m(Foo obj) { return obj.f; } }"""
    val rf = resolveWith(st, code)
    // v1: chained field access not fully resolved without type tracking
    // obj resolves to param, but we can't know its type to resolve .f
    assertHasOccurrence(rf, "")
  }

  // ── R.8 local var defs in method body ────────────────────────

  test("R.8 local var defs in method body") {
    val code = """package pkg; class Test { void m() { int x = 1; x = 2; } }"""
    val rf = resolve(code)
    assertLocals(rf, Set(("local0", false)))
    // ref occurrence only (def in locals, not occurrences)
    assertHasOccurrence(rf, "local0")
  }

  // ── R.9 method param defs + reference inside body ────────────

  test("R.9 method param defs + reference inside body") {
    val code = """package pkg; class C { int m(int p) { return p; } }"""
    val st = new InMemorySymbolTable
    val rf = resolveWith(st, code)
    // ref occurrence to param (def in SymbolTable now, not occurrences)
    assertHasOccurrence(rf, "pkg/C#m().(p)")
  }

  // ── R.10 bare String s resolves via JavaLangSymbols ──────────

  test("R.10 bare String ref resolves via JavaLangSymbols") {
    val code = """package pkg; class C { String s; }"""
    val rf = resolve(code)
    assertHasOccurrence(rf, "java/lang/String#")
  }

  // ── R.11 unresolved name ─────────────────────────────────────

  test("R.11 unresolved name") {
    val code = """package pkg; class C { UnknownType x; }"""
    val rf = resolve(code)
    assertHasOccurrence(rf, "")
  }

  // ── R.12 enum constant ref ───────────────────────────────────

  test("R.12 enum constant ref") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Color#", "Color", isType = true))
    st.add(defn("pkg/Color#RED.", "RED", isType = false))
    val code = """package pkg; class Test { Color c = Color.RED; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Color#RED.")
  }

  // ── R.13 method called on resolved qual ─────────────────────

  test("R.13 method called on resolved qual — v1 chained call unresolved") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Obj#", "Obj", isType = true))
    st.add(defn("pkg/Obj#method().", "method", isType = false))
    val code = """package pkg; class Test { void m(Obj obj) { obj.method(); } }"""
    val rf = resolveWith(st, code)
    // v1: chained method call not resolved, expect unresolved for method name
    assertHasOccurrence(rf, "")
  }

  // ── R.14 record accessor call ───────────────────────────────

  test("R.14 record accessor call — v1 chained call unresolved") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/R#", "R", isType = true))
    st.add(defn("pkg/R#x().", "x", isType = false))
    val code = """package pkg; class Test { int m(R r) { return r.x(); } }"""
    val rf = resolveWith(st, code)
    // v1 non-goal: chained call not resolved
    assertHasOccurrence(rf, "")
  }

  // ── R.15 ref to nested class ─────────────────────────────────

  test("R.15 ref to nested class") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Outer#", "Outer", isType = true))
    st.add(defn("pkg/Outer#Inner#", "Inner", isType = true))
    val code = """package pkg; class Test { Outer.Inner v; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Outer#Inner#")
  }

  // ── R.STATIC static imports ─────────────────────────────────

  test("R.S1 static single import of a field resolves") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#MAX.", "MAX", isType = false))
    val code = """package x; import static pkg.Util.MAX; class Test { int v = MAX; }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#MAX.")
  }

  test("R.S2 static single import of a method resolves to the method symbol") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#helper().", "helper", isType = false))
    val code = """package x; import static pkg.Util.helper; class Test { void m() { helper(); } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#helper().")
  }

  test("R.S3 static on-demand import resolves fields and methods") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#helper().", "helper", isType = false))
    st.add(defn("pkg/Util#MAX.", "MAX", isType = false))
    val code = """package x; import static pkg.Util.*; class Test { int v = MAX; void m() { helper(); } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#MAX.")
    assertHasOccurrence(rf, "pkg/Util#helper().")
  }

  test("R.S4 static single import stays unresolved when the member is unknown") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    val code = """package x; import static pkg.Util.unknown; class Test { void m() { unknown(); } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "")
  }

  // ── R.LOOPS loop statements ─────────────────────────────────

  test("R.16 for loop: initializer local + body refs resolve") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#work().", "work", isType = false))
    val code = """package pkg; class Test { void m() { for (int i = 0; i < 3; i++) { Util.work(); } } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#work().")
    // `i` (compare + update) resolves to the loop local
    assertHasOccurrence(rf, "local0")
    assertLocals(rf, Set(("local0", false)))
  }

  test("R.17 for-each loop: variable + body refs resolve") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#work().", "work", isType = false))
    st.add(defn("java/lang/String#", "String", isType = true))
    val code = """package pkg; class Test { void m(java.util.List<String> xs) { for (String s : xs) { Util.work(); } } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#work().")
    assertLocals(rf, Set(("local0", false)))
  }

  test("R.18 while and do-while loop bodies resolve") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#work().", "work", isType = false))
    val code = """package pkg; class Test { void m() { while (true) { Util.work(); } do { Util.work(); } while (false); } }"""
    val rf = resolveWith(st, code)
    // both bodies resolve (2 occurrences of work)
    assertEquals(rf.occurrences.count(_.symbol == "pkg/Util#work()."), 2,
      s"expected work() in both loop bodies, got ${rf.occurrences.map(_.symbol)}")
  }

  // ── R.TRY try statements ────────────────────────────────────

  test("R.19 try/catch/finally bodies resolve; catch param is a local") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#work().", "work", isType = false))
    val code =
      """package pkg; import java.io.IOException;
        |class Test { void m() {
        |  try { Util.work(); }
        |  catch (IOException e) { e.printStackTrace(); }
        |  finally { Util.work(); }
        |} }""".stripMargin
    val rf = resolveWith(st, code)
    assertEquals(rf.occurrences.count(_.symbol == "pkg/Util#work()."), 2,
      s"expected work() in try + finally, got ${rf.occurrences.map(_.symbol)}")
    // catch param `e` is a bound local
    assertHasOccurrence(rf, "local0")
    assertLocals(rf, Set(("local0", false)))
  }

  test("R.20 try-with-resources: resource var and catch param are locals") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Res#", "Res", isType = true))
    val code =
      """package pkg; class Test { void m(Res r) {
        |  try (Res rr = r) { use(rr); }
        |  catch (Exception e) { e.hashCode(); }
        |} }""".stripMargin
    val rf = resolveWith(st, code)
    // rr (resource) + e (catch param) are both document locals
    assertLocals(rf, Set(("local0", false), ("local1", false)))
    assertHasOccurrence(rf, "local0")
    assertHasOccurrence(rf, "local1")
  }

  // ── R.MREF method references ────────────────────────────────

  test("R.21 method reference Foo::bar resolves to the method symbol") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#transform().", "transform", isType = false))
    val code = """package pkg; class Test { void m() { java.util.function.Function<String,String> f = Util::transform; } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#transform().")
  }

  test("R.22 constructor method reference Foo::new stays unresolved (no member name)") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    val code = """package pkg; class Test { void m() { java.util.function.Supplier<Util> s = Util::new; } }"""
    val rf = resolveWith(st, code)
    assertHasOccurrence(rf, "pkg/Util#")
  }

  // ── R.IMPORT import-line refs ────────────────────────────────

  test("R.IMPORT1 single-type import emits a ref on the type segment") {
    val st = new InMemorySymbolTable
    st.add(defn("a/b/C#", "C", isType = true))
    // line 0: "package x; import a.b.C; class D { C field; }" — `C` at (0,22,0,23)
    val code = """package x; import a.b.C; class D { C field; }"""
    val rf = resolveWith(st, code)
    val importRefs = rf.occurrences.filter(o => o.range.startLine == 0 && o.range.startCharacter == 22)
    assertEquals(importRefs.map(_.symbol).toSet, Set("a/b/C#"))
    assertEquals(importRefs.map(_.range).toSet, Set(new Range(0, 22, 0, 23)),
      "the ref must cover only the type segment, not the package segments")
  }

  test("R.IMPORT2 static single import emits type + member refs") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    st.add(defn("pkg/Util#helper().", "helper", isType = false))
    val code = """package x; import static pkg.Util.helper; class D { void m() { helper(); } }"""
    val rf = resolveWith(st, code)
    val importRefs = rf.occurrences.filter(o => o.range.startLine == 0 && o.range.startCharacter >= 26)
    assertEquals(importRefs.map(_.symbol).toSet, Set("pkg/Util#", "pkg/Util#helper()."),
      "type segment refs the type; the member probes method overloads")
  }

  test("R.IMPORT3 static wildcard import emits a ref on the type") {
    val st = new InMemorySymbolTable
    st.add(defn("pkg/Util#", "Util", isType = true))
    val code = """package x; import static pkg.Util.*; class D { void m() { int v = 1; } }"""
    val rf = resolveWith(st, code)
    val importRefs = rf.occurrences.filter(o => o.range.startLine == 0 && o.range.startCharacter >= 26)
    assertEquals(importRefs.map(_.symbol).toSet, Set("pkg/Util#"))
  }

  test("R.IMPORT4 wildcard import emits nothing; package segments emit nothing") {
    val st = new InMemorySymbolTable
    val code = """package x; import a.b.*; class D {}"""
    val rf = resolveWith(st, code)
    assertEquals(rf.occurrences.count(_.range.startLine == 0), 0,
      "Java has no package objects — package segments must not emit refs")
  }
}
