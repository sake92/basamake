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

  // ═══════════════════════════════════════════════════════════════
  // pairSourceFromRoot — direct single-source pairing (onDidOpen fast path)
  // ═══════════════════════════════════════════════════════════════

  /** Build a fixture with source at `src/main/scala/Main.scala` and a semanticdb
    * file at the conventional `<semDir>/META-INF/semanticdb/<uri>.semanticdb`
    * layout, with the given TextDocument `uri`. */
  private def buildPairFixture(uri: String): (os.Path, os.Path, os.Path, os.Path) = {
    val root = os.temp.dir(prefix = "pair-root-")
    val srcDir = root / "src" / "main" / "scala"
    os.makeDir.all(srcDir)
    val semDir = root / "target" / "scala-3.8.4" / "meta"
    os.makeDir.all(semDir / "META-INF" / "semanticdb" / "src" / "main" / "scala")
    val srcPath = srcDir / "Main.scala"
    os.write(srcPath, "object Main:\n  def m() = 1\n")
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = uri,
      text = "object Main:\n  def m() = 1\n",
      language = Language.SCALA,
      symbols = Nil,
      occurrences = List(
        SymbolOccurrence(symbol = "_empty_/Main.", range = Some(SdbRange(0, 7, 0, 11)), role = SymbolOccurrence.Role.DEFINITION)
      )
    )
    val semPath = semDir / "META-INF" / "semanticdb" / "src" / "main" / "scala" / "Main.scala.semanticdb"
    os.write(semPath, TextDocuments(List(doc)).toByteArray)
    (root, srcPath, semDir, semPath)
  }

  test("pairSourceFromRoot: conventional candidate under the root pairs and indexes defs") {
    val (root, srcPath, semDir, semPath) = buildPairFixture("src/main/scala/Main.scala")
    try {
      val st = new ba.sake.basamake.navigation.InMemorySymbolTable
      val res = SemanticdbIndexing.pairSourceFromRoot(srcPath, root, semDir, root, st)
      assertEquals(res, Some(semPath), "candidate with matching uri must be returned")
      assert(st.get("_empty_/Main.").isDefined, "definition occurrences must be indexed")
    } finally os.remove.all(root)
  }

  test("pairSourceFromRoot: source outside the root returns None") {
    val (root, _, semDir, _) = buildPairFixture("src/main/scala/Main.scala")
    try {
      val outside = os.temp.dir(prefix = "pair-outside-")
      try {
        val st = new ba.sake.basamake.navigation.InMemorySymbolTable
        val res = SemanticdbIndexing.pairSourceFromRoot(outside / "Other.scala", root, semDir, root, st)
        assertEquals(res, None, "source outside the root must not be paired")
      } finally os.remove.all(outside)
    } finally os.remove.all(root)
  }

  test("pairSourceFromRoot: absent candidate returns None silently") {
    val (root, srcPath, _, _) = buildPairFixture("src/main/scala/Main.scala")
    try {
      // a source under the root with NO semanticdb file at the conventional path
      val unpaired = root / "src" / "main" / "scala" / "Unpaired.scala"
      os.write(unpaired, "object Unpaired\n")
      val st = new ba.sake.basamake.navigation.InMemorySymbolTable
      val res = SemanticdbIndexing.pairSourceFromRoot(unpaired, root, root / "target" / "scala-3.8.4" / "meta", root, st)
      assertEquals(res, None, "missing candidate must be an ordinary None (not yet present)")
    } finally os.remove.all(root)
  }

  test("pairSourceFromRoot: candidate whose uri maps to another source returns None") {
    val (root, srcPath, semDir, _) = buildPairFixture("src/main/scala/Other.scala")
    try {
      // Other.scala exists on disk, so resolveSourcePath succeeds — but it is
      // NOT the requested source, so the pairing must be rejected
      os.write(root / "src" / "main" / "scala" / "Other.scala", "object Other\n")
      val st = new ba.sake.basamake.navigation.InMemorySymbolTable
      val res = SemanticdbIndexing.pairSourceFromRoot(srcPath, root, semDir, root, st)
      assertEquals(res, None, "candidate for a different source must not be used")
      assert(st.get("_empty_/Main.").isEmpty, "no defs may be indexed for a rejected candidate")
    } finally os.remove.all(root)
  }

  test("pairSourceFromRoot: candidate uri resolving to a nonexistent source returns None") {
    val (root, srcPath, semDir, _) = buildPairFixture("no/such/file.scala")
    try {
      val st = new ba.sake.basamake.navigation.InMemorySymbolTable
      val res = SemanticdbIndexing.pairSourceFromRoot(srcPath, root, semDir, root, st)
      assertEquals(res, None, "candidate whose uri matches no source file must be rejected")
    } finally os.remove.all(root)
  }

  test("pairSourceFromRoot: malformed candidate data does not throw") {
    val (root, srcPath, semDir, semPath) = buildPairFixture("src/main/scala/Main.scala")
    try {
      os.write.over(semPath, "not a semanticdb protobuf".getBytes("UTF-8"))
      val st = new ba.sake.basamake.navigation.InMemorySymbolTable
      val res = SemanticdbIndexing.pairSourceFromRoot(srcPath, root, semDir, root, st)
      assertEquals(res, None, "malformed candidate must degrade to None, not throw")
    } finally os.remove.all(root)
  }
}
