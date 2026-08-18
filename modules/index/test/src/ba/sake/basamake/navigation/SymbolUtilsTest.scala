package ba.sake.basamake.index

import munit.FunSuite

class SymbolUtilsTest extends FunSuite {

  // ── escapedName / isJavaIdentifier ──────────────────────────

  test("valid Java identifiers pass through without backticks") {
    assertEquals(SymbolUtils.escapedName("Foo"), "Foo")
    assertEquals(SymbolUtils.escapedName("bar"), "bar")
    assertEquals(SymbolUtils.escapedName("x1"), "x1")
    assertEquals(SymbolUtils.escapedName("$outer"), "$outer")
    assertEquals(SymbolUtils.escapedName("_foo_"), "_foo_")
  }

  test("non-Java-identifier names are backtick-wrapped") {
    assertEquals(SymbolUtils.escapedName("<init>"), "`<init>`")
    assertEquals(SymbolUtils.escapedName("::"), "`::`")
    assertEquals(SymbolUtils.escapedName("+"), "`+`")
    assertEquals(SymbolUtils.escapedName("==="), "`===`")
  }

  test("escapedName does not double-wrap already backticked names") {
    assertEquals(SymbolUtils.escapedName("`<init>`"), "`<init>`")
    assertEquals(SymbolUtils.escapedName("`::`"), "`::`")
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
    assertEquals(SymbolUtils.packageOwner(List("com", "example")), "com/example/")
    assertEquals(SymbolUtils.packageOwner(List("scala")), "scala/")
  }

  test("packageOwner with empty list returns empty package marker") {
    assertEquals(SymbolUtils.packageOwner(Nil), "_empty_/")
  }

  // ── typeSymbol ──────────────────────────────────────────────

  test("typeSymbol appends # descriptor") {
    assertEquals(
      SymbolUtils.typeSymbol("com/example/", "Outer"),
      "com/example/Outer#"
    )
  }

  test("typeSymbol with nested owner") {
    assertEquals(
      SymbolUtils.typeSymbol("com/example/Outer#", "Inner"),
      "com/example/Outer#Inner#"
    )
  }

  test("typeSymbol wraps operator names in backticks") {
    assertEquals(
      SymbolUtils.typeSymbol("scala/collection/immutable/", "::"),
      "scala/collection/immutable/`::`#"
    )
  }

  // ── termSymbol ──────────────────────────────────────────────

  test("termSymbol appends . descriptor") {
    assertEquals(
      SymbolUtils.termSymbol("com/example/", "Api"),
      "com/example/Api."
    )
  }

  test("termSymbol with nested class owner") {
    assertEquals(
      SymbolUtils.termSymbol("com/example/Outer#", "field"),
      "com/example/Outer#field."
    )
  }

  test("termSymbol wraps operator names in backticks") {
    assertEquals(
      SymbolUtils.termSymbol("scala/package.", "::"),
      "scala/package.`::`."
    )
  }

  // ── methodSymbol ────────────────────────────────────────────

  test("methodSymbol with overloadIndex 0 uses () disambiguator") {
    assertEquals(
      SymbolUtils.methodSymbol("com/example/Outer#", "run", overloadIndex = 0),
      "com/example/Outer#run()."
    )
  }

  test("methodSymbol with overloadIndex 1 uses (+1) disambiguator") {
    assertEquals(
      SymbolUtils.methodSymbol("com/example/Outer#", "run", overloadIndex = 1),
      "com/example/Outer#run(+1)."
    )
  }

  test("methodSymbol with overloadIndex 2") {
    assertEquals(
      SymbolUtils.methodSymbol("com/example/Outer#", "run", overloadIndex = 2),
      "com/example/Outer#run(+2)."
    )
  }

  test("methodSymbol wraps operator names") {
    assertEquals(
      SymbolUtils.methodSymbol("scala/Any#", "==", overloadIndex = 0),
      "scala/Any#`==`()."
    )
  }

  // ── constructorSymbol ───────────────────────────────────────

  test("constructorSymbol first overload") {
    assertEquals(
      SymbolUtils.constructorSymbol("com/example/Outer#", overloadIndex = 0),
      "com/example/Outer#`<init>`()."
    )
  }

  test("constructorSymbol second overload") {
    assertEquals(
      SymbolUtils.constructorSymbol("com/example/Outer#", overloadIndex = 1),
      "com/example/Outer#`<init>`(+1)."
    )
  }

  // ── Combined examples from plan ─────────────────────────────

  test("full chain: package → type → method") {
    val pkg = SymbolUtils.packageOwner(List("com", "example"))
    val outer = SymbolUtils.typeSymbol(pkg, "Outer")
    assertEquals(outer, "com/example/Outer#")
    val run0 = SymbolUtils.methodSymbol(outer, "run", 0)
    assertEquals(run0, "com/example/Outer#run().")
    val run1 = SymbolUtils.methodSymbol(outer, "run", 1)
    assertEquals(run1, "com/example/Outer#run(+1).")
  }

