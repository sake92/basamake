package ba.sake.basamake.navigation.indexing

import scala.collection.mutable
import com.typesafe.scalalogging.StrictLogging
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.navigation.*
import ba.sake.basamake.navigation.scalasrc.{ScalaDefinitionsExtractor, ScalaReferencesResolver }
import ba.sake.basamake.navigation.javasrc.{JavaDefinitionsExtractor, JavaReferencesResolver}

class WorkspaceIndex(workspacePath: os.Path, symbolTable: SymbolTable) extends StrictLogging {

  // source path → .semanticdb path (built once at initialize)
  private val semanticdbBySource = mutable.Map.empty[os.Path, os.Path]

  // open buffer state
  // TODO store just open files ??
  private val openBuffers = mutable.Map.empty[os.Path, String]
  private val openOccurrences = mutable.Map.empty[os.Path, Vector[ReferenceOccurrence]]
  private val openLocalDefinitions = mutable.Map.empty[os.Path, Vector[SymbolDefinition]]
  private val dirty = mutable.Set.empty[os.Path]

  // workspace sources discovered at initialize — reused for the debug dump refresh
  private var knownSources: Set[os.Path] = Set.empty

  // ── initialize ──────────────────────────────────────────────
  def initialize(roots: List[SemanticdbDirs]): Unit = synchronized {
    logger.info(s"Initializing workspace index at $workspacePath")
    val skipDirNames = Set(".git", ".basamake", ".metals", ".bsp", "node_modules")
    val relevantExtensions = Set("scala", "java")
    def skip(p: os.Path): Boolean =
      if os.isDir(p) then skipDirNames.contains(p.last)
      else if os.isFile(p) then !relevantExtensions.contains(p.ext)
      else true

    val sources = os.walk(workspacePath, skip = skip)
    val fileGroups = sources.groupBy(_.ext)
    val scalaFiles = fileGroups.getOrElse("scala", Vector.empty)
    val javaFiles = fileGroups.getOrElse("java", Vector.empty)
    val scalaJavaFiles = scalaFiles.toSet ++ javaFiles.toSet
    knownSources = scalaJavaFiles
    logger.info(s"Found files: scala=${scalaFiles.size}, java=${javaFiles.size}")

    // Pass A: index semanticdb DEFINITION occurrences from BSP-provided
    // (sourceRootDir, semanticdbDir) pairs into symbolTable, pair with sources.
    // No workspace-wide .semanticdb walk — only explicit dirs from data.json / BSP compile.
    semanticdbBySource.clear()
    if (roots.nonEmpty) {
      logger.info(s"Indexing semanticdb from ${roots.size} target root(s)")
      for (root <- roots if os.exists(root.semanticdbDir) && os.exists(root.sourceRootDir)) {
        val semDir = root.semanticdbDir
        val srcRoot = root.sourceRootDir
        val pairs = SemanticdbIndexing.indexSemanticdbDir(semDir, srcRoot, workspacePath, symbolTable)
        semanticdbBySource.addAll(pairs)
        logger.info(s"Indexed ${pairs.size} semanticdb-paired source files from ${semDir}")
      }
      logger.info(s"Total semanticdb-paired source files: ${semanticdbBySource.size}")
    }
    

    // Pass B: extract from source AST for files WITHOUT semanticdb
    for (path <- scalaFiles if !semanticdbBySource.contains(path)) {
      logger.debug(s"Extracting definitions from $path")
      try {
        val content = os.read(path)
        val extractor = ScalaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
    }
    for (path <- javaFiles if !semanticdbBySource.contains(path)) {
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
    * initialize AND refreshed after every invalidate (BSP compile), so the dump
    * always reflects the latest semanticdb pairing. */
  private def writeDebugDump(): Unit = {
    try {
      val dump = SemanticdbIndexing.dumpPairs(semanticdbBySource.toMap, knownSources, workspacePath)
      val dumpDir = workspacePath / ".basamake"
      os.makeDir.all(dumpDir)
      os.write.over(dumpDir / "index_sources.txt", dump)
      os.write.over(dumpDir / "symbol_table.txt", symbolTable.all.toVector.sortBy(_.symbol).mkString("\n"), createFolders = true)
    } catch {
      case e: Exception => logger.warn(s"Failed to write index_sources.txt: ${e.getMessage}")
    }
  }

  // ── onDidOpen/Change/Save/Close ──────────────────────────────
  def onDidOpen(path: os.Path): Unit = synchronized {
    val text = try os.read(path) catch { case _: Exception => return }
    openBuffers(path) = text
    refreshOpenBuffer(path)
    dirty.remove(path)
  }

  def onDidChange(path: os.Path, text: String): Unit = synchronized {
    openBuffers(path) = text
    dirty.add(path)
    refreshOpenBuffer(path)
  }

  def onDidSave(path: os.Path, text: Option[String]): Unit = synchronized {
    text.foreach(openBuffers(path) = _)
    dirty.remove(path)
    // re-extract SymbolTable for this path
    symbolTable.removeByPath(path)
    if (semanticdbBySource.contains(path)) {
      try {
        val defs = SemanticdbIndexing.parseDefinitions(semanticdbBySource(path), path)
        defs.foreach(symbolTable.add)
      } catch { case _: Exception => () }
    } else if (path.ext == "scala") {
      val content = text.getOrElse(try os.read(path) catch { case _ => "" })
      val extractor = ScalaDefinitionsExtractor(symbolTable)
      extractor.extractFromContent(path.last, content, path)
    } else if (path.ext == "java") {
      val content = text.getOrElse(try os.read(path) catch { case _ => "" })
      val extractor = JavaDefinitionsExtractor(symbolTable)
      extractor.extractFromContent(path.last, content, path)
    }
    refreshOpenBuffer(path)
  }

  def onDidClose(path: os.Path): Unit = synchronized {
    openBuffers.remove(path)
    openOccurrences.remove(path)
    openLocalDefinitions.remove(path)
    dirty.remove(path)
    semanticdbBySource.remove(path)
    symbolTable.removeByPath(path)
  }

  // ── invalidate (BSP compile callback) ────────────────────────

  /** Re-index `.semanticdb` files after a BSP compile.
    * Called from BspConnection.compile's onAfterCompile callback via BspManager.
    * Uses per-target (sourceRootDir, semanticdbDir) pairs for direct URI resolution
    * — no climbing. Additive — does not touch existing per-file paths. */
  def invalidate(roots: List[SemanticdbDirs]): Unit = synchronized {
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
            symbolTable.removeByPath(src)
            semanticdbBySource(src) = semPath
            SemanticdbIndexing.parseDefinitions(semPath, src).foreach(symbolTable.add)
            if (openBuffers.contains(src)) refreshOpenBuffer(src)
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

  // ── queries ─────────────────────────────────────────────────
  def findSymbolsAt(path: os.Path, line: Int, char: Int): Vector[String] = synchronized {
    val result = Vector.newBuilder[String]

    // Probe ref occurrences (refs only — defs live in SymbolTable / openLocalDefinitions)
    val occs = openOccurrences.getOrElse(path, Vector.empty)
    val enclosingRefs = occs.filter(o => isInsideRange(line, char, o.range))
    if (enclosingRefs.nonEmpty) {
      val minLen = enclosingRefs.map(o => rangeLength(o.range)).min
      result ++= enclosingRefs.filter(o => rangeLength(o.range) == minLen).map(_.symbol)
    }

    // Probe local defs (locals have exact range info for cursor-on-def-site)
    val localDefs = openLocalDefinitions.getOrElse(path, Vector.empty)
    val enclosingLocals = localDefs.filter(ld => isInsideRange(line, char, ld.range))
    result ++= enclosingLocals.map(_.symbol)

    // Probe global defs via SymbolTable for this file
    val globalDefs = symbolTable.byPath(path)
    val enclosingGlobals = globalDefs.filter(sd => isInsideRange(line, char, sd.range))
    result ++= enclosingGlobals.map(_.symbol)

    result.result().distinct
  }

  def gotoDefinitions(path: os.Path, line: Int, char: Int): Vector[SymbolDefinition] = synchronized {
    val occurrences = openOccurrences.getOrElse(path, Vector.empty)
    // All occurrences in openOccurrences are references (defs live in SymbolTable / openLocalDefinitions).
    val references = occurrences
    val localDefs = openLocalDefinitions.getOrElse(path, Vector.empty)
    val localDefinitionsMap = localDefs.map(ld => ld.symbol -> ld).toMap

    val referencesUnderCursor = references.filter(o => isInsideRange(line, char, o.range))
    // Cursor on a def site (not a ref) → return empty. "Go to definition" from the
    // definition itself is noise; the user wants references there, not "go to self".
    if (referencesUnderCursor.isEmpty) Vector.empty
    else {
      val local = referencesUnderCursor.flatMap(o => localDefinitionsMap.get(o.symbol))
      val candidates =
        if (local.nonEmpty) local
        else referencesUnderCursor.flatMap(o => symbolTable.get(o.symbol))
      // Filter out the location the cursor is already on (self-filter for refs).
      candidates.filterNot { sd =>
        sd.path == path && isInsideRange(line, char, sd.range)
      }
    }
  }

  /** v1: scan only occurrences in CURRENTLY OPEN FILES.
    * Cross-workspace references are explicitly out of scope for v1. */
  def references(path: os.Path, line: Int, char: Int, includeDeclaration: Boolean): Vector[SymbolDefinition] = synchronized {
    val targetSymbols = findSymbolsAt(path, line, char).toSet
    if (targetSymbols.isEmpty) return Vector.empty

    val results = Vector.newBuilder[SymbolDefinition]

    // Scan ref occurrences across all open files
    for (openPath <- openOccurrences.keys) {
      val occs = openOccurrences(openPath)
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
      for (sym <- targetSymbols) {
        // Try locals first, then SymbolTable
        val defOpt = openLocalDefinitions.values.flatten.find(ld => ld.symbol == sym)
          .orElse(symbolTable.get(sym))
        defOpt.foreach(d => results += d)
      }
    }

    results.result().distinct
  }

  // ── internal helpers ─────────────────────────────────────────

  private def refreshOpenBuffer(path: os.Path): Unit = synchronized {
    val textOpt = openBuffers.get(path)
    textOpt match {
      case None => ()
      case Some(text) =>
        val (occs, locals) =
          if (semanticdbBySource.contains(path)) {
            val res = SemanticdbIndexing.parseOccurrences(semanticdbBySource(path), path)
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
          } else {
            logger.debug(s"Resolving references from source for $path")
            val rf = sourceResolve(path, text)
            logger.debug(s"Resolved occurrences from source for $path: ${rf.occurrences}, locals: ${rf.locals}")
            (rf.occurrences, rf.locals)
          }
        openOccurrences(path) = occs
        openLocalDefinitions(path) = locals
        dirty.remove(path)
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

