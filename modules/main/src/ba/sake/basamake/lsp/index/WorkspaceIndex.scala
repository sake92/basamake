package ba.sake.basamake.lsp.index

import scala.collection.mutable
import com.typesafe.scalalogging.StrictLogging
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.navigation.*
import ba.sake.basamake.navigation.scalasrc.{ScalaDefinitionsExtractor, ScalaReferencesResolver }
import ba.sake.basamake.navigation.javasrc.{JavaDefinitionsExtractor, JavaReferencesResolver}

class WorkspaceIndex(symbolTable: SymbolTable) extends StrictLogging {

  // source path → .semanticdb path (built once at initialize)
  private val semanticdbBySource = mutable.Map.empty[os.Path, os.Path]

  // open buffer state
  private val openBuffers = mutable.Map.empty[os.Path, String]
  private val openOccurrences = mutable.Map.empty[os.Path, Vector[ReferenceOccurrence]]
  private val openLocalDefinitions = mutable.Map.empty[os.Path, Vector[SymbolDefinition]]
  private val dirty = mutable.Set.empty[os.Path]

  // ── initialize ──────────────────────────────────────────────
  def initialize(workspaceRoot: os.Path): Unit = synchronized {
    logger.info(s"Initializing workspace index at $workspaceRoot")
    val skipDirNames = Set(".git", ".basamake", ".metals", ".bsp", "node_modules")
    val relevantExtensions = Set("scala", "java", "semanticdb")
    val skip: os.Path => Boolean = p => {
      if (os.isDir(p)) skipDirNames.contains(p.last) else if (os.isFile(p)) !relevantExtensions.contains(p.ext) else true
    }

    val sources = os.walk(workspaceRoot, skip = skip)
    val fileGroups = sources.groupBy(_.ext)
    val scalaFiles = fileGroups.getOrElse("scala", Vector.empty)
    val javaFiles = fileGroups.getOrElse("java", Vector.empty)
    val semanticdbFiles = fileGroups.getOrElse("semanticdb", Vector.empty)
    val scalaJavaFiles = scalaFiles.toSet ++ javaFiles.toSet
    logger.info(s"Found files: scala=${scalaFiles.size}, java=${javaFiles.size}, semanticdb=${semanticdbFiles.size}")

    // Pass A: index semanticdb DEFINITION occurrences into symbolTable, pair with sources
    val semPairs = SemanticdbIndexing.matchSemanticdbWithSources(
      semanticdbFiles, workspaceRoot, scalaJavaFiles, symbolTable
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

    logger.info(s"Initial symbol table:\n${symbolTable.all.mkString("\n")}")

    // Debug dump: write .basamake/index.txt in the workspace root so users can inspect
    // which source files were paired with which .semanticdb files.
    try {
      val dump = SemanticdbIndexing.dumpPairs(semanticdbBySource.toMap, scalaJavaFiles, workspaceRoot)
      val dumpDir = workspaceRoot / ".basamake"
      os.makeDir.all(dumpDir)
      os.write.over(dumpDir / "index.txt", dump)
      logger.info(s"Wrote index dump to ${dumpDir / "index.txt"}")
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
            val useSemanticdb = !dirty.contains(path) && textMatchesDisk(path, text)
            if (useSemanticdb) {
              // parseOccurrences returns (occs, complete). Under Scala 3 -Ybest-effort the
              // semanticdb emits short/unresolved ref symbols (e.g. `utils.` not `_empty_/utils.`);
              // in that case fall back to source parsing for the ref occurrences (defs in
              // SymbolTable are still full symbols and stay authoritative).
              val (semOccs, complete) = try SemanticdbIndexing.parseOccurrences(semanticdbBySource(path))
                         catch { case e: Exception => logger.warn(s"Failed to parse semanticdb $path: ${e.getMessage}"); (Vector.empty[ReferenceOccurrence], false) }
              if (complete) (semOccs, Vector.empty[SymbolDefinition])
              else {
                logger.debug(s"Semanticdb for $path has short ref symbols, falling back to source parse")
                val resolver = ScalaReferencesResolver(symbolTable)
                val rf = resolver.resolveFromContent(path.last, text, path)
                (rf.occurrences, rf.locals)
              }
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

  // ── range helpers ────────────────────────────────────────────

  private def isInsideRange(line: Int, char: Int, occurenceRange: Range): Boolean =
    line == occurenceRange.startLine && line == occurenceRange.endLine &&
      occurenceRange.startCharacter <= char && char < occurenceRange.endCharacter 

  private def rangeLength(r: Range): Long =
    (r.endLine.toLong - r.startLine.toLong) * 100000 + (r.endCharacter.toLong - r.startCharacter.toLong)

}

