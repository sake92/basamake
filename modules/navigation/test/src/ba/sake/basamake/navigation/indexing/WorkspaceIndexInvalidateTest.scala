package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import ba.sake.basamake.navigation.{SymbolTable, InMemorySymbolTable, SymbolDefinition}
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
    // debugSymbolTableDump = true: the flusher/throttling tests below assert on
    // .basamake/symbol_table.txt content (default startup path skips it)
    val idx = new WorkspaceIndex(root, st, debugSymbolTableDump = true)
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

  // ── gitignored paths never enter the index (entry-point guards) ─

  test("gitignored paths never enter the index via open/create/save") {
    val root = buildSbtLikeFixture()
    try {
      os.write(root / ".gitignore", ".worktrees/\n")
      val (idx, st) = freshIndexAt(root) // engine reads .gitignore at construction

      val wtFile = root / ".worktrees" / "gitignore-roots" / "A.scala"
      os.write(wtFile, "object WorktreeThing\n", createFolders = true)

      // all entry points must be no-ops for gitignored paths
      idx.onFilesCreated(Set(wtFile))
      idx.onDidOpen(wtFile)
      idx.onDidSave(wtFile)

      val dump = os.read(root / ".basamake" / "index_sources.txt")
      // assert on the gitignored file's path segment, not the literal ".worktrees"
      // — the dump header prints the workspace path, which itself contains
      // ".worktrees" when the repo lives in a worktree (e.g. .worktrees/<branch>/)
      assert(!dump.contains("gitignore-roots"),
        s"gitignored files must not appear in the dump:\n$dump")
      assert(st.get("_empty_/WorktreeThing#").isEmpty,
        "no definitions may be extracted for gitignored files")
    } finally os.remove.all(root)
  }

  test("nested repo paths never enter the index via open/save/create") {
    val root = buildSbtLikeFixture()
    try {
      os.makeDir.all(root / "nested" / ".git")
      val (idx, st) = freshIndexAt(root)
      val nestedFile = root / "nested" / "sub" / "A.scala"
      os.write(nestedFile, "object NestedThing\n", createFolders = true)

      // all entry points must be no-ops for nested-repo paths
      idx.onFilesCreated(Set(nestedFile))
      idx.onDidOpen(nestedFile)
      idx.onDidSave(nestedFile)

      val dump = os.read(root / ".basamake" / "index_sources.txt")
      assert(!dump.contains("A.scala"),
        s"nested repo files must not appear in the dump:\n$dump")
      assert(st.get("_empty_/NestedThing.").isEmpty,
        "no definitions may be extracted for nested repo files")
    } finally os.remove.all(root)
  }

  // ── semanticdb pairing guards (warm-start hardening) ─────────

  test("invalidate: semanticdb roots inside a nested repo are skipped") {
    val fixture = buildSbtLikeFixture()
    val outer = os.pwd / "tmp" / s"semdb-nested-${System.currentTimeMillis()}"
    os.makeDir.all(outer)
    try {
      os.makeDir.all(outer / ".git")
      val nested = outer / "nested"
      os.move(fixture, nested)
      os.makeDir.all(nested / ".git")

      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(outer, st)
      idx.invalidate(List(SemanticdbDirs(nested, semanticdbDirOf(nested))))

      assert(st.get("_empty_/utils.getMsg().").isEmpty,
        "semanticdb from a nested repo must not be indexed")
    } finally os.remove.all(outer)
  }

  test("initialize with roots: nested repo semanticdb roots are skipped") {
    val fixture = buildSbtLikeFixture()
    val outer = os.pwd / "tmp" / s"semdb-nested-${System.currentTimeMillis()}"
    os.makeDir.all(outer)
    try {
      os.makeDir.all(outer / ".git")
      val nested = outer / "nested"
      os.move(fixture, nested)
      os.makeDir.all(nested / ".git")

      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(outer, st)
      idx.initialize(List(SemanticdbDirs(nested, semanticdbDirOf(nested))))

      assert(st.get("_empty_/utils.getMsg().").isEmpty,
        "semanticdb from a nested repo must not be indexed at startup")
    } finally os.remove.all(outer)
  }

  test("initialize with roots: pairs resolving into a nested repo are rejected") {
    val fixture = buildSbtLikeFixture()
    val outer = os.pwd / "tmp" / s"semdb-nested-${System.currentTimeMillis()}"
    os.makeDir.all(outer)
    try {
      os.makeDir.all(outer / ".git")
      val nested = outer / "nested"
      os.move(fixture, nested)
      os.makeDir.all(nested / ".git")

      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(outer, st)
      // sourceRoot = outer (NOT inside nested) — exercises the per-pair guard,
      // not the root guard. The climb resolves URIs into outer/nested.
      idx.initialize(List(SemanticdbDirs(outer, semanticdbDirOf(nested))))

      assert(st.get("_empty_/utils.getMsg().").isEmpty,
        "definitions for sources inside a nested repo must be purged")
    } finally os.remove.all(outer)
  }

  // ── perf: invalidate skips unchanged semanticdb files ─────────

  test("invalidate skips unchanged .semanticdb files (re-invalidate is a no-op)") {
    val root = buildSbtLikeFixture()
    try {
      val (idx, _) = freshIndexAt(root)
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      val afterFirst = idx.indexedSemanticdbFiles
      assertEquals(afterFirst, 2L, "both semanticdb files indexed on first invalidate")
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      assertEquals(idx.indexedSemanticdbFiles, afterFirst, "unchanged files must not be re-indexed")
    } finally os.remove.all(root)
  }

  test("invalidate re-indexes only changed .semanticdb files") {
    val root = buildSbtLikeFixture()
    try {
      val (idx, st) = freshIndexAt(root)
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      val afterFirst = idx.indexedSemanticdbFiles

      // a compile produced one more source + semanticdb file
      os.write(root / "src" / "main" / "scala" / "Extra.scala", "object Extra:\n  def extra() = 1\n")
      val newDoc = TextDocument(
        schema = Schema.SEMANTICDB4,
        uri = "src/main/scala/Extra.scala",
        text = "object Extra:\n  def extra() = 1\n",
        language = Language.SCALA,
        symbols = Nil,
        occurrences = List(
          SymbolOccurrence(symbol = "_empty_/Extra#", range = Some(SdbRange(0, 7, 0, 12)), role = SymbolOccurrence.Role.DEFINITION)
        )
      )
      os.write(semanticdbDirOf(root) / "META-INF" / "semanticdb" / "src" / "main" / "scala" / "Extra.scala.semanticdb",
        TextDocuments(List(newDoc)).toByteArray)

      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      assertEquals(idx.indexedSemanticdbFiles, afterFirst + 1, "only the new file is re-indexed")
      assert(st.get("_empty_/Extra#").isDefined, "new file's defs are loaded")
    } finally os.remove.all(root)
  }

  // ── perf: symbol_table.txt is throttled (index_sources.txt stays sync) ──

  test("invalidate defers symbol_table.txt to the throttled flusher") {
    val root = buildSbtLikeFixture()
    try {
      val (idx, _) = freshIndexAt(root) // init writes both dump files synchronously
      val dumpFile = root / ".basamake" / "symbol_table.txt"
      Thread.sleep(20) // separate mtimes — a sync rewrite would then be detectable
      val before = os.mtime(dumpFile)

      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      assert(idx.symbolTableDumpDirty, "invalidate must mark the symbol table dump dirty")
      assertEquals(os.mtime(dumpFile), before, "invalidate must NOT rewrite symbol_table.txt synchronously")

      idx.flushSymbolTableDump()
      assert(!idx.symbolTableDumpDirty, "flush clears the dirty flag")
      assert(os.read(dumpFile).contains("_empty_/utils.getMsg()."),
        "flushed dump contains the new defs")

      // index_sources.txt IS refreshed synchronously — tests above assert content
      val sources = os.read(root / ".basamake" / "index_sources.txt")
      assert(sources.contains("src/main/scala/utils.scala"), "index_sources.txt refreshed synchronously")
    } finally os.remove.all(root)
  }

  test("default startup path skips symbol_table.txt (opt-in debug dump)") {
    val root = buildSbtLikeFixture()
    try {
      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st) // debugSymbolTableDump = false (default)
      idx.initialize(List.empty)
      assert(!os.exists(root / ".basamake" / "symbol_table.txt"),
        "symbol_table.txt must not be written on the default startup path")
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      assert(!idx.symbolTableDumpDirty, "invalidate must not mark the dump dirty when disabled")
      assert(!os.exists(root / ".basamake" / "symbol_table.txt"),
        "invalidate must not start the heavy flusher when the dump is disabled")
      // the lightweight dump still works
      assert(os.exists(root / ".basamake" / "index_sources.txt"),
        "index_sources.txt stays on the default path")
    } finally os.remove.all(root)
  }

  // ── perf: gotoDefinitions retries during an invalidation window ──

  test("gotoDefinitions retries while an invalidation is in progress") {
    val root = buildSbtLikeFixture()
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val utilsFile = root / "src" / "main" / "scala" / "utils.scala"
      val (idx, st) = freshIndexAt(root)
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root)))) // semanticdb occurrences
      idx.onDidOpen(mainFile)
      st.removeByPath(utilsFile) // simulate the tear-down half of an invalidation

      val fut = new java.util.concurrent.CompletableFuture[Vector[SymbolDefinition]]()
      idx.setInvalidating(true)
      Thread.ofVirtual().start(() => fut.complete(idx.gotoDefinitions(mainFile, 2, 18)))
      Thread.sleep(150) // first resolution comes up empty → retry loop waits
      st.add(SymbolDefinition("_empty_/utils.getMsg().", "getMsg", isType = false,
        SdbRange(1, 6, 1, 12), utilsFile))

      val locs = fut.get(5, java.util.concurrent.TimeUnit.SECONDS)
      assert(locs.nonEmpty, s"retry must resolve the def that appeared mid-invalidation, got empty")
      assertEquals(locs.head.path.last, "utils.scala")
    } finally os.remove.all(root)
  }

  test("gotoDefinitions does not retry when the cursor is on a def site (no refs)") {
    val root = buildSbtLikeFixture()
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val (idx, _) = freshIndexAt(root)
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      idx.onDidOpen(mainFile)
      idx.setInvalidating(true)
      // no occurrence at (0, 1) → None → no retry loop → immediate empty
      val locs = idx.gotoDefinitions(mainFile, 0, 1)
      assert(locs.isEmpty, s"def-site goto must return empty, got $locs")
    } finally os.remove.all(root)
  }

  // ── perf: onDidChange skips refreshes while the disk file is unchanged ──

  test("onDidChange skips buffer refresh while the disk file is unchanged") {
    val root = buildSbtLikeFixture()
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val (idx, _) = freshIndexAt(root)
      idx.invalidate(List(SemanticdbDirs(root, semanticdbDirOf(root))))
      idx.onDidOpen(mainFile)
      val afterOpen = idx.bufferRefreshCountValue

      idx.onDidChange(mainFile) // typing with an unchanged disk file → no refresh
      assertEquals(idx.bufferRefreshCountValue, afterOpen, "unchanged disk must skip the refresh")

      // external disk change (different size) → next didChange refreshes
      os.write.over(mainFile, "object Main:\n  def main(args: Array[String]): Unit =\n    println(ext.getMsg()); println(\"changed\")\n")
      idx.onDidChange(mainFile)
      assertEquals(idx.bufferRefreshCountValue, afterOpen + 1, "disk change must trigger a refresh")
    } finally os.remove.all(root)
  }
}
