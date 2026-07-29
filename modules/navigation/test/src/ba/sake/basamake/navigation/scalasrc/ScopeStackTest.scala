package ba.sake.basamake.navigation.scalasrc

import munit.FunSuite
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, ScopeStack, OwnerScope, LocalScope, ImportScopeData}

class ScopeStackTest extends FunSuite {

  private def stack(): ScopeStack = new ScopeStack(new SymbolTable)

  test("local shadows owner") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, None))
    val s = new ScopeStack(st)
    // Push owner first, then local (local is later = top of stack = checked first)
    s.push(OwnerScope("pkg/"))
    s.push(LocalScope(collection.mutable.Map("Foo" -> "localFoo#")))
    assertEquals(s.lookup("Foo", isType = true, inCallContext = false), Some("localFoo#"))
  }

  test("owner shadows import") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, None))
    st.add(SymbolDefinition("pkg/other/Bar#", "Bar", isType = true, None))
    val s = new ScopeStack(st)
    s.push(ImportScopeData(Map("Foo" -> "pkg/other/Foo#"), Nil, Set.empty))
    s.push(OwnerScope("pkg/"))

    // Owner-scope Foo resolves, overrides import
    assertEquals(s.lookup("Foo", isType = true, inCallContext = false), Some("pkg/Foo#"))
  }

  test("explicit import shadows wildcard") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/other/Foo#", "Foo", isType = true, None))
    st.add(SymbolDefinition("pkg/wild/Foo#", "Foo", isType = true, None))
    val s = new ScopeStack(st)
    s.push(ImportScopeData(
      explicit = Map("Foo" -> "pkg/other/Foo#"),
      wildcards = List("pkg/wild/"),
      unimports = Set.empty
    ))

    assertEquals(s.lookup("Foo", isType = true, inCallContext = false), Some("pkg/other/Foo#"))
  }

  test("wildcard resolves via symbolTable") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/wild/Foo#", "Foo", isType = true, None))
    val s = new ScopeStack(st)
    s.push(ImportScopeData(
      explicit = Map.empty,
      wildcards = List("pkg/wild/"),
      unimports = Set.empty
    ))

    assertEquals(s.lookup("Foo", isType = true, inCallContext = false), Some("pkg/wild/Foo#"))
  }

  test("import shadows predef") {
    val st = new SymbolTable
    st.add(SymbolDefinition("my/custom/List#", "List", isType = true, None))
    val s = new ScopeStack(st)
    s.push(ImportScopeData(
      explicit = Map("List" -> "my/custom/List#"),
      wildcards = Nil,
      unimports = Set.empty
    ))

    // Should resolve to custom import, not Predef List
    val result = s.lookup("List", isType = true, inCallContext = false)
    assert(result.isDefined)
    assertEquals(result.get, "my/custom/List#")
  }

  test("inner wildcard shadows outer wildcard") {
    val st = new SymbolTable
    st.add(SymbolDefinition("inner/Foo#", "Foo", isType = true, None))
    st.add(SymbolDefinition("outer/Foo#", "Foo", isType = true, None))
    val s = new ScopeStack(st)
    s.push(ImportScopeData(
      explicit = Map.empty,
      wildcards = List("outer/"),
      unimports = Set.empty
    ))
    s.push(ImportScopeData(
      explicit = Map.empty,
      wildcards = List("inner/"),
      unimports = Set.empty
    ))

    assertEquals(s.lookup("Foo", isType = true, inCallContext = false), Some("inner/Foo#"))
  }

  test("unimport excludes from wildcard resolution") {
    val st = new SymbolTable
    st.add(SymbolDefinition("pkg/wild/Foo#", "Foo", isType = true, None))
    st.add(SymbolDefinition("pkg/wild/Bar#", "Bar", isType = true, None))
    val s = new ScopeStack(st)
    s.push(ImportScopeData(
      explicit = Map.empty,
      wildcards = List("pkg/wild/"),
      unimports = Set("Foo")
    ))

    // Foo is unimported — should not be found
    assertEquals(s.lookup("Foo", isType = true, inCallContext = false), None)
    // Bar is not unimported — should be found
    assertEquals(s.lookup("Bar", isType = true, inCallContext = false), Some("pkg/wild/Bar#"))
  }

  test("local shadows predef") {
    val s = new ScopeStack(new SymbolTable)
    s.push(LocalScope(collection.mutable.Map("println" -> "localPrintln")))
    val result = s.lookup("println", isType = false, inCallContext = true)
    assertEquals(result, Some("localPrintln"))
  }

  test("lookup returns None when nothing in scope") {
    val s = new ScopeStack(new SymbolTable)
    assertEquals(s.lookup("UnknownThing", isType = true, inCallContext = false), None)
    assertEquals(s.lookup("unknownMethod", isType = false, inCallContext = true), None)
  }
}
