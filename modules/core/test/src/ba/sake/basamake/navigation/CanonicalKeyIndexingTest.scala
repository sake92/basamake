package ba.sake.basamake.navigation

import munit.FunSuite
import org.eclipse.lsp4j.{Location, Position, Range}

class CanonicalKeyIndexingTest extends FunSuite {

  test("dependency source indexing stores only canonical keys") {
    val depSlice = DependencySourceIndexing.indexSourceContent(
      "file:///tmp/pkg/Foo.scala",
      "package pkg\nclass Owner { def run(x: Int): Unit = () }"
    ).head

    // Only canonical symbols, no stripped/alias keys
    val keys = depSlice.symbolDefinitions.keySet
    assert(keys.contains("pkg/Owner#"), clues(keys))
    assert(keys.contains("pkg/Owner#`<init>`()."), clues(keys))
    assert(keys.contains("pkg/Owner#run()."), clues(keys))
    // Alias keys must be absent
    assert(!keys.contains("Owner.run"), clues(keys))
    assert(!keys.contains("Owner"), clues(keys))
    assert(!keys.contains("run"), clues(keys))
    assert(!keys.contains("pkg/Owner.run"), clues(keys))
  }

  test("workspace SemanticDB indexing stores raw compiler symbols verbatim") {
    // Build a minimal TextDocument message and serialize it
    val doc = scala.meta.internal.semanticdb.TextDocument(
      uri = "src/main/scala/Foo.scala",
      language = scala.meta.internal.semanticdb.Language.SCALA,
      occurrences = Seq(
        scala.meta.internal.semanticdb.SymbolOccurrence(
          range = Some(scala.meta.internal.semanticdb.Range(0, 6, 0, 9)),
          symbol = "pkg/Owner#run().",
          role = scala.meta.internal.semanticdb.SymbolOccurrence.Role.DEFINITION
        ),
        scala.meta.internal.semanticdb.SymbolOccurrence(
          range = Some(scala.meta.internal.semanticdb.Range(5, 0, 5, 3)),
          symbol = "pkg/Owner#run().",
          role = scala.meta.internal.semanticdb.SymbolOccurrence.Role.REFERENCE
        ),
        scala.meta.internal.semanticdb.SymbolOccurrence(
          range = Some(scala.meta.internal.semanticdb.Range(6, 0, 6, 3)),
          symbol = "",  // empty symbol (unresolvable SUID)
          role = scala.meta.internal.semanticdb.SymbolOccurrence.Role.REFERENCE
        )
      ),
      symbols = Seq.empty
    )
    val td = new scala.meta.internal.semanticdb.TextDocuments(List(doc))
    val bytes = td.toByteArray

    // Write to a temp path that mimics META-INF/semanticdb structure
    val tmp = os.temp.dir(prefix = "canonical-workspace")
    try {
      val semanticdbDir = tmp / "META-INF" / "semanticdb" / "src" / "main" / "scala"
      os.makeDir.all(semanticdbDir)
      val sdbFile = semanticdbDir / "Foo.scala.semanticdb"
      os.write.over(sdbFile, bytes)

      val sourceRoots = List(tmp)
      val sliceOpt = SemanticdbIndexing.parseSemanticdbFile(tmp, sdbFile, sourceRoots)
      assert(sliceOpt.nonEmpty, "should parse semanticdb file")

      val slice = sliceOpt.get
      val defKeys = slice.symbolDefinitions.keySet
      val refKeys = slice.symbolReferences.keySet

      // Raw symbol is the only key
      assertEquals(defKeys, Set("pkg/Owner#run()."))
      assertEquals(refKeys, Set("pkg/Owner#run()."))

      // No alias keys
      assert(!defKeys.contains("pkg/Owner.run"), clues(defKeys))
      assert(!defKeys.contains("Owner.run"), clues(defKeys))
      assert(!defKeys.contains("run"), clues(defKeys))

      // Empty symbol is filtered
      assert(!refKeys.contains(""), clues(refKeys))
    } finally os.remove.all(tmp)
  }

  test("dependency source slice has single canonical key per definition") {
    val definitions = DependencySourceParsing.extractDefinitions(
      "Outer.scala",
      """package com.example
        |class Outer {
        |  def run(): Unit = ()
        |  def run(x: Int): Unit = ()
        |}
        |""".stripMargin
    )
    val slice = DependencySourceIndexing.indexSourceContent(
      "file:///tmp/Outer.scala",
      """package com.example
        |class Outer {
        |  def run(): Unit = ()
        |  def run(x: Int): Unit = ()
        |}
        |""".stripMargin
    ).head

    val defKeys = slice.symbolDefinitions.keySet
    assertEquals(defKeys, Set(
      "com/example/Outer#",
      "com/example/Outer#`<init>`().",
      "com/example/Outer#run().",
      "com/example/Outer#run(+1)."
    ), clues(defKeys))
  }
}
