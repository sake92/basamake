package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import ba.sake.basamake.navigation.{SymbolTable, InMemorySymbolTable}
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, Range => SdbRange, SymbolOccurrence}

/** Integration tests for WorkspaceIndex.invalidate / initialize semanticdb pairing.
  *
  * The key scenario: fresh sbt project → compile generates semanticdb → invalidate
  * must load it. sbt passes NO `-sourceroot` flag, so the BSP-provided sourceRoot
  * can be wrong (previously os.pwd — the LSP process cwd). Pairing must survive
  * via ancestor climbing from the .semanticdb file.
  */
class WorkspaceIndexInvalidateTest extends FunSuite {

  private def freshIndexAt(root: os.Path): (WorkspaceIndex, SymbolTable) = {
    val st = new InMemorySymbolTable
    val idx = new WorkspaceIndex(root, st)
    idx.initialize(List.empty)
    (idx, st)
  }

  /** Build an sbt-like fixture in os.pwd/tmp:
    *   root/src/main/scala/{Main,utils}.scala
    *   root/target/scala-3.8.4/meta/META-INF/semanticdb/src/main/scala/{Main,utils}.scala.semanticdb
    *
    * Main.scala references `ext.getMsg()` — the SOURCE parser cannot resolve `ext`
    * (empty symbol), but the semanticdb fixture claims the ref is
    * `_empty_/utils.getMsg()`. This discriminates semanticdb-based occurrences
    * from source-parsed ones.
    *
    * @return fixture root
    */
  private def buildSbtLikeFixture(): os.Path = {
    val root = os.pwd / "tmp" / s"semdb-invalidate-${System.currentTimeMillis()}"
    val srcDir = root / "src" / "main" / "scala"
    os.makeDir.all(srcDir)
    val semDir = root / "target" / "scala-3.8.4" / "meta" / "META-INF" / "semanticdb" / "src" / "main" / "scala"
    os.makeDir.all(semDir)

    val utilsContent = "object utils:\n  def getMsg() = \"bla\"\n"
    val mainContent = "object Main:\n  def main(args: Array[String]): Unit =\n    println(ext.getMsg())\n"
    os.write(srcDir / "utils.scala", utilsContent)
    os.write(srcDir / "Main.scala", mainContent)

    // utils.scala.semanticdb — definitions
    val utilsDoc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "src/main/scala/utils.scala",
      text = utilsContent,
      language = Language.SCALA,
      symbols = Nil,
      occurrences = List(
        SymbolOccurrence(symbol = "_empty_/utils.", range = Some(SdbRange(0, 7, 0, 12)), role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(symbol = "_empty_/utils.getMsg().", range = Some(SdbRange(1, 6, 1, 12)), role = SymbolOccurrence.Role.DEFINITION)
      )
    )
    // Main.scala.semanticdb — references
    val mainDoc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "src/main/scala/Main.scala",
      text = mainContent,
      language = Language.SCALA,
      symbols = Nil,
      occurrences = List(
        SymbolOccurrence(symbol = "_empty_/utils.", range = Some(SdbRange(2, 12, 2, 15)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "_empty_/utils.getMsg().", range = Some(SdbRange(2, 16, 2, 22)), role = SymbolOccurrence.Role.REFERENCE)
      )
    )
    os.write(semDir / "utils.scala.semanticdb", TextDocuments(List(utilsDoc)).toByteArray)
    os.write(semDir / "Main.scala.semanticdb", TextDocuments(List(mainDoc)).toByteArray)
    root
  }

  private def semanticdbDirOf(root: os.Path): os.Path =
    root / "target" / "scala-3.8.4" / "meta"

  // ── invalidate: the fresh-sbt-project flow ────────────────────

