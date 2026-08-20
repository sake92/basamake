package ba.sake.basamake.index.scalasrc

import munit.FunSuite
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.index.{SymbolTable, InMemorySymbolTable, SymbolDefinition, ResolvedFile}

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
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("a/b/C#", "C", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package x; import a.b.C; val v: C = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "a/b/C#")
  }

  // ── R.2 ref to class in same package (no import) ──────────────

  test("R.2 ref to class in same package (no import)") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; class Bar extends Foo"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/Foo#")
  }

  // ── R.2b ref to class in same nested-package (scala-library style) ──

  test("R.2b ref to class in same nested-package (scala-library style)") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("a/b/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package a
                  |package b
                  |class Bar extends Foo""".stripMargin
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "a/b/Foo#")
  }

  // ── R.3 wildcard import ───────────────────────────────────────

  test("R.3 wildcard import") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("a/b/Thing#", "Thing", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package x; import a.b.*; val t: Thing = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "a/b/Thing#")
  }

  // ── R.4 rename import ─────────────────────────────────────────

  test("R.4 rename import") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("a/b/C#", "C", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package x; import a.b.{C => D}; val d: D = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "a/b/C#")
  }

  // ── R.5 new C(args) — emits C# + ctor ref ─────────────────────

  test("R.5 new C(args) — emits C# + ctor ref") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/C#", "C", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/C#`<init>`().", "<init>", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; class C; object Main { val c = new C() }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/C#")
    assertHasOccurrence(rf, "pkg/C#`<init>`().")
  }

  // ── R.6 Foo(args) where Foo is object with apply ──────────────

  test("R.6 Foo(args) where Foo is object with apply") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/Foo.", "Foo", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/Foo.apply().", "apply", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; object Main { val r = Foo(42) }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/Foo.")
    assertHasOccurrence(rf, "pkg/Foo.apply().")
  }

  // ── R.7 method call obj.meth(x) ───────────────────────────────

  test("R.7 method call obj.meth(x)") {
    val st = new InMemorySymbolTable
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
    val st = new InMemorySymbolTable
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    // ref occurrence at x in body (def occurrence lives in SymbolTable now)
    assertHasOccurrence(rf, "pkg/C#m().(x)")
    // param def is not emitted as occurrence — it's in SymbolTable
  }

  // ── R.9 method-local val — local<N> ───────────────────────────

  test("R.9 method-local val — local<N>") {
    val code = """package pkg; class C { def m(): Int = { val y = 1; y } }"""
    val st = new InMemorySymbolTable
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    // ref occurrence to y only (def occurrence now in locals, not occurrences)
    assertHasOccurrence(rf, "local0")
    // Should also have locals entry for the definition
    assertLocals(rf, Set(("local0", false)))
  }

  // ── R.10 unresolved name ──────────────────────────────────────

  test("R.10 unresolved name") {
    val code = """package pkg; class C { def m(): Nothing = doStuff() }"""
    val st = new InMemorySymbolTable
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "")
  }

  // ── R.11 Predef List(...) ─────────────────────────────────────

  // TODO List type
  test("R.11 Predef List(...)") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("scala/collection/immutable/List.", "List", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("scala/collection/immutable/List.apply().", "apply", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; object Main { val xs = List(1, 2) }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "scala/collection/immutable/List.")
    assertHasOccurrence(rf, "scala/collection/immutable/List.apply().")
  }

  // ── R.12 type-position ref val x: Foo ─────────────────────────

  test("R.12 type-position ref val x: Foo") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; class Main { val x: Foo = null }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/Foo#")
  }

  // ── R.13 nested a.b.c.d select ────────────────────────────────

  test("R.13 nested a.b.c.d select") {
    val st = new InMemorySymbolTable
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
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/Person.", "Person", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/Person.apply().", "apply", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package pkg; object Main { val p = Person("x") }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/Person.")
    assertHasOccurrence(rf, "pkg/Person.apply().")
  }

  // ── R.15 unimport ─────────────────────────────────────────────

  test("R.15 unimport") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("a/b/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("a/b/Bar#", "Bar", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code = """package x; import a.b.{Foo => _, *}; val v: Bar = null"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "a/b/Bar#")
    assert(!rf.occurrences.exists(_.symbol == "a/b/Foo#"),
      s"Foo# should NOT appear — it was unimported (Foo => _)")
  }

  // ── R.16 same-file top-level sibling def (no package) ────────

  test("R.16 same-file top-level sibling def (no package)") {
    val st = new InMemorySymbolTable
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
    val st = new InMemorySymbolTable
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
    val st = new InMemorySymbolTable
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
    val st = new InMemorySymbolTable
    val r = new Range(0, 0, 0, 0)
    st.add(SymbolDefinition("pkg/C#", "C", isType = true, r, os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/C#m().", "m", isType = false, r, os.pwd / "dummy.scala"))
    val code = """package pkg; class U { val v = new C().m() }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/C#")
    assertHasOccurrence(rf, "pkg/C#m().")
  }

  test("method call resolves when the only overload is beyond index 8 (sparse table)") {
    val st = new InMemorySymbolTable
    val r = new Range(0, 0, 0, 0)
    st.add(SymbolDefinition("pkg/C#", "C", isType = true, r, os.pwd / "dummy.scala"))
    // 0..8 missing, only overload (+9) present — the old fixed 0..8 scan missed it
    st.add(SymbolDefinition("pkg/C#m(+9).", "m", isType = false, r, os.pwd / "dummy.scala"))
    val code = """package pkg; class U { val v = new C().m() }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/C#m(+9).")
  }

  test("method call with overloads 0..12 resolves to the lowest index") {
    val st = new InMemorySymbolTable
    val r = new Range(0, 0, 0, 0)
    st.add(SymbolDefinition("pkg/C#", "C", isType = true, r, os.pwd / "dummy.scala"))
    (0 to 12).foreach { i =>
      val dis = if (i == 0) "" else s"+$i"
      st.add(SymbolDefinition(s"pkg/C#m($dis).", "m", isType = false, r, os.pwd / "dummy.scala"))
    }
    val code = """package pkg; class U { val v = new C().m() }"""
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/C#m().")
  }

  // ── R.GIVEN given imports (named + given-all) don't crash ───────

  test("given imports don't crash resolution; other refs still resolve") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val code =
      """package pkg
        |import scala.util.given
        |import scala.util.{given Ordering}
        |class Bar { val f: Foo = null }
        |""".stripMargin
    val rf = new ScalaReferencesResolver(st).resolveFromContent("test.scala", code, os.pwd / "test.scala")
    assertHasOccurrence(rf, "pkg/Foo#")
  }

  // ── .sbt build definitions ────────────────────────────────────

  test("sbt: ref to val defined in same build.sbt resolves under _empty_/build.") {
    val st = new InMemorySymbolTable
    val code = "lazy val core = project\nlazy val cli = project.dependsOn(core)"
    new ScalaDefinitionsExtractor(st).extractFromContent("build.sbt", code, os.pwd / "build.sbt")
    val rf = new ScalaReferencesResolver(st).resolveFromContent("build.sbt", code, os.pwd / "build.sbt")
    assertHasOccurrence(rf, "_empty_/build.core.")
  }

  // ── sbt implicit imports (build.sbt only) ─────────────────────

  test("sbt: key from implicit Keys._ import resolves to sbt/Keys") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("sbt/Keys.semanticdbEnabled.", "semanticdbEnabled", isType = false, new Range(0,0,0,0), os.pwd / "Keys.scala"))
    val rf = new ScalaReferencesResolver(st).resolveFromContent("build.sbt", """ThisBuild / semanticdbEnabled := true""", os.pwd / "build.sbt")
    assertHasOccurrence(rf, "sbt/Keys.semanticdbEnabled.")
  }

  test("sbt: name key in settings resolves to sbt/Keys") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("sbt/Keys.name.", "name", isType = false, new Range(0,0,0,0), os.pwd / "Keys.scala"))
    val rf = new ScalaReferencesResolver(st).resolveFromContent("build.sbt", """name := "hello"""", os.pwd / "build.sbt")
    assertHasOccurrence(rf, "sbt/Keys.name.")
  }

  test("sbt: package-object member via implicit sbt._ import (file)") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("sbt/package.file().", "file", isType = false, new Range(0,0,0,0), os.pwd / "package.scala"))
    val rf = new ScalaReferencesResolver(st).resolveFromContent("build.sbt", """lazy val root = (project in file("."))""", os.pwd / "build.sbt")
    assertHasOccurrence(rf, "sbt/package.file().")
  }

  test("sbt: user val shadows the implicit sbt key") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("sbt/Keys.name.", "name", isType = false, new Range(0,0,0,0), os.pwd / "Keys.scala"))
    st.add(SymbolDefinition("_empty_/build.name.", "name", isType = false, new Range(0,0,0,0), os.pwd / "build.sbt"))
    val code = """lazy val name = "mine"
                  |lazy val cli = project.dependsOn(name)""".stripMargin
    val rf = new ScalaReferencesResolver(st).resolveFromContent("build.sbt", code, os.pwd / "build.sbt")
    assertHasOccurrence(rf, "_empty_/build.name.")
    assert(rf.occurrences.forall(_.symbol != "sbt/Keys.name."),
      s"shadowed key must not resolve: ${rf.occurrences.map(_.symbol)}")
  }

  test("scala style: no implicit sbt imports (no leakage)") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("sbt/Keys.semanticdbEnabled.", "semanticdbEnabled", isType = false, new Range(0,0,0,0), os.pwd / "Keys.scala"))
    val rf = new ScalaReferencesResolver(st).resolveFromContent("Main.scala", """ThisBuild / semanticdbEnabled := true""", os.pwd / "Main.scala")
    assert(rf.occurrences.forall(_.symbol.isEmpty),
      s"scala style must not resolve sbt keys: ${rf.occurrences.map(_.symbol)}")
  }

  // ── Scala 3 top-level statements ──────────────────────────────

  test("scala 3 top-level: ref to top-level val from method resolves under X$package") {
    val st = new InMemorySymbolTable
    val code = "val greeting = \"hello\"\ndef main(): Unit = println(greeting)"
    new ScalaDefinitionsExtractor(st).extractFromContent("Main.scala", code, os.pwd / "Main.scala")
    val rf = new ScalaReferencesResolver(st).resolveFromContent("Main.scala", code, os.pwd / "Main.scala")
    assertHasOccurrence(rf, "_empty_/Main$package.greeting.")
  }

  // ── import prefix package segments ────────────────────────────

  test("R.PKG import prefix package segments emit package symbols") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("a/b/package.", "package", isType = false, new Range(0,0,0,7), os.pwd / "package.scala"))
    st.add(SymbolDefinition("a/b/C#", "C", isType = true, new Range(0,0,0,1), os.pwd / "C.scala"))
    val code = "package x\nimport a.b.C\nclass D { val c: C = null }\n"
    val rf = new ScalaReferencesResolver(st).resolveFromContent("D.scala", code, os.pwd / "D.scala")
    val segRefs = rf.occurrences.filter(_.range.startLine == 1)
    assertEquals(segRefs.map(_.symbol).toSet, Set("a/", "a/b/", "a/b.", "a/b/C#"),
      "package segments emit package symbols (resolvable to the package object) + the object-candidate term alt; not empty")
  }
}
