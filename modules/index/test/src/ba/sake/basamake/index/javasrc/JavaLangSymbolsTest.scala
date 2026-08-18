package ba.sake.basamake.index.javasrc

import munit.FunSuite

class JavaLangSymbolsTest extends FunSuite {

  test("lookup String as type") {
    assertEquals(JavaLangSymbols.lookup("String", isType = true), Some("java/lang/String#"))
  }

  test("lookup String as term returns None") {
    assertEquals(JavaLangSymbols.lookup("String", isType = false), None)
  }

  test("lookup System as type") {
    assertEquals(JavaLangSymbols.lookup("System", isType = true), Some("java/lang/System#"))
  }

  test("lookup System as term returns None (System is class, not object)") {
    assertEquals(JavaLangSymbols.lookup("System", isType = false), None)
  }

  test("rawLookup Throwable") {
    assertEquals(JavaLangSymbols.rawLookup("Throwable"), Some("java/lang/Throwable#"))
  }

  test("lookup unknown name returns None") {
    assertEquals(JavaLangSymbols.lookup("Unknown", isType = true), None)
    assertEquals(JavaLangSymbols.lookup("Unknown", isType = false), None)
  }

  test("rawLookup unknown name returns None") {
    assertEquals(JavaLangSymbols.rawLookup("Unknown"), None)
  }

  test("lookup all java.util types") {
    assertEquals(JavaLangSymbols.lookup("ArrayList", isType = true), Some("java/util/ArrayList#"))
    assertEquals(JavaLangSymbols.lookup("HashMap", isType = true), Some("java/util/HashMap#"))
    assertEquals(JavaLangSymbols.lookup("Optional", isType = true), Some("java/util/Optional#"))
  }
}
