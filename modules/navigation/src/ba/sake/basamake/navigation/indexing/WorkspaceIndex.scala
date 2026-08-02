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
  private val openBuffers = mutable.Map.empty[os.Path, String]
  private val openOccurrences = mutable.Map.empty[os.Path, Vector[ReferenceOccurrence]]
  private val openLocalDefinitions = mutable.Map.empty[os.Path, Vector[SymbolDefinition]]
  private val dirty = mutable.Set.empty[os.Path]

  // ── initialize ──────────────────────────────────────────────
  def initialize(semanticdbDirs: List[String] = Nil): Unit = synchronized {
    logger.info(s"Initializing workspace index at $workspacePath")
    val skipDirNames = Set(".git", ".basamake", ".metals", ".bsp", "node_modules")
    val relevantExtensions = Set("scala", "java", "semanticdb")
    val skip: os.Path => Boolean = p => {
      if (os.isDir(p)) skipDirNames.contains(p.last) else if (os.isFile(p)) !relevantExtensions.contains(p.ext) else true
    }

    val sources = os.walk(workspacePath, skip = skip)
    val fileGroups = sources.groupBy(_.ext)
    val scalaFiles = fileGroups.getOrElse("scala", Vector.empty)
    val javaFiles = fileGroups.getOrElse("java", Vector.empty)
    val semanticdbFiles = fileGroups.getOrElse("semanticdb", Vector.empty)
    val scalaJavaFiles = scalaFiles.toSet ++ javaFiles.toSet
    logger.info(s"Found files: scala=${scalaFiles.size}, java=${javaFiles.size}, semanticdb=${semanticdbFiles.size}")

    // Pass A: index semanticdb DEFINITION occurrences into symbolTable, pair with sources
    val semPairs = SemanticdbIndexing.matchSemanticdbWithSources(
      semanticdbFiles, workspacePath, scalaJavaFiles, symbolTable
    )
    semanticdbBySource.clear()
    semanticdbBySource.addAll(semPairs)
    logger.info(s"Indexed ${semPairs.size} semanticdb-paired source files")

    // Pass B: extract from source AST for files WITHOUT semanticdb
    for (path <- scalaFiles if !semPairs.contains(path)) {
      logger.debug(s"Extracting definitions from $path")
      try {
        val content = os.read(path)
        val extractor = ScalaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
    }
    for (path <- javaFiles if !semPairs.contains(path)) {
      logger.debug(s"Extracting definitions from $path")
      try {
        val content = os.read(path)
        val extractor = JavaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
    }

    // Pass C: index semanticdb files from explicit dirs (data.json) that workspace walk missed
    if (semanticdbDirs.nonEmpty) {
      logger.info(s"Indexing ${semanticdbDirs.size} supplemental semanticdb dirs from data.json")
      val semFilesToIndex = semanticdbDirs.flatMap { uri =>
        val dirPath = try os.Path(java.net.URI.create(uri))
          catch { case _: Exception => try os.Path(uri) catch { case _: Exception => null } }
        if (dirPath != null && os.isDir(dirPath))
          os.walk(dirPath).filter(_.ext == "semanticdb").toList
        else Nil
      }
      for (semPath <- semFilesToIndex) {
        indexSemanticdbFile(semPath)
      }
      logger.info(s"Supplemental semanticdb indexing complete: ${semFilesToIndex.size} files")
    }

    // Debug dump: write .basamake/index.txt in the workspace root so users can inspect
    // which source files were paired with which .semanticdb files.
    try {
      val dump = SemanticdbIndexing.dumpPairs(semanticdbBySource.toMap, scalaJavaFiles, workspacePath)
      val dumpDir = workspacePath / ".basamake"
      os.makeDir.all(dumpDir)
      os.write.over(dumpDir / "index.txt", dump)
    } catch {
      case e: Exception => logger.warn(s"Failed to write index.txt: ${e.getMessage}")
    }
  }

  // ── onDidOpen/Change/Save/Close ──────────────────────────────
  def onDidOpen(path: os.Path, text: String): Unit = synchronized {
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
    * Walks semanticdbDirs preferentially (from scalacOptions); falls back to
    * workspace-wide walk when semanticdbDirs is empty. Additive — does not touch
    * existing per-file paths. */
  def invalidate(sourceDirs: List[String], semanticdbDirs: List[String]): Unit = synchronized {
    if (sourceDirs.isEmpty && semanticdbDirs.isEmpty) return
    logger.info(s"Invalidating workspace index (${semanticdbDirs.size} semanticdb dirs, ${sourceDirs.size} source dirs)")

    // Collect .semanticdb files to (re-)index
    val semFilesToIndex = if (semanticdbDirs.nonEmpty) {
      // Fast path: walk only the known semanticdb dirs
      semanticdbDirs.flatMap { uri =>
        val dirPath = try os.Path(java.net.URI.create(uri))
          catch { case _: Exception => try os.Path(uri) catch { case _: Exception => null } }
        if (dirPath != null && os.isDir(dirPath))
          os.walk(dirPath).filter(_.ext == "semanticdb").toList
        else Nil
      }
    } else {
      // Fallback: walk source dirs for .semanticdb (backward compat)
      sourceDirs.flatMap { uri =>
        val dirPath = try os.Path(java.net.URI.create(uri))
          catch { case _: Exception => try os.Path(uri) catch { case _: Exception => null } }
        if (dirPath != null && os.isDir(dirPath))
          os.walk(dirPath, skip = p => os.isDir(p) && p.last == ".git" || p.last == ".basamake")
            .filter(_.ext == "semanticdb").toList
        else Nil
      }
    }

    for (semPath <- semFilesToIndex) {
      indexSemanticdbFile(semPath)
    }
  }

  /** Index a single .semanticdb file: parse definitions, pair with source, update SymbolTable. */
  private def indexSemanticdbFile(semPath: os.Path): Unit = {
    try {
      val docs = scala.meta.internal.semanticdb.TextDocuments.parseFrom(os.read.bytes(semPath))
      for (doc <- docs.documents.toVector if doc.uri.nonEmpty) {
        val uriStr = if (doc.uri.startsWith("/")) doc.uri.drop(1) else doc.uri
        val rel = os.RelPath(uriStr)
        var ancestor: os.Path = semPath / os.up
        var found: Option[os.Path] = None
        var keepGoing = true
        while (found.isEmpty && keepGoing) {
          val candidate = ancestor / rel
          if (os.isFile(candidate)) found = Some(candidate)
          else if (ancestor == os.root) keepGoing = false
          else ancestor = ancestor / os.up
        }
        found.foreach { src =>
          symbolTable.removeByPath(src)
          semanticdbBySource(src) = semPath
          SemanticdbIndexing.parseDefinitions(semPath, src).foreach(symbolTable.add)
          if (openBuffers.contains(src)) refreshOpenBuffer(src)
        }
      }
    } catch {
      case e: Exception => logger.warn(s"Failed to index $semPath: ${e.getMessage}")
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
            val useSemanticdb = !dirty.contains(path) && textMatchesDisk(path, text) && semanticdbIsFresh(path, semanticdbBySource(path))
            if (useSemanticdb) {
              val res = SemanticdbIndexing.parseOccurrences(semanticdbBySource(path), path)
              (res.occurrences, res.locals)
            } else {
              val resolver = ScalaReferencesResolver(symbolTable)
              val rf = resolver.resolveFromContent(path.last, text, path)
              (rf.occurrences, rf.locals)
            }
          } else {
            logger.debug(s"Resolving references from source for $path")
            val rf =
              if (path.ext == "java") {
                val resolver = new JavaReferencesResolver(symbolTable)
                resolver.resolveFromContent(path.last, text, path)
              } else {
                val resolver = new ScalaReferencesResolver(symbolTable)
                resolver.resolveFromContent(path.last, text, path)
              }
            val res = (rf.occurrences, rf.locals)
            logger.debug(s"Resolved occurrences from source for $path: ${res._1}, locals: ${res._2}")
            res
          }
        openOccurrences(path) = occs
        openLocalDefinitions(path) = locals
        dirty.remove(path)
    }
  }

  private def textMatchesDisk(path: os.Path, text: String): Boolean = {
    try os.read(path) == text catch { case _ => false }
  }

  private def semanticdbIsFresh(sourcePath: os.Path, semanticdbPath: os.Path): Boolean = {
    try {
      val srcMtime = os.mtime(sourcePath)
      val semMtime = os.mtime(semanticdbPath)
      semMtime >= srcMtime
    } catch { case _: Exception => false }
  }

  // ── range helpers ────────────────────────────────────────────

  private def isInsideRange(line: Int, char: Int, occurenceRange: Range): Boolean =
    line == occurenceRange.startLine && line == occurenceRange.endLine &&
      occurenceRange.startCharacter <= char && char < occurenceRange.endCharacter 

  private def rangeLength(r: Range): Long =
    (r.endLine.toLong - r.startLine.toLong) * 100000 + (r.endCharacter.toLong - r.startCharacter.toLong)

}

