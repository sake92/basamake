package ba.sake.basamake.navigation

import munit.FunSuite

class SemanticdbSymbolTest extends FunSuite {

  // ── escapedName / isJavaIdentifier ──────────────────────────

  test("valid Java identifiers pass through without backticks") {
    assertEquals(SemanticdbSymbol.escapedName("Foo"), "Foo")
    assertEquals(SemanticdbSymbol.escapedName("bar"), "bar")
    assertEquals(SemanticdbSymbol.escapedName("x1"), "x1")
    assertEquals(SemanticdbSymbol.escapedName("$outer"), "$outer")
    assertEquals(SemanticdbSymbol.escapedName("_foo_"), "_foo_")
  }

  test("non-Java-identifier names are backtick-wrapped") {
    assertEquals(SemanticdbSymbol.escapedName("<init>"), "`<init>`")
    assertEquals(SemanticdbSymbol.escapedName("::"), "`::`")
    assertEquals(SemanticdbSymbol.escapedName("+"), "`+`")
    assertEquals(SemanticdbSymbol.escapedName("==="), "`===`")
  }

  test("escapedName does not double-wrap already backticked names") {
    assertEquals(SemanticdbSymbol.escapedName("`<init>`"), "`<init>`")
    assertEquals(SemanticdbSymbol.escapedName("`::`"), "`::`")
  }

  test("isJavaIdentifier rejects empty string, operators, and digit-start names") {
    assert(!SemanticdbSymbol.isJavaIdentifier(""))
    assert(!SemanticdbSymbol.isJavaIdentifier("<init>"))  // contains < >
    assert(!SemanticdbSymbol.isJavaIdentifier("::"))       // contains :
    assert(!SemanticdbSymbol.isJavaIdentifier("1foo"))     // starts with digit
  }

  test("isJavaIdentifier accepts keyword names (not escaped by SemanticDB)") {
    // Keywords are made of identifier characters; SemanticDB does NOT
    // backtick-escape them (e.g. scala/package. is a valid symbol)
    assert(SemanticdbSymbol.isJavaIdentifier("package"))
    assert(SemanticdbSymbol.isJavaIdentifier("class"))
    assert(SemanticdbSymbol.isJavaIdentifier("null"))
  }

  test("isJavaIdentifier accepts normal names") {
    assert(SemanticdbSymbol.isJavaIdentifier("Foo"))
    assert(SemanticdbSymbol.isJavaIdentifier("bar"))
    assert(SemanticdbSymbol.isJavaIdentifier("x1"))
    assert(SemanticdbSymbol.isJavaIdentifier("X"))
    assert(SemanticdbSymbol.isJavaIdentifier("_underscore"))
    assert(SemanticdbSymbol.isJavaIdentifier("$dollar"))
  }

  // ── packageOwner ────────────────────────────────────────────

  test("packageOwner with segments") {
    assertEquals(SemanticdbSymbol.packageOwner(List("com", "example")), "com/example/")
    assertEquals(SemanticdbSymbol.packageOwner(List("scala")), "scala/")
  }

  test("packageOwner with empty list returns empty package marker") {
    assertEquals(SemanticdbSymbol.packageOwner(Nil), "_empty_/")
  }

  // ── typeSymbol ──────────────────────────────────────────────

  test("typeSymbol appends # descriptor") {
    assertEquals(
      SemanticdbSymbol.typeSymbol("com/example/", "Outer"),
      "com/example/Outer#"
    )
  }

  test("typeSymbol with nested owner") {
    assertEquals(
      SemanticdbSymbol.typeSymbol("com/example/Outer#", "Inner"),
      "com/example/Outer#Inner#"
    )
  }

  test("typeSymbol wraps operator names in backticks") {
    assertEquals(
      SemanticdbSymbol.typeSymbol("scala/collection/immutable/", "::"),
      "scala/collection/immutable/`::`#"
    )
  }

  // ── termSymbol ──────────────────────────────────────────────

  test("termSymbol appends . descriptor") {
    assertEquals(
      SemanticdbSymbol.termSymbol("com/example/", "Api"),
      "com/example/Api."
    )
  }

  test("termSymbol with nested class owner") {
    assertEquals(
      SemanticdbSymbol.termSymbol("com/example/Outer#", "field"),
      "com/example/Outer#field."
    )
  }

  test("termSymbol wraps operator names in backticks") {
    assertEquals(
      SemanticdbSymbol.termSymbol("scala/package.", "::"),
      "scala/package.`::`."
    )
  }

  // ── methodSymbol ────────────────────────────────────────────

  test("methodSymbol with overloadIndex 0 uses () disambiguator") {
    assertEquals(
      SemanticdbSymbol.methodSymbol("com/example/Outer#", "run", overloadIndex = 0),
      "com/example/Outer#run()."
    )
  }

  test("methodSymbol with overloadIndex 1 uses (+1) disambiguator") {
    assertEquals(
      SemanticdbSymbol.methodSymbol("com/example/Outer#", "run", overloadIndex = 1),
      "com/example/Outer#run(+1)."
    )
  }

  test("methodSymbol with overloadIndex 2") {
    assertEquals(
      SemanticdbSymbol.methodSymbol("com/example/Outer#", "run", overloadIndex = 2),
      "com/example/Outer#run(+2)."
    )
  }

  test("methodSymbol wraps operator names") {
    assertEquals(
      SemanticdbSymbol.methodSymbol("scala/Any#", "==", overloadIndex = 0),
      "scala/Any#`==`()."
    )
  }

  // ── constructorSymbol ───────────────────────────────────────

  test("constructorSymbol first overload") {
    assertEquals(
      SemanticdbSymbol.constructorSymbol("com/example/Outer#", overloadIndex = 0),
      "com/example/Outer#`<init>`()."
    )
  }

  test("constructorSymbol second overload") {
    assertEquals(
      SemanticdbSymbol.constructorSymbol("com/example/Outer#", overloadIndex = 1),
      "com/example/Outer#`<init>`(+1)."
    )
  }

  // ── Combined examples from plan ─────────────────────────────

  test("full chain: package → type → method") {
    val pkg = SemanticdbSymbol.packageOwner(List("com", "example"))
    val outer = SemanticdbSymbol.typeSymbol(pkg, "Outer")
    assertEquals(outer, "com/example/Outer#")
    val run0 = SemanticdbSymbol.methodSymbol(outer, "run", 0)
    assertEquals(run0, "com/example/Outer#run().")
    val run1 = SemanticdbSymbol.methodSymbol(outer, "run", 1)
    assertEquals(run1, "com/example/Outer#run(+1).")
  }

  test("full chain: empty package → type → term → method") {
    val pkg = SemanticdbSymbol.packageOwner(Nil)
    assertEquals(pkg, "_empty_/")
    val foo = SemanticdbSymbol.typeSymbol(pkg, "Foo")
    assertEquals(foo, "_empty_/Foo#")
    val run = SemanticdbSymbol.methodSymbol(foo, "run", 0)
    assertEquals(run, "_empty_/Foo#run().")
  }

  test("full chain: package object term → operator method") {
    val scalaPkg = SemanticdbSymbol.termSymbol("scala/", "package")
    assertEquals(scalaPkg, "scala/package.")
    val seq = SemanticdbSymbol.termSymbol(scalaPkg, "??")
    assertEquals(seq, "scala/package.`??`.")
  }
}
