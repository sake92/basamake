package ba.sake.basamake.lsp.index

import scala.collection.mutable
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.*

class WorkspaceIndex extends StrictLogging {
  // Global symbol -> definition location
  val definitions = mutable.Map[Symbol, SymbolLocation]()

  // Path -> SourceSemanticdb (cursor position → symbol resolution)
  private val fileDocs = mutable.Map[os.Path, SourceSemanticdb]()

  // Inverted map for O(1) file removal and re-indexing
  private val fileToSymbols = mutable.Map[os.Path, mutable.Set[Symbol]]()

  def indexFile(path: os.Path, doc: SourceSemanticdb): Unit = synchronized {
    logger.debug(s"Indexing source file: $path")
    removeFile(path)
    fileDocs(path) = doc
    val createdSymbols = mutable.Set[Symbol]()

    for defn <- doc.definitions do
      if !SymbolUtils.isLocalSymbol(defn.symbol.value) then
        definitions(defn.symbol) = defn.location
        createdSymbols += defn.symbol

    fileToSymbols(path) = createdSymbols
  }

  def removeFile(path: os.Path): Unit = synchronized {
    fileDocs.remove(path)
    fileToSymbols.remove(path).foreach { symbols =>
      symbols.foreach(definitions.remove)
    }
  }

  def findSymbolsAt(path: os.Path, line: Int, char: Int): Vector[Symbol] = synchronized {
    logger.debug(s"Finding symbols at: $path:$line:$char")
    val fileDoc = fileDocs.get(path)
    fileDoc.map { doc =>
      val refSyms = doc.references.filter(r => isInside(line, char, r.location.range)).map(_.symbol)
      if refSyms.nonEmpty then
        refSyms.toVector
      else
        val defSyms = doc.definitions.filter(d => isInside(line, char, d.location.range)).map(_.symbol)
        if defSyms.nonEmpty then
          logger.debug(s"Found defs: $defSyms")
          defSyms.toVector
        else Vector.empty
    }.getOrElse(Vector.empty)
  }

  def gotoDefinitions(symbol: Symbol): Vector[SymbolLocation] = synchronized {
    definitions.get(symbol) match
      case Some(loc) =>
        logger.debug(s"Goto definition for symbol: $symbol -> $loc")
        Vector(loc)
      case None =>
        logger.debug(s"Goto definition for symbol: $symbol -> (not found)")
        Vector.empty
  }

  def findLocalDefinition(path: os.Path, symbol: Symbol): Option[SymbolLocation] = synchronized {
    fileDocs.get(path).flatMap { doc =>
      doc.definitions.find(d => d.symbol == symbol).map(_.location)
    }
  }

  private def isInside(line: Int, char: Int, range: SymbolLocationRange): Boolean =
    if line < range.startLine || line > range.endLine then false
    else if line == range.startLine && char < range.startCharacter then false
    else if line == range.endLine && char >= range.endCharacter then false
    else true
}
