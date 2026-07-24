package ba.sake.basamake.navigation

import munit.FunSuite

class SymbolUtilsTest extends FunSuite {

  // ── escapedName / isJavaIdentifier ──────────────────────────

  test("valid Java identifiers pass through without backticks") {
    assertEquals(SymbolUtils.escapedName("Foo").value, "Foo")
    assertEquals(SymbolUtils.escapedName("bar").value, "bar")
    assertEquals(SymbolUtils.escapedName("x1").value, "x1")
    assertEquals(SymbolUtils.escapedName("$outer").value, "$outer")
    assertEquals(SymbolUtils.escapedName("_foo_").value, "_foo_")
  }

  test("non-Java-identifier names are backtick-wrapped") {
    assertEquals(SymbolUtils.escapedName("<init>").value, "`<init>`")
    assertEquals(SymbolUtils.escapedName("::").value, "`::`")
    assertEquals(SymbolUtils.escapedName("+").value, "`+`")
    assertEquals(SymbolUtils.escapedName("===").value, "`===`")
  }

  test("escapedName does not double-wrap already backticked names") {
    assertEquals(SymbolUtils.escapedName("`<init>`").value, "`<init>`")
    assertEquals(SymbolUtils.escapedName("`::`").value, "`::`")
  }

  test("isJavaIdentifier rejects empty string, operators, and digit-start names") {
    assert(!SymbolUtils.isJavaIdentifier(""))
    assert(!SymbolUtils.isJavaIdentifier("<init>"))  // contains < >
    assert(!SymbolUtils.isJavaIdentifier("::"))       // contains :
    assert(!SymbolUtils.isJavaIdentifier("1foo"))     // starts with digit
  }

  test("isJavaIdentifier accepts keyword names (not escaped by SemanticDB)") {
    // Keywords are made of identifier characters; SemanticDB does NOT
    // backtick-escape them (e.g. scala/package. is a valid symbol)
    assert(SymbolUtils.isJavaIdentifier("package"))
    assert(SymbolUtils.isJavaIdentifier("class"))
    assert(SymbolUtils.isJavaIdentifier("null"))
  }

  test("isJavaIdentifier accepts normal names") {
    assert(SymbolUtils.isJavaIdentifier("Foo"))
    assert(SymbolUtils.isJavaIdentifier("bar"))
    assert(SymbolUtils.isJavaIdentifier("x1"))
    assert(SymbolUtils.isJavaIdentifier("X"))
    assert(SymbolUtils.isJavaIdentifier("_underscore"))
    assert(SymbolUtils.isJavaIdentifier("$dollar"))
  }

  // ── packageOwner ────────────────────────────────────────────

  test("packageOwner with segments") {
    assertEquals(SymbolUtils.packageOwner(List("com", "example")).value, "com/example/")
    assertEquals(SymbolUtils.packageOwner(List("scala")).value, "scala/")
  }

  test("packageOwner with empty list returns empty package marker") {
    assertEquals(SymbolUtils.packageOwner(Nil).value, "_empty_/")
  }

  // ── typeSymbol ──────────────────────────────────────────────

  test("typeSymbol appends # descriptor") {
    assertEquals(
      SymbolUtils.typeSymbol(Symbol("com/example/"), "Outer").value,
      "com/example/Outer#"
    )
  }

  test("typeSymbol with nested owner") {
    assertEquals(
      SymbolUtils.typeSymbol(Symbol("com/example/Outer#"), "Inner").value,
      "com/example/Outer#Inner#"
    )
  }

  test("typeSymbol wraps operator names in backticks") {
    assertEquals(
      SymbolUtils.typeSymbol(Symbol("scala/collection/immutable/"), "::").value,
      "scala/collection/immutable/`::`#"
    )
  }

  // ── termSymbol ──────────────────────────────────────────────

  test("termSymbol appends . descriptor") {
    assertEquals(
      SymbolUtils.termSymbol(Symbol("com/example/"), "Api").value,
      "com/example/Api."
    )
  }

  test("termSymbol with nested class owner") {
    assertEquals(
      SymbolUtils.termSymbol(Symbol("com/example/Outer#"), "field").value,
      "com/example/Outer#field."
    )
  }

  test("termSymbol wraps operator names in backticks") {
    assertEquals(
      SymbolUtils.termSymbol(Symbol("scala/package."), "::").value,
      "scala/package.`::`."
    )
  }

  // ── methodSymbol ────────────────────────────────────────────

  test("methodSymbol with overloadIndex 0 uses () disambiguator") {
    assertEquals(
      SymbolUtils.methodSymbol(Symbol("com/example/Outer#"), "run", overloadIndex = 0).value,
      "com/example/Outer#run()."
    )
  }

  test("methodSymbol with overloadIndex 1 uses (+1) disambiguator") {
    assertEquals(
      SymbolUtils.methodSymbol(Symbol("com/example/Outer#"), "run", overloadIndex = 1).value,
      "com/example/Outer#run(+1)."
    )
  }

  test("methodSymbol with overloadIndex 2") {
    assertEquals(
      SymbolUtils.methodSymbol(Symbol("com/example/Outer#"), "run", overloadIndex = 2).value,
      "com/example/Outer#run(+2)."
    )
  }

  test("methodSymbol wraps operator names") {
    assertEquals(
      SymbolUtils.methodSymbol(Symbol("scala/Any#"), "==", overloadIndex = 0).value,
      "scala/Any#`==`()."
    )
  }

  // ── constructorSymbol ───────────────────────────────────────

  test("constructorSymbol first overload") {
    assertEquals(
      SymbolUtils.constructorSymbol(Symbol("com/example/Outer#"), overloadIndex = 0).value,
      "com/example/Outer#`<init>`()."
    )
  }

  test("constructorSymbol second overload") {
    assertEquals(
      SymbolUtils.constructorSymbol(Symbol("com/example/Outer#"), overloadIndex = 1).value,
      "com/example/Outer#`<init>`(+1)."
    )
  }

  // ── Combined examples from plan ─────────────────────────────

  test("full chain: package → type → method") {
    val pkg = SymbolUtils.packageOwner(List("com", "example"))
    val outer = SymbolUtils.typeSymbol(pkg, "Outer")
    assertEquals(outer.value, "com/example/Outer#")
    val run0 = SymbolUtils.methodSymbol(outer, "run", 0)
    assertEquals(run0.value, "com/example/Outer#run().")
    val run1 = SymbolUtils.methodSymbol(outer, "run", 1)
    assertEquals(run1.value, "com/example/Outer#run(+1).")
  }

  test("full chain: empty package → type → term → method") {
    val pkg = SymbolUtils.packageOwner(Nil)
    assertEquals(pkg.value, "_empty_/")
    val foo = SymbolUtils.typeSymbol(pkg, "Foo")
    assertEquals(foo.value, "_empty_/Foo#")
    val run = SymbolUtils.methodSymbol(foo, "run", 0)
    assertEquals(run.value, "_empty_/Foo#run().")
  }

  test("full chain: package object term → operator method") {
    val scalaPkg = SymbolUtils.termSymbol(Symbol("scala/"), "package").value
    assertEquals(scalaPkg, "scala/package.")
    val seq = SymbolUtils.termSymbol(Symbol(scalaPkg), "??").value
    assertEquals(seq, "scala/package.`??`.")
  }
}
