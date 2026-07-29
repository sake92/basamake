package ba.sake.basamake.lsp.index

import scala.collection.mutable
import com.typesafe.scalalogging.StrictLogging
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.navigation.*
import ba.sake.basamake.navigation.scalasrc.*

class WorkspaceIndex extends StrictLogging {
  // global symbol → definition
  val symbolTable = SymbolTable()

  // source path → .semanticdb path (built once at initialize)
  private val semanticdbBySource = mutable.Map.empty[os.Path, os.Path]

  // open buffer state
  private val openBuffers = mutable.Map.empty[os.Path, String]
  private val openOccurrences = mutable.Map.empty[os.Path, Vector[ReferenceOccurrence]]
  private val openLocals = mutable.Map.empty[os.Path, Vector[SymbolDefinition]]
  private val dirty = mutable.Set.empty[os.Path]

  // ── initialize ──────────────────────────────────────────────
  def initialize(workspaceRoot: os.Path): Unit = synchronized {
    logger.info(s"Initializing workspace index at $workspaceRoot")
    // Pass A: build semanticdbBySource
    val discovered = SemanticdbIndexing.discoverSemanticdbSources(workspaceRoot)
    semanticdbBySource.clear(); semanticdbBySource.addAll(discovered)
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
    }

    //   B2: from source-AST for files WITHOUT semanticdb
    // TODO this would miss generatedSources in .deder and target/..
    val skipDirNames = Set(".git", ".deder", "target", "out", ".basamake", ".metals", ".scala-build", ".bsp", "node_modules")
    val skip: os.Path => Boolean = p => {
      if (os.isDir(p)) skipDirNames.contains(p.last) else false
    }
    val sources = os.walk(workspaceRoot, skip = skip).filter { p =>
      os.isFile(p) && p.ext == "scala" && !semanticdbBySource.contains(p)
    }
    for (path <- sources) {
      try {
        val content = os.read(path)
        val extractor = ScalaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
    }

    // Pass C: stand-in fix-up for sentinel ranges
    // TODO check if necessary..
    var changed = true
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
    }
    logger.info(s"Symbol table contains ${symbolTable.all.size} entries after stand-in")
  }

  // ── onDidOpen/Change/Save/Close ──────────────────────────────
  def onDidOpen(path: os.Path, text: String): Unit = synchronized {
    openBuffers(path) = text
    dirty.remove(path)
    refreshOpenBuffer(path)
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
    openLocals.remove(path)
    dirty.remove(path)
  }

  // ── queries ─────────────────────────────────────────────────
  def findSymbolsAt(path: os.Path, line: Int, char: Int): Vector[String] = synchronized {
    val occs = openOccurrences.getOrElse(path, null)
    if (occs == null) return Vector.empty
    val enclosing = occs.filter(o => isInside(line, char, o.range))
    if (enclosing.isEmpty) Vector.empty
    else {
      val minLen = enclosing.map(o => rangeLength(o.range)).min
      enclosing.filter(o => rangeLength(o.range) == minLen).map(o => o.symbol).toVector
    }
  }

  def gotoDefinitions(path: os.Path, line: Int, char: Int): Vector[SymbolDefinition] = synchronized {
    val occs = openOccurrences.getOrElse(path, null)
    if (occs == null) return Vector.empty
    val enclosing = occs.filter(o => isInside(line, char, o.range))
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
            openLocals.get(path).flatMap(_.find(ld => ld.symbol == symbol)).map { ld =>
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
    }
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
        val occsWithLocals =
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
            val resolver = ScalaReferencesResolver(symbolTable)
            val rf = resolver.resolveFromContent(path.last, text, path)
            (rf.occurrences, rf.locals)
          }
        openOccurrences(path) = occsWithLocals._1
        openLocals(path) = occsWithLocals._2
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

  private def isInside(line: Int, char: Int, r: Range): Boolean = {
    if (line < r.startLine || line > r.endLine) false
    else if (line == r.startLine && char < r.startCharacter) false
    else if (line == r.endLine && char >= r.endCharacter) false
    else true
  }

  private def rangeLength(r: Range): Long =
    (r.endLine.toLong - r.startLine.toLong) * 100000 + (r.endCharacter.toLong - r.startCharacter.toLong)

  private def isSentinel(r: Range): Boolean =
    r.startLine == 0 && r.startCharacter == 0 && r.endLine == 0 && r.endCharacter == 0
}

object WorkspaceIndex {
  def apply(): WorkspaceIndex = new WorkspaceIndex
}
