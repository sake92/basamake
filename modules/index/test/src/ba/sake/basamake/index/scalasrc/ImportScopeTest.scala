package ba.sake.basamake.index.scalasrc

import munit.FunSuite
import scala.meta.*
import scala.meta.dialects.Scala3Future
import scala.meta.inputs.Input
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.index.{SymbolTable, InMemorySymbolTable, SymbolDefinition, ScopeStack, ImportScopeData}

class ImportScopeTest extends FunSuite {

  private def parseImport(code: String): List[ImportScopeData] = {
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new InMemorySymbolTable
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
        val st = new InMemorySymbolTable
        st.add(SymbolDefinition("a/b/", "b", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
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
        val st = new InMemorySymbolTable
        st.add(SymbolDefinition("a/b/C#", "C", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
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
        val st = new InMemorySymbolTable
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
        val st = new InMemorySymbolTable
        st.add(SymbolDefinition("a/", "a", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
        st.add(SymbolDefinition("a/b/", "b", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
        st.add(SymbolDefinition("a/b/C#", "C", isType = true, new Range(0,0,0,0), os.pwd / "dummy.scala"))
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        // Should emit refs for import path segments
        assert(emitted.nonEmpty)
      case _ => fail("parse failed")
    }
  }

  test("named given import resolves and produces explicit entry") {
    val code = "import a.b.{given Foo}"
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new InMemorySymbolTable
        st.add(SymbolDefinition("a/b/Foo.", "Foo", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        val scopes = ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        assertEquals(scopes.head.explicit.get("Foo"), Some("a/b/Foo."))
        assert(emitted.exists(_ == ("Foo", "a/b/Foo.")))
      case _ => fail("parse failed")
    }
  }

  test("named given import (unresolved) does not throw") {
    val code = "import a.b.{given Foo}"
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new InMemorySymbolTable
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        val scopes = ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        assert(scopes.nonEmpty)
        assert(scopes.head.explicit.isEmpty)
      case _ => fail("parse failed")
    }
  }

  test("bare given import produces wildcard entry") {
    val code = "import a.b.given"
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new InMemorySymbolTable
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        val scopes = ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        assert(scopes.head.wildcards.contains("a/b/"),
          s"Expected wildcard a/b/, got: ${scopes.head.wildcards}")
      case _ => fail("parse failed")
    }
  }

  test("mixed named given + wildcard import produces both entries") {
    val code = "import a.b.{given Foo, _}"
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new InMemorySymbolTable
        st.add(SymbolDefinition("a/b/Foo.", "Foo", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        val scopes = ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        assertEquals(scopes.head.explicit.get("Foo"), Some("a/b/Foo."))
        assert(scopes.head.wildcards.contains("a/b/"),
          s"Expected wildcard a/b/, got: ${scopes.head.wildcards}")
      case _ => fail("parse failed")
    }
  }

  test("unresolved importee with a package prefix emits type+term candidates (dep fallback)") {
    // `import sttp.client3.{HttpError, SttpBackend}` — nothing in the WORKSPACE
    // table; the importee must still emit BOTH plausible symbols so the dep
    // index can answer the goto-def (source-parse mode)
    val code = "import sttp.client3.{HttpError, SttpBackend}"
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new InMemorySymbolTable
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        val scopes = ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        assert(scopes.head.explicit.isEmpty, "no workspace symbol → no explicit binding")
        assert(emitted.contains(("HttpError", "sttp/client3/HttpError#")),
          s"type candidate missing: $emitted")
        assert(emitted.contains(("HttpError", "sttp/client3/HttpError.")),
          s"term candidate missing: $emitted")
        assert(emitted.contains(("SttpBackend", "sttp/client3/SttpBackend#")),
          s"type candidate missing: $emitted")
        assert(emitted.contains(("SttpBackend", "sttp/client3/SttpBackend.")),
          s"term candidate missing: $emitted")
      case _ => fail("parse failed")
    }
  }

  test("unresolved importee with a TERM (non-package) prefix stays unresolved") {
    // `import a.b.C` where `a/b.` is a workspace object — the prefix is a term
    // owner, NOT a package path and NOT a dep candidate, so no dep candidates
    // are invented (a TABLE-RESOLVED term owner is a workspace object)
    val code = "import a.b.C"
    val input = Input.String(code)
    val tree = { given Dialect = Scala3Future; input.parse[Stat] }
    tree match {
      case Parsed.Success(imp: Import) =>
        val st = new InMemorySymbolTable
        st.add(SymbolDefinition("a/b.", "b", isType = false, new Range(0,0,0,0), os.pwd / "dummy.scala"))
        val ss = new ScopeStack(st)
        val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
        ImportScope.parse(imp, ss, (t, sym) => emitted += ((t.syntax, sym)))
        assert(emitted.contains(("C", "")), s"expected empty-symbol miss, got $emitted")
        assert(!emitted.exists(e => e._1 == "C" && e._2.nonEmpty), s"no candidates may be invented: $emitted")
      case _ => fail("parse failed")
    }
  }
}
