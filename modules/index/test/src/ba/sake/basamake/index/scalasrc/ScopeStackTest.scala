package ba.sake.basamake.index.scalasrc

import munit.FunSuite
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.index.{SymbolTable, InMemorySymbolTable, SymbolDefinition, ScopeStack, OwnerScope, LocalScope, ImportScopeData}

class ScopeStackTest extends FunSuite {

  private def stack(): ScopeStack = new ScopeStack(new InMemorySymbolTable)

  test("local shadows owner") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val s = new ScopeStack(st)
    // Push owner first, then local (local is later = top of stack = checked first)
    s.push(OwnerScope("pkg/"))
    s.push(LocalScope(collection.mutable.Map("Foo" -> "localFoo#")))
    assertEquals(s.lookup("Foo", isType = true, inCallContext = false), Some("localFoo#"))
  }

  test("owner shadows import") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/other/Bar#", "Bar", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val s = new ScopeStack(st)
    s.push(ImportScopeData(Map("Foo" -> "pkg/other/Foo#"), Nil, Set.empty))
    s.push(OwnerScope("pkg/"))

    // Owner-scope Foo resolves, overrides import
    assertEquals(s.lookup("Foo", isType = true, inCallContext = false), Some("pkg/Foo#"))
  }

  test("explicit import shadows wildcard") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/other/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/wild/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val s = new ScopeStack(st)
    s.push(ImportScopeData(
      explicit = Map("Foo" -> "pkg/other/Foo#"),
      wildcards = List("pkg/wild/"),
      unimports = Set.empty
    ))

    assertEquals(s.lookup("Foo", isType = true, inCallContext = false), Some("pkg/other/Foo#"))
  }

  test("wildcard resolves via symbolTable") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/wild/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val s = new ScopeStack(st)
    s.push(ImportScopeData(
      explicit = Map.empty,
      wildcards = List("pkg/wild/"),
      unimports = Set.empty
    ))

    assertEquals(s.lookup("Foo", isType = true, inCallContext = false), Some("pkg/wild/Foo#"))
  }

  test("import shadows predef") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("my/custom/List#", "List", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
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
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("inner/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("outer/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
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
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/wild/Foo#", "Foo", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    st.add(SymbolDefinition("pkg/wild/Bar#", "Bar", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
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
    val s = new ScopeStack(new InMemorySymbolTable)
    s.push(LocalScope(collection.mutable.Map("println" -> "localPrintln")))
    val result = s.lookup("println", isType = false, inCallContext = true)
    assertEquals(result, Some("localPrintln"))
  }

  test("lookup returns None when nothing in scope") {
    val s = new ScopeStack(new InMemorySymbolTable)
    assertEquals(s.lookup("UnknownThing", isType = true, inCallContext = false), None)
    assertEquals(s.lookup("unknownMethod", isType = false, inCallContext = true), None)
  }

  // ── overload probing beyond the old fixed 0..8 cap ────────────

  test("owner-scope method resolution works when the only overload is beyond index 8") {
    val st = new InMemorySymbolTable
    // sparse table: only overload 9 exists (0..8 missing — e.g. partial /
    // best-effort indexing). The old fixed 0..8 scan missed this entirely.
    st.add(SymbolDefinition("pkg/Util#m(+9).", "m", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val s = new ScopeStack(st)
    s.push(OwnerScope("pkg/Util#"))

    assertEquals(s.lookup("m", isType = false, inCallContext = true), Some("pkg/Util#m(+9)."))
  }

  test("contiguous overloads 0..12 resolve to the first (lowest) index") {
    val st = new InMemorySymbolTable
    (0 to 12).foreach { i =>
      val dis = if (i == 0) "" else s"+$i"
      st.add(SymbolDefinition(s"pkg/Util#m($dis).", "m", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    }
    val s = new ScopeStack(st)
    s.push(OwnerScope("pkg/Util#"))

    assertEquals(s.lookup("m", isType = false, inCallContext = true), Some("pkg/Util#m()."))
  }

  test("missing method terminates the scan (bounded, no runaway)") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/Util#other().", "other", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val s = new ScopeStack(st)
    s.push(OwnerScope("pkg/Util#"))

    assertEquals(s.lookup("nonexistent", isType = false, inCallContext = true), None)
  }

  test("method import scope: call binds to the method overload, not a term symbol") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/Util#foo(+9).", "foo", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val s = new ScopeStack(st)
    // Java `import static pkg.Util.foo;` — name → owner type symbol
    s.push(ImportScopeData(
      explicit = Map("foo" -> "pkg/Util#foo."),
      wildcards = Nil,
      unimports = Set.empty,
      methodImports = Map("foo" -> "pkg/Util#")
    ))

    // a call binds to the method overload even though the term symbol is absent
    assertEquals(s.lookup("foo", isType = false, inCallContext = true), Some("pkg/Util#foo(+9)."))
    // non-call usage does NOT bind to the absent term symbol (table-verified)
    assertEquals(s.lookup("foo", isType = false, inCallContext = false), None)
  }

  test("method import scope: non-call usage binds the term symbol when present") {
    val st = new InMemorySymbolTable
    st.add(SymbolDefinition("pkg/Util#MAX.", "MAX", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
    val s = new ScopeStack(st)
    s.push(ImportScopeData(
      explicit = Map("MAX" -> "pkg/Util#MAX."),
      wildcards = Nil,
      unimports = Set.empty,
      methodImports = Map("MAX" -> "pkg/Util#")
    ))

    assertEquals(s.lookup("MAX", isType = false, inCallContext = false), Some("pkg/Util#MAX."))
  }
}
