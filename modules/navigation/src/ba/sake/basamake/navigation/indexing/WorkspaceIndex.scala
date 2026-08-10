package ba.sake.basamake.navigation.indexing

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.navigation.*
import ba.sake.basamake.navigation.scalasrc.{ScalaDefinitionsExtractor, ScalaReferencesResolver }
import ba.sake.basamake.navigation.javasrc.{JavaDefinitionsExtractor, JavaReferencesResolver}

/** Per-source index state. `occurrences`/`locals` are populated only while the
  * file is open; `semanticdbPath` is workspace-level state that survives tab close. */
private final case class SourceData(
    occurrences: Vector[ReferenceOccurrence],
    locals: Vector[SymbolDefinition],
    semanticdbPath: Option[os.Path]
)

private object SourceData {
  val empty: SourceData = SourceData(Vector.empty, Vector.empty, None)
}

class WorkspaceIndex(workspacePath: os.Path, symbolTable: SymbolTable, ignorePatterns: Vector[String] = Vector.empty) extends StrictLogging {

  // One map for ALL workspace sources (keyed by path): the keySet IS the live
  // source list (no separate knownSources), semanticdbPath is the pairing,
  // occurrences/locals are the open-file data. ConcurrentHashMap so queries
  // never block on BSP-compile invalidations (no synchronized).
  private val sourcesMap = new ConcurrentHashMap[os.Path, SourceData]()
  // open files — separate set: an open file with zero references must stay "open"
  private val openFiles = ConcurrentHashMap.newKeySet[os.Path]()

  // serialize debug-dump file writes (os.write.over is not atomic)
  private val dumpLock = new Object

  // gitignore engine — built once at construction, used by the walk AND by the
  // entry-point guards below. Replaced wholesale on .gitignore changes (volatile
  // so concurrent guard calls never see a half-mutated engine).
  @volatile private var ignoreEngine = new GitIgnoreEngine(workspacePath, ignorePatterns)

  /** Re-read `.gitignore` rules — called by BspManager on .gitignore change events. */
  def reloadIgnores(): Unit = ignoreEngine = new GitIgnoreEngine(workspacePath, ignorePatterns)

  /** True for paths that must never enter the index: gitignored paths INSIDE the
    * workspace (e.g. `.worktrees/`, `node_modules/`) or under an always-skip dir.
    * Paths OUTSIDE the workspace (deps/JDK sources opened via goto-def) are never
    * blocked — GitIgnoreEngine treats everything outside its root as ignored. */
  private def isIgnoredWorkspacePath(p: os.Path): Boolean = {
    if !p.startsWith(workspacePath) then return false
    var cur = p
    while cur.startsWith(workspacePath) do {
      if GitIgnoreEngine.alwaysSkipDirNames.contains(cur.last) then return true
      cur = cur / os.up
    }
    ignoreEngine.isIgnored(p, isDir = os.isDir(p))
  }

