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

class WorkspaceIndex(workspacePath: os.Path, symbolTable: SymbolTable, ignorePatterns: Vector[String] = Vector.empty, progressListener: IndexingProgressListener = IndexingProgressListener.noop, debugSymbolTableDump: Boolean = false) extends StrictLogging {

  // One map for ALL workspace sources (keyed by path): the keySet IS the live
  // source list (no separate knownSources), semanticdbPath is the pairing,
  // occurrences/locals are the open-file data. ConcurrentHashMap so queries
  // never block on BSP-compile invalidations (no synchronized).
  private val sourcesMap = new ConcurrentHashMap[os.Path, SourceData]()
  // open files — separate set: an open file with zero references must stay "open"
  private val openFiles = ConcurrentHashMap.newKeySet[os.Path]()

  // serialize debug-dump file writes (os.write.over is not atomic)
  private val dumpLock = new Object

  // ── debug dump throttling ─────────────────────────────────────
  // index_sources.txt stays SYNCHRONOUS (cheap, few ms — tests rely on it being
  // fresh after invalidate). symbol_table.txt is the heavy one (serializes the
  // whole symbol table, e.g. 12.9MB for msc-backend) — it used to run on the
  // BSP event thread after EVERY compile; it's now deferred to a background
  // flusher that writes at most once per interval, only when dirty.
  private val DumpFlushIntervalMs = 60_000L
  private val symbolTableDirty = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val dumpFlusherStarted = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Lazy-start the background symbol-table flusher (one virtual thread). */
  private def ensureDumpFlusher(): Unit = {
    if (!dumpFlusherStarted.compareAndSet(false, true)) return
    Thread.ofVirtual().start(() => {
      var running = true
      while (running) {
        try Thread.sleep(DumpFlushIntervalMs)
        catch { case _: InterruptedException => running = false }
        if (running && symbolTableDirty.getAndSet(false)) writeSymbolTableDump()
      }
    })
  }

  /** True while a BSP-compile invalidation is tearing down + rebuilding the
    * symbol table — gotoDefinitions retries once in this window (see below). */
  @volatile private var invalidating = false

  /** disk (mtime, size) at the time of the last buffer refresh — lets onDidChange
    * skip the per-keystroke re-parse: occurrences only depend on DISK content
    * (semanticdb parse or source parse of the file on disk), so an unchanged
    * disk file yields identical occurrences no matter how much the buffer moves. */
  private val diskStamps = new ConcurrentHashMap[os.Path, (Long, Long)]()

  /** .semanticdb path → (mtime, size) of the last successfully indexed file —
    * invalidate skips unchanged files, so a compile no longer re-parses the
    * whole semanticdb output (~1376 files ≈ 1.5s on the BSP thread) when
    * nothing changed. */
  private val semanticdbStamps = new ConcurrentHashMap[os.Path, (Long, Long)]()

  // ── test seams ────────────────────────────────────────────────
  private val semanticdbIndexCount = new java.util.concurrent.atomic.AtomicLong(0)
  private val bufferRefreshCount = new java.util.concurrent.atomic.AtomicLong(0)
  private[navigation] def indexedSemanticdbFiles: Long = semanticdbIndexCount.get()
  private[navigation] def bufferRefreshCountValue: Long = bufferRefreshCount.get()
  private[navigation] def symbolTableDumpDirty: Boolean = symbolTableDirty.get()
  private[navigation] def flushSymbolTableDump(): Unit = {
    symbolTableDirty.set(false)
    writeSymbolTableDump()
  }
  private[navigation] def setInvalidating(v: Boolean): Unit = invalidating = v

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
    val relevantExtensions = Set("scala", "java", "sbt")
    def skip(p: os.Path): Boolean =
      if os.isDir(p) then GitIgnoreEngine.alwaysSkipDirNames.contains(p.last) || ignoreEngine.isIgnored(p, isDir = true)
      else if os.isFile(p) then !relevantExtensions.contains(p.ext) || ignoreEngine.isIgnored(p, isDir = false)
      else true

