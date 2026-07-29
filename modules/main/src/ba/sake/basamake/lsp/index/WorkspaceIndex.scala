package ba.sake.basamake.lsp.index

import scala.collection.mutable
import com.typesafe.scalalogging.StrictLogging
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.navigation.*
import ba.sake.basamake.navigation.scalasrc.{ScalaDefinitionsExtractor, ScalaReferencesResolver }
import ba.sake.basamake.navigation.javasrc.JavaDefinitionsExtractor

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
    // Pass A: build semanticdbBySource
    // TODO load semanticdb symbol definitions into symbolTable
    /*val discovered = SemanticdbIndexing.discoverSemanticdbSources(workspaceRoot)
    semanticdbBySource.clear()
    semanticdbBySource.addAll(discovered)
    logger.info(s"Discovered ${discovered.size} source files with semanticdb")

    // TODO make semanticdb + .scala os.walk once
    // Pass B: populate SymbolTable
    //   B1: from semanticdb DEFINITION occurrences
    for (sourcePath <- semanticdbBySource.keys) {
      val semPath = semanticdbBySource(sourcePath)
      try {
        val defs = SemanticdbIndexing.parseDefinitions(semPath, sourcePath)
        defs.foreach { sd =>
          symbolTable.add(sd)
        }
      } catch {
        case e: Exception => logger.warn(s"Failed to parse $semPath: ${e.getMessage}")
      }
    }*/

    //   B2: from source-AST for files WITHOUT semanticdb
    // TODO this would miss generatedSources in .deder and target/..
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
    logger.info(s"Found files: scala=${scalaFiles.size}, java=${javaFiles.size}, semanticdb=${semanticdbFiles.size}")
    // TODO load semanticdb symbol definitions into symbolTable
    // then load only scala/java files that don't have semanticdb
    for (path <- scalaFiles) {
      logger.debug(s"Extracting definitions from $path")
      try {
        val content = os.read(path)
        val extractor = ScalaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
    }
    for (path <- javaFiles) {
      logger.debug(s"Extracting definitions from $path")
      try {
        val content = os.read(path)
        val extractor = JavaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
    }

    

    // Pass C: stand-in fix-up for sentinel ranges
    // TODO check if necessary..
    /*var changed = true
    var iterations = 0
    while (changed && iterations < 8) {
      changed = false
      iterations += 1
      for (sd <- symbolTable.all if isSentinel(sd.range)) {
        SemanticdbIndexing.ownerStandIn(sd.symbol, symbolTable).foreach { standIn =>
          symbolTable.add(sd.copy(range = standIn.range))
          changed = true
        }
      }
    }*/
    logger.info(s"Initial symbol table:\n${symbolTable.all.mkString("\n")}")
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
      fixStandInsForPath(path)
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
    val references = occurrences.filter(_.isDefinition == false)
    val localDefinitionsMap = openLocalDefinitions.getOrElse(path, Vector.empty).map(ld => ld.symbol -> ld).toMap

    val referencesUnderCursor = references.filter(o => isInsideRange(line, char, o.range))
    if (referencesUnderCursor.nonEmpty) {
      val localDefinitions = referencesUnderCursor.flatMap(o => localDefinitionsMap.get(o.symbol))
      if (localDefinitions.nonEmpty) localDefinitions
      else {
        val globalDefinitions = referencesUnderCursor.flatMap(o => symbolTable.get(o.symbol))
        globalDefinitions
      }
    } else {
      // Cursor not on a ref — might be on a def site. Use findSymbolsAt as fallback.
      val targetSymbols = findSymbolsAt(path, line, char)
      targetSymbols.flatMap { sym =>
        openLocalDefinitions.getOrElse(path, Vector.empty).find(_.symbol == sym)
          .orElse(symbolTable.get(sym))
      }
    }
  }

  /** v1: scan only occurrences in CURRENTLY OPEN FILES.
    * Cross-workspace references are explicitly out of scope for v1. */
  def references(path: os.Path, line: Int, char: Int, includeDeclaration: Boolean): Vector[SymbolDefinition] = synchronized {
    val targetSymbols = findSymbolsAt(path, line, char).toSet
    if (targetSymbols.isEmpty) return Vector.empty

    val results = Vector.newBuilder[SymbolDefinition]

    // Scan ref occurrences across all open files (refs only — no isDefinition filter needed)
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
              val occs = try SemanticdbIndexing.parseOccurrences(semanticdbBySource(path))
                         catch { case e: Exception => logger.warn(s"Failed to parse semanticdb $path: ${e.getMessage}"); Vector.empty }
              (occs, Vector.empty[SymbolDefinition])
            } else {
              val resolver = ScalaReferencesResolver(symbolTable)
              val rf = resolver.resolveFromContent(path.last, text, path)
              (rf.occurrences, rf.locals)
            }
          } else {
            logger.debug(s"Resolving references from source for $path")
            val resolver = ScalaReferencesResolver(symbolTable)
            val rf = resolver.resolveFromContent(path.last, text, path)
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

  private def fixStandInsForPath(path: os.Path): Unit = synchronized {
    var changed = true; var iters = 0
    while (changed && iters < 8) {
      changed = false; iters += 1
      for (sd <- symbolTable.all if sd.path == path && isSentinel(sd.range)) {
        SemanticdbIndexing.ownerStandIn(sd.symbol, symbolTable).foreach { standIn =>
          symbolTable.add(sd.copy(range = standIn.range))
          changed = true
        }
      }
    }
  }

  // ── range helpers ────────────────────────────────────────────

  private def isInsideRange(line: Int, char: Int, occurenceRange: Range): Boolean =
    line == occurenceRange.startLine && line == occurenceRange.endLine &&
      occurenceRange.startCharacter <= char && char < occurenceRange.endCharacter 

  private def rangeLength(r: Range): Long =
    (r.endLine.toLong - r.startLine.toLong) * 100000 + (r.endCharacter.toLong - r.startCharacter.toLong)

  private def isSentinel(r: Range): Boolean =
    r.startLine == 0 && r.startCharacter == 0 && r.endLine == 0 && r.endCharacter == 0
}

