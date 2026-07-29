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
    val occs = openOccurrences.getOrElse(path, null)
    if (occs == null) return Vector.empty
    val enclosing = occs.filter(o => isInsideRange(line, char, o.range))
    if (enclosing.isEmpty) Vector.empty
    else {
      val minLen = enclosing.map(o => rangeLength(o.range)).min
      enclosing.filter(o => rangeLength(o.range) == minLen).map(o => o.symbol).toVector
    }
  }

  def gotoDefinitions(path: os.Path, line: Int, char: Int): Vector[SymbolDefinition] = synchronized {
    val occurences = openOccurrences.getOrElse(path, Vector.empty)
    val references = occurences.filter(_.isDefinition == false)
    val localDefinitionsMap = openLocalDefinitions.getOrElse(path, Vector.empty).map(ld => ld.symbol -> ld).toMap
    
    //occurences.filter(_.isDefinition == true).groupBy(_.symbol).map { case (sym, occs) => sym -> occs.head }
    //logger.debug(s"gotoDefinitions: $path:$line:$char, occs=${occs}")
    //logger.debug(s"gotoDefinitions openLocalDefinitions=${openLocalDefinitions.getOrElse(path, Vector.empty)}")
    //val localCandidates = openLocalDefinitions.getOrElse(path, Vector.empty).filter(o => isInsideRange(line, char, o.range))
    //logger.debug(s"gotoDefinitions localCandidates=${localCandidates}")


    val referencesUnderCursor = references.filter(o => isInsideRange(line, char, o.range))
    println(s"gotoDefinitions referencesUnderCursor=${referencesUnderCursor}")
    val localDefinitions = referencesUnderCursor.flatMap(o => localDefinitionsMap.get(o.symbol))
    if localDefinitions.nonEmpty
    then localDefinitions
    else {
      val globalDefinitions = referencesUnderCursor.flatMap(o => symbolTable.get(o.symbol))
      globalDefinitions
    }

    
    
    //logger.debug(s"gotoDefinitions globalCandidates=${globalCandidates}")
   // val res = if localCandidates.nonEmpty
    //then localCandidates 
   // else globalCandidates.flatMap { occ =>
    // localCandidates.get or Else global
    //  symbolTable.get(occ.symbol)
   // }
    //logger.debug(s"gotoDefinitions result=${res}")

    //res
    
    /*val enclosing = occs.filter(o => isInsideRange(line, char, o.range))
    if (enclosing.isEmpty) return Vector.empty
    val minLen = enclosing.map(o => rangeLength(o.range)).min
    val targets = enclosing.filter(o => rangeLength(o.range) == minLen).map(_.symbol).distinct

    targets.toVector.flatMap { symbol =>
      if (SymbolUtils.isLocalSymbol(symbol)) {
        val localDefOcc = occs.find(o => o.symbol == symbol && o.isDefinition)
        localDefOcc match {
          case Some(occ) =>
            Vector(SymbolDefinition(
              symbol = occ.symbol,
              shortName = occ.symbol,
              isType = SymbolUtils.isTypeSymbol(occ.symbol),
              range = occ.range,
              path = path
            ))
          case None =>
            openLocalDefinitions.get(path).flatMap(_.find(ld => ld.symbol == symbol)).map { ld =>
              SymbolDefinition(
                symbol = ld.symbol,
                shortName = ld.symbol,
                isType = SymbolUtils.isTypeSymbol(ld.symbol),
                range = ld.range,
                path = path
              )
            }.toVector
        }
      } else {
        symbolTable.get(symbol) match {
          case Some(sd) =>
            Vector(sd)
          case None => Vector.empty
        }
      }
    }*/
  }

  /** v1: scan only occurrences in CURRENTLY OPEN FILES.
    * Cross-workspace references are explicitly out of scope for v1. */
  def references(path: os.Path, line: Int, char: Int, includeDeclaration: Boolean): Vector[SymbolDefinition] = synchronized {
    val targetSymbols = findSymbolsAt(path, line, char).toSet
    if (targetSymbols.isEmpty) Vector.empty
    else {
      val results = Vector.newBuilder[SymbolDefinition]
      for (openPath <- openOccurrences.keys) {
        val occs = openOccurrences(openPath)
        for (occ <- occs if targetSymbols.contains(occ.symbol)) {
          if (includeDeclaration || !occ.isDefinition) {
            results += SymbolDefinition(
              symbol = occ.symbol,
              shortName = occ.symbol,
              isType = SymbolUtils.isTypeSymbol(occ.symbol),
              range = occ.range,
              path = openPath
            )
          }
        }
      }
      results.result().distinct
    }
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