    val sources = os.walk(workspacePath, skip = skip)
    val fileGroups = sources.groupBy(_.ext)
    val scalaFiles = fileGroups.getOrElse("scala", Vector.empty)
    val sbtFiles = fileGroups.getOrElse("sbt", Vector.empty)
    val javaFiles = fileGroups.getOrElse("java", Vector.empty)
    logger.info(s"Found files: scala=${scalaFiles.size}, sbt=${sbtFiles.size}, java=${javaFiles.size}")

    sourcesMap.clear()
    semanticdbStamps.clear()
    // a file opened between the walk and the clear must not be dropped
    openFiles.forEach(p => sourcesMap.putIfAbsent(p, SourceData.empty))
    val allFiles = scalaFiles.toSet ++ sbtFiles.toSet ++ javaFiles.toSet
    allFiles.foreach(p => sourcesMap.put(p, SourceData.empty))

    val total = allFiles.size.toLong
    var done = 0L
    def report(msg: String): Unit =
      progressListener.onProgress(IndexingPhase.Workspace, done.min(total), total, msg)
    report("scanning workspace")

    // Pass A: index semanticdb DEFINITION occurrences from BSP-provided
    // (sourceRootDir, semanticdbDir) pairs into symbolTable, pair with sources.
    // No workspace-wide .semanticdb walk — only explicit dirs from data.json / BSP compile.
    if (roots.nonEmpty) {
      logger.info(s"Indexing semanticdb from ${roots.size} target root(s)")
      for (root <- roots if os.exists(root.semanticdbDir) && os.exists(root.sourceRootDir)) {
        val semDir = root.semanticdbDir
        val srcRoot = root.sourceRootDir
        if (ignoreEngine.isInsideNestedRepo(srcRoot)) {
          logger.warn(s"Skipping semanticdb root inside nested git repo: $srcRoot")
        } else {
          val pairs = SemanticdbIndexing.indexSemanticdbDir(semDir, srcRoot, workspacePath, symbolTable)
          val (accepted, rejected) = pairs.partition((src, _) => !ignoreEngine.isInsideNestedRepo(src))
          rejected.keySet.foreach { src =>
            logger.warn(s"Source inside nested git repo, skipping semanticdb pair: $src")
            symbolTable.removeByPath(src)
          }
          accepted.foreach { case (src, semPath) => setSemanticdbPath(src, semPath) }
          done += accepted.size.toLong
          report(s"semanticdb ${accepted.size} files")
          logger.info(s"Indexed ${accepted.size} semanticdb-paired source files from ${semDir}")
        }
      }
      val paired = sourcesMap.values().asScala.count(_.semanticdbPath.isDefined)
      logger.info(s"Total semanticdb-paired source files: $paired")
    }

    // Pass B: extract from source AST for files WITHOUT semanticdb
    for (path <- scalaFiles ++ sbtFiles if sourcesMap.get(path).semanticdbPath.isEmpty) {
      logger.debug(s"Extracting definitions from $path")
      try {
        val content = os.read(path)
        val extractor = ScalaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
      done += 1
      report(path.last)
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
      done += 1
      report(path.last)
    }

    report(s"Indexed $total files")
    // Async initialize: files may be opened while indexing runs. The map
    // re-seed above only preserved their PRESENCE — restore their buffer state
    // (occurrences/locals) and prefer semanticdb occurrences now that pairing
    // is done. Without this, goto-def in such tabs returns empty until the
    // user edits or saves the file.
    openFiles.forEach(p => refreshOpenBuffer(p))
    writeDebugDump()
  }

  /** Debug dump: .basamake/index_sources.txt + (opt-in) symbol_table.txt —
    * which source files are paired with which .semanticdb files, and the full
    * symbol table. index_sources.txt is written at initialize AND refreshed
    * after every index state change (cheap). symbol_table.txt is the heavy one
    * (serializes the whole symbol table, e.g. 12.9MB for msc-backend) — it is
    * OPT-IN (`debugSymbolTableDump`), so the default startup path never pays
    * for it. */
  private def writeDebugDump(): Unit = {
    writeIndexSourcesDump()
    if (debugSymbolTableDump) writeSymbolTableDump()
  }