  // ── initialize ──────────────────────────────────────────────
  def initialize(roots: List[SemanticdbDirs]): Unit = {
    logger.info(s"Initializing workspace index at $workspacePath")
    val relevantExtensions = Set("scala", "java")
    def skip(p: os.Path): Boolean =
      if os.isDir(p) then GitIgnoreEngine.alwaysSkipDirNames.contains(p.last) || ignoreEngine.isIgnored(p, isDir = true)
      else if os.isFile(p) then !relevantExtensions.contains(p.ext) || ignoreEngine.isIgnored(p, isDir = false)
      else true

    val sources = os.walk(workspacePath, skip = skip)
    val fileGroups = sources.groupBy(_.ext)
    val scalaFiles = fileGroups.getOrElse("scala", Vector.empty)
    val javaFiles = fileGroups.getOrElse("java", Vector.empty)
    logger.info(s"Found files: scala=${scalaFiles.size}, java=${javaFiles.size}")

    sourcesMap.clear()
    (scalaFiles.toSet ++ javaFiles.toSet).foreach(p => sourcesMap.put(p, SourceData.empty))

    // Pass A: index semanticdb DEFINITION occurrences from BSP-provided
    // (sourceRootDir, semanticdbDir) pairs into symbolTable, pair with sources.
    // No workspace-wide .semanticdb walk — only explicit dirs from data.json / BSP compile.
    if (roots.nonEmpty) {
      logger.info(s"Indexing semanticdb from ${roots.size} target root(s)")
      for (root <- roots if os.exists(root.semanticdbDir) && os.exists(root.sourceRootDir)) {
        val semDir = root.semanticdbDir
        val srcRoot = root.sourceRootDir
        val pairs = SemanticdbIndexing.indexSemanticdbDir(semDir, srcRoot, workspacePath, symbolTable)
        pairs.foreach { case (src, semPath) => setSemanticdbPath(src, semPath) }
        logger.info(s"Indexed ${pairs.size} semanticdb-paired source files from ${semDir}")
      }
      val paired = sourcesMap.values().asScala.count(_.semanticdbPath.isDefined)
      logger.info(s"Total semanticdb-paired source files: $paired")
    }

    // Pass B: extract from source AST for files WITHOUT semanticdb
    for (path <- scalaFiles if sourcesMap.get(path).semanticdbPath.isEmpty) {
      logger.debug(s"Extracting definitions from $path")
      try {
        val content = os.read(path)
        val extractor = ScalaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
    }
    for (path <- javaFiles if sourcesMap.get(path).semanticdbPath.isEmpty) {
      logger.debug(s"Extracting definitions from $path")
      try {
        val content = os.read(path)
        val extractor = JavaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
    }

    writeDebugDump()
  }

  /** Debug dump: .basamake/index_sources.txt + symbol_table.txt — which source files
    * are paired with which .semanticdb files, and the full symbol table. Written at
    * initialize AND refreshed after every index state change, so the dump always
    * reflects the latest source list + semanticdb pairing. */
  private def writeDebugDump(): Unit = {
    try {
      val pairs = sourcesMap.entrySet().asScala.flatMap { e =>
        e.getValue.semanticdbPath.map(sem => e.getKey -> sem)
      }.toMap
      val allSources = sourcesMap.keySet().asScala.toSet
      val dump = SemanticdbIndexing.dumpPairs(pairs, allSources, workspacePath)
      val dumpDir = workspacePath / ".basamake"
      os.makeDir.all(dumpDir)
      dumpLock.synchronized {
        os.write.over(dumpDir / "index_sources.txt", dump)
        os.write.over(dumpDir / "symbol_table.txt", symbolTable.all.toVector.sortBy(_.symbol).mkString("\n"), createFolders = true)
      }
    } catch {
      case e: Exception => logger.warn(s"Failed to write index_sources.txt: ${e.getMessage}")
    }
  }

  // ── onDidOpen/Change/Save/Close ──────────────────────────────
  def onDidOpen(path: os.Path): Unit = {
    if isIgnoredWorkspacePath(path) then return
    openFiles.add(path)
    sourcesMap.putIfAbsent(path, SourceData.empty)
    refreshOpenBuffer(path)
  }

  def onDidChange(path: os.Path): Unit = {
    if isIgnoredWorkspacePath(path) then return
    openFiles.add(path)
    refreshOpenBuffer(path)
  }

  def onDidSave(path: os.Path): Unit = {
    if isIgnoredWorkspacePath(path) then return
    openFiles.add(path)
    sourcesMap.putIfAbsent(path, SourceData.empty)
    // re-extract SymbolTable for this path
    symbolTable.removeByPath(path)
    val data = sourcesMap.get(path)
    if (data != null && data.semanticdbPath.isDefined) {
      try {
        val defs = SemanticdbIndexing.parseDefinitions(data.semanticdbPath.get, path)
        defs.foreach(symbolTable.add)
      } catch { case _: Exception => () }
    } else if (path.ext == "scala") {
      val content = try os.read(path) catch { case _ => "" }
      val extractor = ScalaDefinitionsExtractor(symbolTable)
      extractor.extractFromContent(path.last, content, path)
    } else if (path.ext == "java") {
      val content = try os.read(path) catch { case _ => "" }
      val extractor = JavaDefinitionsExtractor(symbolTable)
      extractor.extractFromContent(path.last, content, path)
    }
    refreshOpenBuffer(path)
  }

  def onDidClose(path: os.Path): Unit = {
    // A closed tab keeps its workspace-level state (semanticdbPath); only the
    // open-file occurrences/locals are emptied.
    openFiles.remove(path)
    sourcesMap.computeIfPresent(path, (_, sd) => sd.copy(occurrences = Vector.empty, locals = Vector.empty))
  }

  /** Files removed from disk (watcher delete events, rename old paths).
    * Purges ALL state for them — buffer state and workspace-level state
    * (semanticdb pairing + SymbolTable definitions). */
  def onFilesDeleted(paths: Set[os.Path]): Unit = {
    paths.foreach { path =>
      openFiles.remove(path)
      sourcesMap.remove(path)
      symbolTable.removeByPath(path)
    }
    writeDebugDump()
  }

  /** New source files on disk (watcher create events, rename new paths).
    * Semanticdb pairing arrives with the next compile (invalidate).
    * Gitignored paths are dropped — they must never enter the source list. */
  def onFilesCreated(paths: Set[os.Path]): Unit = {
    val accepted = paths.filterNot(isIgnoredWorkspacePath)
    if (accepted.isEmpty) return
    accepted.foreach(p => sourcesMap.putIfAbsent(p, SourceData.empty))
    writeDebugDump()
  }

  // ── invalidate (BSP compile callback) ────────────────────────

  /** Re-index `.semanticdb` files after a BSP compile.
    * Called from BspConnection.compile's onAfterCompile callback via BspManager.
    * Uses per-target (sourceRootDir, semanticdbDir) pairs for direct URI resolution
    * — no climbing. Additive — does not touch existing per-file paths. */
  def invalidate(roots: List[SemanticdbDirs]): Unit = {
    if (roots.isEmpty) return
    logger.info(s"Invalidating workspace index (${roots.size} semanticdb root(s))")

    for (root <- roots if  os.exists(root.sourceRootDir) && os.exists(root.semanticdbDir)) {
      val srcRoot = root.sourceRootDir
      val semDir = root.semanticdbDir
      val semFiles = os.walk(semDir).filter(_.ext == "semanticdb").toList
      var paired = 0
      for (semPath <- semFiles) {
        if (indexSemanticdbFile(semPath, srcRoot)) paired += 1
      }
      logger.info(s"Invalidated $paired/${semFiles.size} semanticdb files from $semDir")
    }
    writeDebugDump()
  }

  /** Index a single .semanticdb file: parse definitions, pair with source via direct
    * sourceRoot / uri resolution (+ ancestor-climbing fallback), update SymbolTable.
    * @return true if the file was paired with a source */
  private def indexSemanticdbFile(semPath: os.Path, sourceRoot: os.Path): Boolean = {
    try {
      val docs = scala.meta.internal.semanticdb.TextDocuments.parseFrom(os.read.bytes(semPath))
      var paired = false
      for (doc <- docs.documents.toVector if doc.uri.nonEmpty) {
        SemanticdbIndexing.resolveSourcePath(semPath, doc.uri, sourceRoot, workspacePath) match {
          case Some(src) =>
            setSemanticdbPath(src, semPath)
            symbolTable.removeByPath(src)
            SemanticdbIndexing.parseDefinitions(semPath, src).foreach(symbolTable.add)
            if (openFiles.contains(src)) refreshOpenBuffer(src)
            paired = true
          case None =>
            logger.warn(s"No source match for $semPath (uri=${doc.uri}, sourceRoot=$sourceRoot)")
        }
      }
      paired
    } catch {
      case e: Exception => logger.warn(s"Failed to index $semPath: ${e.getMessage}"); false
    }
  }

  private def setSemanticdbPath(src: os.Path, semPath: os.Path): Unit =
    sourcesMap.compute(src, (_, old) => {
      val current = if (old == null) SourceData.empty else old
      current.copy(semanticdbPath = Some(semPath))
    })

  // ── queries ─────────────────────────────────────────────────
  def findSymbolsAt(path: os.Path, line: Int, char: Int): Vector[String] = {
    val result = Vector.newBuilder[String]

    val data = sourcesMap.get(path)
    // Probe ref occurrences (refs only — defs live in SymbolTable / open-file locals)
    val occs = if (data == null) Vector.empty else data.occurrences
    val enclosingRefs = occs.filter(o => isInsideRange(line, char, o.range))
    if (enclosingRefs.nonEmpty) {
      val minLen = enclosingRefs.map(o => rangeLength(o.range)).min
      result ++= enclosingRefs.filter(o => rangeLength(o.range) == minLen).map(_.symbol)
    }

    // Probe local defs (locals have exact range info for cursor-on-def-site)
    val localDefs = if (data == null) Vector.empty else data.locals
    val enclosingLocals = localDefs.filter(ld => isInsideRange(line, char, ld.range))
    result ++= enclosingLocals.map(_.symbol)

    // Probe global defs via SymbolTable for this file
    val globalDefs = symbolTable.byPath(path)
    val enclosingGlobals = globalDefs.filter(sd => isInsideRange(line, char, sd.range))
    result ++= enclosingGlobals.map(_.symbol)

    result.result().distinct
  }

  def gotoDefinitions(path: os.Path, line: Int, char: Int, depCandidates: List[os.Path] = Nil): Vector[SymbolDefinition] = {
    val data = sourcesMap.get(path)
    // All occurrences are references (defs live in SymbolTable / open-file locals).
    val references = if (data == null) Vector.empty else data.occurrences
    val localDefs = if (data == null) Vector.empty else data.locals
    val localDefinitionsMap = localDefs.map(ld => ld.symbol -> ld).toMap

    val referencesUnderCursor = references.filter(o => isInsideRange(line, char, o.range))
    // Cursor on a def site (not a ref) → return empty. "Go to definition" from the
    // definition itself is noise; the user wants references there, not "go to self".
    if (referencesUnderCursor.isEmpty) Vector.empty
    else {
      val local = referencesUnderCursor.flatMap(o => localDefinitionsMap.get(o.symbol))
      val candidates =
        if (local.nonEmpty) local
        else referencesUnderCursor.flatMap(o => getSymbol(o.symbol, depCandidates))
      // Filter out the location the cursor is already on (self-filter for refs).
      candidates.filterNot { sd =>
        sd.path == path && isInsideRange(line, char, sd.range)
      }
    }
  }

  /** v1: scan only occurrences in CURRENTLY OPEN FILES.
    * Cross-workspace references are explicitly out of scope for v1. */
  def references(path: os.Path, line: Int, char: Int, includeDeclaration: Boolean, depCandidates: List[os.Path] = Nil): Vector[SymbolDefinition] = {
    val targetSymbols = findSymbolsAt(path, line, char).toSet
    if (targetSymbols.isEmpty) return Vector.empty

    val results = Vector.newBuilder[SymbolDefinition]

    // Scan ref occurrences across all open files
    for (openPath <- openFiles.asScala) {
      val data = sourcesMap.get(openPath)
      val occs = if (data == null) Vector.empty else data.occurrences
      for (occ <- occs if targetSymbols.contains(occ.symbol)) {
        results += SymbolDefinition(
          symbol = occ.symbol,
          shortName = occ.symbol,
          isType = SymbolUtils.isTypeSymbol(occ.symbol),
          range = occ.range,
          path = openPath
        )
      }
    }

    // If includeDeclaration, append the def site from SymbolTable or locals
    if (includeDeclaration) {
      val openLocals = openFiles.asScala.iterator.flatMap { p =>
        val d = sourcesMap.get(p)
        if (d == null) Iterator.empty else d.locals.iterator
      }.toVector
      for (sym <- targetSymbols) {
        // Try locals first, then SymbolTable (dep lookups scoped to candidates)
        val defOpt = openLocals.find(ld => ld.symbol == sym).orElse(getSymbol(sym, depCandidates))
        defOpt.foreach(d => results += d)
      }
    }

    results.result().distinct
  }

  // ── internal helpers ─────────────────────────────────────────

  /** Symbol lookup with optional dep-jar candidates (the file's BSP target deps).
    * With candidates, dep lookups are scoped to those jars (precise + cheap);
    * without, the plain lookup is used (global dep route / workspace table).
    * Public — used by HoverProvider (hover needs def resolution, including on
    * def sites, where gotoDefinitions deliberately returns empty). */
  def getSymbol(symbol: String, depCandidates: List[os.Path]): Option[SymbolDefinition] =
    if depCandidates.isEmpty then symbolTable.get(symbol)
    else symbolTable match {
      case c: CompositeSymbolTable => c.getWithCandidates(symbol, depCandidates)
      case t                       => t.get(symbol)
    }

  /** Paths of currently open editor files (used by BspManager to index the right
    * targets' deps right after a handshake). */
  def openPaths: Set[os.Path] = openFiles.asScala.toSet

  private def refreshOpenBuffer(path: os.Path): Unit = {
    if (!openFiles.contains(path)) return
    val textOpt = try Some(os.read(path)) catch { case _: Exception => None }
    textOpt match {
      case None => ()
      case Some(text) =>
        val current = sourcesMap.get(path)
        val semPathOpt = if (current == null) None else current.semanticdbPath
        val (occs, locals) = semPathOpt match {
          case Some(semPath) =>
            val res = SemanticdbIndexing.parseOccurrences(semPath, path)
            if (res.complete) {
              (res.occurrences, res.locals)
            } else {
              // Partial -Ybest-effort ref symbols (e.g. `utils.` not `_empty_/utils.`)
              // — fall back to source parsing for occurrences. Defs in SymbolTable
              // are full symbols and stay authoritative.
              logger.debug(s"Semanticdb for $path has short ref symbols — falling back to source parse")
              val rf = sourceResolve(path, text)
              (rf.occurrences, rf.locals)
            }
          case None =>
            logger.debug(s"Resolving references from source for $path")
            val rf = sourceResolve(path, text)
            logger.debug(s"Resolved occurrences from source for $path: ${rf.occurrences}, locals: ${rf.locals}")
            (rf.occurrences, rf.locals)
        }
        sourcesMap.compute(path, (_, old) => {
          val base = if (old == null) SourceData.empty else old
          base.copy(occurrences = occs, locals = locals)
        })
    }
  }

  private def sourceResolve(path: os.Path, text: String): ResolvedFile =
    if (path.ext == "java") {
      val resolver = new JavaReferencesResolver(symbolTable)
      resolver.resolveFromContent(path.last, text, path)
    } else {
      val resolver = new ScalaReferencesResolver(symbolTable)
      resolver.resolveFromContent(path.last, text, path)
    }

  // ── range helpers ────────────────────────────────────────────

  private def isInsideRange(line: Int, char: Int, occurenceRange: Range): Boolean =
    line == occurenceRange.startLine && line == occurenceRange.endLine &&
      occurenceRange.startCharacter <= char && char < occurenceRange.endCharacter 

  private def rangeLength(r: Range): Long =
    (r.endLine.toLong - r.startLine.toLong) * 100000 + (r.endCharacter.toLong - r.startCharacter.toLong)

}
