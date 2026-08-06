package ba.sake.basamake.navigation.javasrc

import munit.FunSuite
import ba.sake.basamake.navigation.{SymbolTable, InMemorySymbolTable, SymbolDefinition, SymbolUtils}

class JavaDefinitionsExtractorTest extends FunSuite {

  private def extract(code: String): Set[SymbolDefinition] = {
    val table = new InMemorySymbolTable
    val extractor = new JavaDefinitionsExtractor(table)
    extractor.extractFromContent("test.java", code, os.pwd)
    table.all
  }

  private def sym(symbol: String, isType: Boolean = false): (String, Boolean) =
    (symbol, isType)

  private def assertSymbols(
    code: String,
    expected: Set[(String, Boolean)]
  )(implicit loc: munit.Location): Unit = {
    val actual = extract(code).map(d => (d.symbol, d.isType))
    assertEquals(actual, expected, clues(actual))
  }

  // ── J.1 empty pkg, single class ─────────────────────────────
  test("J.1 empty pkg, single class") {
    assertSymbols("class C {}", Set(
      sym("_empty_/C#", isType = true),
      sym("_empty_/C#`<init>`()."),
    ))
  }

  // ── J.2 named pkg ──────────────────────────────────────────
  test("J.2 named package, single class") {
    assertSymbols("package a; class C {}", Set(
      sym("a/C#", isType = true),
      sym("a/C#`<init>`()."),
    ))
  }

  // ── J.3 nested pkgs ────────────────────────────────────────
  test("J.3 nested packages a.b.c") {
    assertSymbols("package a.b.c; class C {}", Set(
      sym("a/b/c/C#", isType = true),
      sym("a/b/c/C#`<init>`()."),
    ))
  }

  // ── J.4 interface ──────────────────────────────────────────
  test("J.4 interface") {
    assertSymbols("package a; interface I {}", Set(
      sym("a/I#", isType = true),
    ))
  }

  // ── J.5 enum + 3 constants ─────────────────────────────────
  test("J.5 enum + 3 constants") {
    assertSymbols("package a; enum E { RED, GREEN, BLUE }", Set(
      sym("a/E#", isType = true),
      sym("a/E#RED."),
      sym("a/E#GREEN."),
      sym("a/E#BLUE."),
    ))
  }

  // ── J.6 @interface annotation with one element ──────────────
  test("J.6 @interface annotation with one element") {
    assertSymbols("package a; @interface A { String value(); }", Set(
      sym("a/A#", isType = true),
      sym("a/A#value()."),
    ))
  }

  // ── J.7 class with 2 overloads of m ────────────────────────
  test("J.7 class with 2 overloads of m") {
    val code = """package a;
class C {
  void m() {}
  void m(int x) {}
}"""
    assertSymbols(code, Set(
      sym("a/C#", isType = true),
      sym("a/C#`<init>`()."),
      sym("a/C#m()."),
      sym("a/C#m(+1)."),
      sym("a/C#m(+1).(x)"),
    ))
  }

  // ── J.8 fields ─────────────────────────────────────────────
  test("J.8 fields") {
    assertSymbols("package a; class C { int x; String y; }", Set(
      sym("a/C#", isType = true),
      sym("a/C#`<init>`()."),
      sym("a/C#x."),
      sym("a/C#y."),
    ))
  }

  // ── J.9 record ─────────────────────────────────────────────
  test("J.9 record") {
    assertSymbols("package a; record R(int x, String y) {}", Set(
      sym("a/R#", isType = true),
      sym("a/R#`<init>`()."),
      sym("a/R#`<init>`().(x)"),
      sym("a/R#`<init>`().(y)"),
      sym("a/R#x()."),
      sym("a/R#y()."),
    ))
  }

  // ── J.10 static nested class ───────────────────────────────
  test("J.10 static nested class") {
    assertSymbols("package a; class Outer { static class Inner {} }", Set(
      sym("a/Outer#", isType = true),
      sym("a/Outer#`<init>`()."),
      sym("a/Outer#Inner#", isType = true),
      sym("a/Outer#Inner#`<init>`()."),
    ))
  }

  // ── J.11 inner (non-static) class with field ────────────────
  test("J.11 inner (non-static) class with field") {
    assertSymbols("package a; class Outer { class Inner { int field; } }", Set(
      sym("a/Outer#", isType = true),
      sym("a/Outer#`<init>`()."),
      sym("a/Outer#Inner#", isType = true),
      sym("a/Outer#Inner#`<init>`()."),
      sym("a/Outer#Inner#field."),
    ))
  }

  // ── J.12 class with type param ─────────────────────────────
  test("J.12 class with type param") {
    assertSymbols("package a; class C<T> {}", Set(
      sym("a/C#", isType = true),
      sym("a/C#[T]"),
      sym("a/C#`<init>`()."),
    ))
  }

  // ── J.13 method with params ────────────────────────────────
  test("J.13 method with params") {
    val code = """package a;
class C {
  void m(int x, String y) {}
}"""
    assertSymbols(code, Set(
      sym("a/C#", isType = true),
      sym("a/C#`<init>`()."),
      sym("a/C#m()."),
      sym("a/C#m().(x)"),
      sym("a/C#m().(y)"),
    ))
  }

  // ── J.14 user-declared constructors ────────────────────────
  test("J.14 user-declared constructors") {
    val code = """package a;
class C {
  C() {}
  C(int x) {}
}"""
    assertSymbols(code, Set(
      sym("a/C#", isType = true),
      sym("a/C#`<init>`()."),
      sym("a/C#`<init>`(+1)."),
      sym("a/C#`<init>`(+1).(x)"),
    ))
  }

  // ── J.15 enum with explicit constructor ────────────────────
  test("J.15 enum with explicit constructor") {
    val code = """package a;
enum E {
  RED(1), GREEN(2);
  int val;
  E(int v) { this.val = v; }
}"""
    assertSymbols(code, Set(
      sym("a/E#", isType = true),
      sym("a/E#RED."),
      sym("a/E#GREEN."),
      sym("a/E#`<init>`()."),
      sym("a/E#`<init>`().(v)"),
      sym("a/E#val."),
    ))
  }

  // ── J.16 record with user method same name as component ────
  test("J.16 record with user method same name as component") {
    val code = """package a;
record R(int x) {
  public int x() { return x + 1; }
}"""
    val actual = extract(code).map(d => (d.symbol, d.isType))
    // record type + canonical ctor + params + user x() method at idx 0 (no synth accessor)
    assert(actual.contains(sym("a/R#", isType = true)))
    assert(actual.contains(sym("a/R#`<init>`().")))
    assert(actual.contains(sym("a/R#`<init>`().(x)")))
    assert(actual.contains(sym("a/R#x()."))) // user method, not synth accessor
    // should NOT contain a second x() (synth accessor)
    assert(!actual.contains((SymbolUtils.methodSymbol(SymbolUtils.typeSymbol("a/", "R"), "x", 1), false)))
  }

  // ── J.17 record with explicit compact ctor ─────────────────
  test("J.17 record with explicit compact ctor") {
    val code = """package a;
record R(int x, int y) {
  R { if (x < 0) throw new IllegalArgumentException(); }
}"""
    assertSymbols(code, Set(
      sym("a/R#", isType = true),
      sym("a/R#`<init>`()."),       // canonical (slot 0)
      sym("a/R#`<init>`().(x)"),
      sym("a/R#`<init>`().(y)"),
      sym("a/R#`<init>`(+1)."),      // user compact ctor (slot 1)
      sym("a/R#x()."),
      sym("a/R#y()."),
    ))
  }
}
