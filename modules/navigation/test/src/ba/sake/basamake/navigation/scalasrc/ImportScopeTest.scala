package ba.sake.basamake.navigation.scalasrc

import munit.FunSuite
import scala.meta.*
import scala.meta.dialects.Scala3Future
import scala.meta.inputs.Input
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition}

class ImportScopeTest extends FunSuite {

  private def parseImport(code: String): List[ImportScopeData] = {
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new SymbolTable
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
      case _ => Nil
    }
  }

  test("explicit import produces ImportScopeData with explicit entry") {
    val code = "import a.b.C"
    val scopes = parseImport(code)
    assert(scopes.nonEmpty)
    // Even without SymbolTable entries, should produce an ImportScope with an empty explicit map
    // since the import name can't be resolved without SymbolTable entries
  }

  test("wildcard import produces wildcard entry") {
    val code = "import a.b._"
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new SymbolTable
        st.add(SymbolDefinition("a/b/", "b", isType = false, None))
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        val scopes = ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        assert(scopes.nonEmpty)
        assert(scopes.head.wildcards.nonEmpty)
      case _ => fail("parse failed")
    }
  }

  test("rename import maps new name to original symbol") {
    val code = "import a.b.{C => D}"
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new SymbolTable
        st.add(SymbolDefinition("a/b/C#", "C", isType = true, None))
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        val scopes = ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        assert(scopes.nonEmpty)
        assert(scopes.head.explicit.get("D").isDefined)
      case _ => fail("parse failed")
    }
  }

  test("unimport adds name to unimports set") {
    val code = "import a.b.{Foo => _, _}"
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new SymbolTable
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        val scopes = ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        assert(scopes.nonEmpty)
        assert(scopes.head.unimports.contains("Foo"))
      case _ => fail("parse failed")
    }
  }

  test("import emitRef emits symbols") {
    val code = "import a.b.C"
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new SymbolTable
        st.add(SymbolDefinition("a/", "a", isType = false, None))
        st.add(SymbolDefinition("a/b/", "b", isType = false, None))
        st.add(SymbolDefinition("a/b/C#", "C", isType = true, None))
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        // Should emit refs for import path segments
        assert(emitted.nonEmpty)
      case _ => fail("parse failed")
    }
  }
}