  test("full chain: empty package → type → term → method") {
    val pkg = SymbolUtils.packageOwner(Nil)
    assertEquals(pkg, "_empty_/")
    val foo = SymbolUtils.typeSymbol(pkg, "Foo")
    assertEquals(foo, "_empty_/Foo#")
    val run = SymbolUtils.methodSymbol(foo, "run", 0)
    assertEquals(run, "_empty_/Foo#run().")
  }

  test("full chain: package object term → operator method") {
    val scalaPkg = SymbolUtils.termSymbol("scala/", "package")
    assertEquals(scalaPkg, "scala/package.")
    val seq = SymbolUtils.termSymbol(scalaPkg, "??")
    assertEquals(seq, "scala/package.`??`.")
  }

  // ── shortNameOf (display-name decoder) ───────────────────────

  test("shortNameOf: methods and overloads") {
    assertEquals(SymbolUtils.shortNameOf("com/example/Outer#run()."), "run")
    assertEquals(SymbolUtils.shortNameOf("com/example/Outer#run(+1)."), "run")
    assertEquals(SymbolUtils.shortNameOf("com/example/Outer#run(+2)."), "run")
    assertEquals(SymbolUtils.shortNameOf("java/lang/String#substring(II)."), "substring")
  }

  test("shortNameOf: nested member keeps the MEMBER name, not the owner") {
    // regression: `takeWhile` on the first `.` used to return the owner's name
    assertEquals(SymbolUtils.shortNameOf("basamake/sbt/sbtutils.getMsg()."), "getMsg")
    assertEquals(SymbolUtils.shortNameOf("basamake/sbt/sbtutils.y."), "y")
    assertEquals(SymbolUtils.shortNameOf("basamake/sbt/Main$package.hello()."), "hello")
    assertEquals(SymbolUtils.shortNameOf("_empty_/utils.getMsg()."), "getMsg")
  }

  test("shortNameOf: types and terms") {
    assertEquals(SymbolUtils.shortNameOf("com/example/Outer#"), "Outer")
    assertEquals(SymbolUtils.shortNameOf("scala/collection/immutable/List#"), "List")
    assertEquals(SymbolUtils.shortNameOf("com/example/Outer#field."), "field")
    assertEquals(SymbolUtils.shortNameOf("_empty_/bla$package."), "bla$package")
    assertEquals(SymbolUtils.shortNameOf("_empty_/utils."), "utils")
  }

  test("shortNameOf: params, ctors, type params") {
    assertEquals(SymbolUtils.shortNameOf("com/example/Outer#run().(x)"), "x")
    assertEquals(SymbolUtils.shortNameOf("com/example/Outer#`<init>`()."), "`<init>`")
    assertEquals(SymbolUtils.shortNameOf("com/example/Foo#bar[T]()."), "bar")
    assertEquals(SymbolUtils.shortNameOf("com/example/Outer#copy$default$1()."), "copy$default$1")
  }

  test("shortNameOf: short / best-effort symbols without owner prefix") {
    assertEquals(SymbolUtils.shortNameOf("utils."), "utils")
    assertEquals(SymbolUtils.shortNameOf("Unit#"), "Unit")
    assertEquals(SymbolUtils.shortNameOf("utils.getMsg()."), "getMsg")
  }

  test("packageOf: backticked names containing '/' (operator symbols)") {
    assertEquals(SymbolUtils.packageOf("scala/Byte#`/`()."), Some("scala"))
    assertEquals(SymbolUtils.packageOf("scala/Int#`/`()."), Some("scala"))
    assertEquals(SymbolUtils.packageOf("scala/`scala`."), Some("scala"))
    assertEquals(SymbolUtils.packageOf("scala/Byte#`>=`()."), Some("scala"))
  }

  test("packageOf: unchanged for normal symbols") {
    assertEquals(SymbolUtils.packageOf("java/lang/String#"), Some("java.lang"))
    assertEquals(SymbolUtils.packageOf("com/example/Foo#bar()."), Some("com.example"))
    assertEquals(SymbolUtils.packageOf("org/apache/commons/net/FTPClient#"), Some("org.apache.commons.net"))
    assertEquals(SymbolUtils.packageOf("Foo#"), None)
    assertEquals(SymbolUtils.packageOf("_empty_/Foo#"), None)
  }

  test("shortNameOf: backticked operator names") {
    assertEquals(SymbolUtils.shortNameOf("scala/Byte#`/`()."), "`/`")
    assertEquals(SymbolUtils.shortNameOf("scala/`scala`."), "`scala`")
    assertEquals(SymbolUtils.shortNameOf("com/example/Outer#`/`()."), "`/`")
  }
}