  test("invalidate: wrong sourceRoot (os.pwd regression) → ancestor climbing still pairs") {
    val root = buildSbtLikeFixture()
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val (idx, st) = freshIndexAt(root) // source-only init (no data.json on fresh project)
      idx.onDidOpen(mainFile)

      // Before invalidate: source parser cannot resolve `ext` → no getMsg symbol
      val before = idx.findSymbolsAt(mainFile, 2, 18)
      assert(!before.exists(_.contains("getMsg")), s"expected unresolved before invalidate, got $before")

      // Simulate the bug: sourceRoot = LSP process cwd (parent of the project), not the project base
      val wrongRoot = root / os.up
      idx.invalidate(List(SemanticdbDirs(wrongRoot, semanticdbDirOf(root))))

      val utilsSym = st.get("_empty_/utils.getMsg().")
      assert(utilsSym.isDefined, "semanticdb def should be in symbol table after invalidate")
      assertEquals(utilsSym.get.path, root / "src" / "main" / "scala" / "utils.scala")

      // Open buffer switched to semanticdb occurrences
      val after = idx.findSymbolsAt(mainFile, 2, 18)
      assert(after.contains("_empty_/utils.getMsg()."), s"semanticdb occurrences should be used after invalidate, got $after")

      // goto-def resolves to utils.scala
      val locs = idx.gotoDefinitions(mainFile, 2, 18)
      assert(locs.nonEmpty, s"expected getMsg goto to resolve via semanticdb, got empty")
      assertEquals(locs.head.path.last, "utils.scala")
    } finally os.remove.all(root)
  }

  test("invalidate: correct sourceRoot pairs directly (no climbing needed)") {
    val root = buildSbtLikeFixture()
    try {
      val (idx, st) = freshIndexAt(root)
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      val utilsSym = st.get("_empty_/utils.getMsg().")
      assert(utilsSym.isDefined, "semanticdb def should be in symbol table after invalidate")
      assertEquals(utilsSym.get.path, root / "src" / "main" / "scala" / "utils.scala")
    } finally os.remove.all(root)
  }

  test("invalidate: unmatched semanticdb uri does not throw, others still pair") {
    val root = buildSbtLikeFixture()
    try {
      // Ghost semanticdb whose uri matches no source file
      val ghostDoc = TextDocument(
        schema = Schema.SEMANTICDB4,
        uri = "no/such/Foo.scala",
        text = "",
        language = Language.SCALA,
        symbols = Nil,
        occurrences = List(
          SymbolOccurrence(symbol = "_empty_/Ghost#", range = Some(SdbRange(0, 0, 0, 5)), role = SymbolOccurrence.Role.DEFINITION)
        )
      )
      val ghostPath = root / "target" / "scala-3.8.4" / "meta" / "META-INF" / "semanticdb" / "Ghost.scala.semanticdb"
      os.makeDir.all(ghostPath / os.up)
      os.write(ghostPath, TextDocuments(List(ghostDoc)).toByteArray)

      val (idx, st) = freshIndexAt(root)
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root)))) // must not throw
      assert(st.get("_empty_/utils.getMsg().").isDefined, "matched files still paired despite ghost")
    } finally os.remove.all(root)
  }

  test("invalidate: no-op on empty roots and on nonexistent dirs") {
    val root = buildSbtLikeFixture()
    try {
      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)
      idx.invalidate(Nil) // must not throw
      idx.invalidate(List(SemanticdbDirs("/no/such/source", "/no/such/sem"))) // must not throw
    } finally os.remove.all(root)
  }

  // ── initialize with roots (startup with data.json present) ────

  test("initialize with roots: open buffer occurrences come from semanticdb") {
    val root = buildSbtLikeFixture()
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)
      idx.initialize(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      idx.onDidOpen(mainFile)

      // `ext` is unresolvable by the source parser — the symbol can only come from semanticdb
      val syms = idx.findSymbolsAt(mainFile, 2, 18)
      assert(syms.contains("_empty_/utils.getMsg()."), s"expected semanticdb symbol, got $syms")
    } finally os.remove.all(root)
  }

  // ── debug dump refresh ───────────────────────────────────────

  test("invalidate refreshes index_sources.txt debug dump (fresh-project flow)") {
    val root = buildSbtLikeFixture()
    try {
      val (idx, _) = freshIndexAt(root) // source-only init writes the dump with NO pairs

      val before = os.read(root / ".basamake" / "index_sources.txt")
      assert(before.contains("<<NO SEMANTICDB>>"), "before invalidate the dump shows no pairs")

      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))

      val after = os.read(root / ".basamake" / "index_sources.txt")
      assert(after.contains("src/main/scala/utils.scala"), s"dump lists the source:\n$after")
      assert(!after.contains("<<NO SEMANTICDB>>"), s"dump must be refreshed after invalidate:\n$after")
      assert(after.contains(".semanticdb"), s"dump must show semanticdb pairs:\n$after")
    } finally os.remove.all(root)
  }

  // ── close vs delete semantics ────────────────────────────────

  test("closing an open buffer does not lose semanticdb pairing or definitions") {
    val root = buildSbtLikeFixture()
    try {
      val utilsFile = root / "src" / "main" / "scala" / "utils.scala"
      val (idx, st) = freshIndexAt(root)
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      assert(st.get("_empty_/utils.getMsg().").isDefined, "defs loaded after invalidate")

      idx.onDidOpen(utilsFile)
      idx.onDidClose(utilsFile) // tab close (e.g. VS Code preview-tab switch)

      assert(st.get("_empty_/utils.getMsg().").isDefined,
        "definitions must survive closing a tab (only disk events purge them)")
      val dump = os.read(root / ".basamake" / "index_sources.txt")
      assert(!dump.contains("<<NO SEMANTICDB>>"), s"dump must still show pairs after close:\n$dump")
    } finally os.remove.all(root)
  }

  test("onFilesDeleted purges pairing and definitions for deleted files") {
    val root = buildSbtLikeFixture()
    try {
      val utilsFile = root / "src" / "main" / "scala" / "utils.scala"
      val (idx, st) = freshIndexAt(root)
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      assert(st.get("_empty_/utils.getMsg().").isDefined, "defs loaded after invalidate")

      os.remove(utilsFile) // file gone from disk (external delete / rename)
      idx.onFilesDeleted(Set(utilsFile))

      assert(st.get("_empty_/utils.getMsg().").isEmpty,
        "definitions must be purged when the file is deleted")
      val dump = os.read(root / ".basamake" / "index_sources.txt")
      assert(!dump.contains("utils.scala"),
        s"deleted file must disappear from the dump entirely:\n$dump")
    } finally os.remove.all(root)
  }

  test("onFilesCreated adds post-initialize files to the source list") {
    val root = buildSbtLikeFixture()
    try {
      val (idx, _) = freshIndexAt(root)
      val newFile = root / "src" / "main" / "scala" / "NewThing.scala"
      os.write(newFile, "object NewThing\n")

      idx.onFilesCreated(Set(newFile))

      val dump = os.read(root / ".basamake" / "index_sources.txt")
      assert(dump.contains("NewThing.scala"),
        s"dump must list files created after initialize:\n$dump")
    } finally os.remove.all(root)
  }
}
