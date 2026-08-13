package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, Range => SdbRange, SymbolOccurrence}

/** Deterministic coverage for the `ResolvedFile.complete=false` fallback path:
  * Scala 3 `-Ybest-effort` emits SHORT ref symbols (`utils.` instead of
  * `_empty_/utils.`) under compile errors. `parseOccurrences` flags the file
  * incomplete so the caller falls back to source parsing for refs, while
  * DEFINITION occurrences (full symbols) stay authoritative in SymbolTable. */
class SemanticdbIndexingTest extends FunSuite {

  private def writeSemDoc(dir: os.Path, name: String, occurrences: List[SymbolOccurrence]): os.Path = {
    os.makeDir.all(dir)
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "Main.scala",
      text = "object Main:\n  def m() = 1\n",
      language = Language.SCALA,
      symbols = Nil,
      occurrences = occurrences
    )
    val p = dir / name
    os.write(p, TextDocuments(List(doc)).toByteArray)
    p
  }

  test("full ref symbols → complete=true") {
    val dir = os.temp.dir(prefix = "semdb-complete-")
    try {
      val semPath = writeSemDoc(dir, "full.semanticdb", List(
        SymbolOccurrence(symbol = "_empty_/utils.", range = Some(SdbRange(0, 0, 0, 5)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "local0", range = Some(SdbRange(0, 6, 0, 7)), role = SymbolOccurrence.Role.DEFINITION)
      ))
      val rf = SemanticdbIndexing.parseOccurrences(semPath, os.pwd / "Main.scala")
      assert(rf.complete, "full symbols must be complete")
      assertEquals(rf.occurrences.map(_.symbol).toList, List("_empty_/utils."))
      assertEquals(rf.locals.map(_.symbol).toList, List("local0"))
    } finally os.remove.all(dir)
  }

  test("partial ref symbols (-Ybest-effort) → complete=false") {
    val dir = os.temp.dir(prefix = "semdb-partial-")
    try {
      val semPath = writeSemDoc(dir, "partial.semanticdb", List(
        SymbolOccurrence(symbol = "utils.", range = Some(SdbRange(0, 0, 0, 5)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "utils.getMsg().", range = Some(SdbRange(0, 6, 0, 12)), role = SymbolOccurrence.Role.REFERENCE)
      ))
      val rf = SemanticdbIndexing.parseOccurrences(semPath, os.pwd / "Main.scala")
      assert(!rf.complete, "short ref symbols must flag complete=false (source-parse fallback)")
      // occurrences are still delivered — the CALLER decides to fall back
      assertEquals(rf.occurrences.map(_.symbol).toList, List("utils.", "utils.getMsg()."))
    } finally os.remove.all(dir)
  }

  test("mixed full defs + partial refs → incomplete refs, locals still delivered") {
    val dir = os.temp.dir(prefix = "semdb-mixed-")
    try {
      val semPath = writeSemDoc(dir, "mixed.semanticdb", List(
        SymbolOccurrence(symbol = "_empty_/utils.getMsg().", range = Some(SdbRange(1, 6, 1, 12)), role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(symbol = "utils.", range = Some(SdbRange(0, 2, 0, 7)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "local0", range = Some(SdbRange(0, 8, 0, 9)), role = SymbolOccurrence.Role.DEFINITION)
      ))
      val rf = SemanticdbIndexing.parseOccurrences(semPath, os.pwd / "Main.scala")
      assert(!rf.complete, "any partial ref flags the file incomplete")
      // document-scoped locals (full local<N> symbols) are still delivered
      assertEquals(rf.locals.map(_.symbol).toList, List("local0"))
      // global DEFINITION occurrences are NOT refs (they go to SymbolTable
      // via parseDefinitions and stay authoritative)
      assert(!rf.occurrences.exists(_.symbol == "_empty_/utils.getMsg()."),
        s"defs must not leak into refs, got ${rf.occurrences.map(_.symbol)}")
    } finally os.remove.all(dir)
  }
}