  /** Cheap refresh after index state changes (invalidate / file create+delete):
    * index_sources.txt synchronously (tests + freshness), symbol_table.txt
    * deferred to the throttled background flusher — the full-table serialize
    * (~13MB) must not run on the BSP event thread after every compile. */
  private def refreshDebugDump(): Unit = {
    writeIndexSourcesDump()
    if (debugSymbolTableDump) {
      symbolTableDirty.set(true)
      ensureDumpFlusher()
    }
  }

  private def writeIndexSourcesDump(): Unit = {
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
      }
    } catch {
      case e: Exception => logger.warn(s"Failed to write index_sources.txt: ${e.getMessage}")
    }
  }

  private def writeSymbolTableDump(): Unit = {
    try {
      val dumpDir = workspacePath / ".basamake"
      os.makeDir.all(dumpDir)
      dumpLock.synchronized {
        os.write.over(dumpDir / "symbol_table.txt", symbolTable.all.toVector.sortBy(_.symbol).mkString("\n"), createFolders = true)
      }
    } catch {
      case e: Exception => logger.warn(s"Failed to write symbol_table.txt: ${e.getMessage}")
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
    // Occurrences only depend on DISK content — skip the re-parse while the
    // disk file is unchanged (typing = no refresh; the old code re-parsed the
    // whole file's semanticdb occurrences on EVERY keystroke, serializing the
    // single lsp4j message thread).
    if (diskStamps.get(path) != diskStampOf(path)) refreshOpenBuffer(path)
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
    } else if (path.ext == "scala" || path.ext == "sbt") {
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
      diskStamps.remove(path)
      symbolTable.removeByPath(path)
    }
    refreshDebugDump()
  }

  /** New source files on disk (watcher create events, rename new paths).
    * Semanticdb pairing arrives with the next compile (invalidate).
    * Gitignored paths are dropped — they must never enter the source list. */
  def onFilesCreated(paths: Set[os.Path]): Unit = {
    val accepted = paths.filterNot(isIgnoredWorkspacePath)
    if (accepted.isEmpty) return
    accepted.foreach(p => sourcesMap.putIfAbsent(p, SourceData.empty))
    refreshDebugDump()
  }

  // ── invalidate (BSP compile callback) ────────────────────────

  /** Re-index `.semanticdb` files after a BSP compile.
    * Called from BspConnection.compile's onAfterCompile callback via BspManager.
    * Uses per-target (sourceRootDir, semanticdbDir) pairs for direct URI resolution
    * — no climbing. Additive — does not touch existing per-file paths. */
  def invalidate(roots: List[SemanticdbDirs]): Unit = {
    if (roots.isEmpty) return
    logger.info(s"Invalidating workspace index (${roots.size} semanticdb root(s))")
    invalidating = true
    try {
      for (root <- roots if os.exists(root.sourceRootDir) && os.exists(root.semanticdbDir) && !ignoreEngine.isInsideNestedRepo(root.sourceRootDir)) {
        val srcRoot = root.sourceRootDir
        val semDir = root.semanticdbDir
        val semFiles = os.walk(semDir).filter(_.ext == "semanticdb").toList
        var paired = 0
        for (semPath <- semFiles) {
          // skip files unchanged since their last successful index — a compile
          // rewrites ALL semanticdb files, but only the changed ones matter
          if (semanticdbStamps.get(semPath) != stampOf(semPath)) {
            if (indexSemanticdbFile(semPath, srcRoot)) paired += 1
          }
        }
        logger.info(s"Invalidated $paired/${semFiles.size} semanticdb files from $semDir")
      }
    } finally {
      invalidating = false
    }
    refreshDebugDump()
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
          case Some(src) if !ignoreEngine.isInsideNestedRepo(src) =>
            setSemanticdbPath(src, semPath)
            symbolTable.removeByPath(src)
            SemanticdbIndexing.parseDefinitions(semPath, src).foreach(symbolTable.add)
            if (openFiles.contains(src)) refreshOpenBuffer(src)
            paired = true
          case Some(src) =>
            logger.warn(s"Source inside nested git repo, skipping semanticdb pair: $src")
          case None =>
            logger.warn(s"No source match for $semPath (uri=${doc.uri}, sourceRoot=$sourceRoot)")
        }
      }
      // only record the stamp after a successful parse — a transient failure
      // stays un-stamped and is retried on the next invalidate
      semanticdbStamps.put(semPath, stampOf(semPath))
      semanticdbIndexCount.incrementAndGet()
      paired
    } catch {
      case e: Exception => logger.warn(s"Failed to index $semPath: ${e.getMessage}"); false
    }
  }

  private def stampOf(p: os.Path): (Long, Long) =
    try (os.mtime(p), os.size(p)) catch { case _: Exception => (-1L, -1L) }

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
    // An in-flight invalidation tears down + rebuilds the symbol table per file
    // on the BSP thread; a lookup landing in that window can transiently return
    // empty. Retry briefly (only when refs were found but resolution came up
    // empty — a cursor on a def site must NOT trigger the wait).
    var result = resolveDefinitions(path, line, char, depCandidates)
    var attempts = 0
    while (result.contains(Vector.empty) && invalidating && attempts < 20) {
      Thread.sleep(50)
      result = resolveDefinitions(path, line, char, depCandidates)
      attempts += 1
    }
    result.getOrElse(Vector.empty)
  }

  /** One-shot resolution: None = no refs under the cursor (def site), Some =
    * resolution outcome (possibly empty — symbol not found yet). */
  private def resolveDefinitions(path: os.Path, line: Int, char: Int, depCandidates: List[os.Path]): Option[Vector[SymbolDefinition]] = {
    val data = sourcesMap.get(path)
    // All occurrences are references (defs live in SymbolTable / open-file locals).
    val references = if (data == null) Vector.empty else data.occurrences
    val localDefs = if (data == null) Vector.empty else data.locals
    val localDefinitionsMap = localDefs.map(ld => ld.symbol -> ld).toMap

    val referencesUnderCursor = references.filter(o => isInsideRange(line, char, o.range))
    // Cursor on a def site (not a ref) → return empty. "Go to definition" from the
    // definition itself is noise; the user wants references there, not "go to self".
    if (referencesUnderCursor.isEmpty) None
    else {
      val local = referencesUnderCursor.flatMap(o => localDefinitionsMap.get(o.symbol))
      val candidates =
        if (local.nonEmpty) local
        else referencesUnderCursor.flatMap(o => getSymbol(o.symbol, depCandidates))
      // Filter out the location the cursor is already on (self-filter for refs).
      Some(candidates.filterNot { sd =>
        sd.path == path && isInsideRange(line, char, sd.range)
      })
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
        diskStamps.put(path, diskStampOf(path))
        bufferRefreshCount.incrementAndGet()
    }
  }

  private def diskStampOf(p: os.Path): (Long, Long) =
    try (os.mtime(p), os.size(p)) catch { case _: Exception => (-1L, -1L) }

  private def sourceResolve(path: os.Path, text: String): ResolvedFile =
    if (path.ext == "java") {
      val resolver = new JavaReferencesResolver(symbolTable)
      resolver.resolveFromContent(path.last, text, path)
    } else {
      val resolver = new ScalaReferencesResolver(symbolTable)
      resolver.resolveFromContent(path.last, text, path)
    }

  // ── range helpers ────────────────────────────────────────────

  /** True when (line, char) is inside `r`, with END-EXCLUSIVE semantics:
    * `r.start <= cursor < r.end`. Multiline-safe: a cursor on any middle line
    * of a multiline range is inside, and the end position itself is outside. */
  private def isInsideRange(line: Int, char: Int, r: Range): Boolean = {
    val afterStart = line > r.startLine || (line == r.startLine && char >= r.startCharacter)
    val beforeEnd = line < r.endLine || (line == r.endLine && char < r.endCharacter)
    afterStart && beforeEnd
  }

  private def rangeLength(r: Range): Long =
    (r.endLine.toLong - r.startLine.toLong) * 100000 + (r.endCharacter.toLong - r.startCharacter.toLong)

}
