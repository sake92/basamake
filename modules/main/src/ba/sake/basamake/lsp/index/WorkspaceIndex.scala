package ba.sake.basamake.lsp.index

import scala.collection.mutable
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.*

class WorkspaceIndex extends StrictLogging {
  // Global symbol -> definition location
  val definitions = mutable.Map[Symbol, SymbolLocation]()

  // Path -> SourceSemanticdb (cursor position → symbol resolution)
  // TODO scaffeine last 50??
  private val fileDocs = mutable.Map[os.Path, SourceSemanticdb]()

  // Inverted map for O(1) file removal and re-indexing
  private val fileToSymbols = mutable.Map[os.Path, mutable.Set[Symbol]]()

  /** Adds or updates the index for a file.
    * Local symbols (local<N>) live in SourceSemanticdb.definitions but NOT in the global map.
    * They are resolved per-file via findLocalDefinition. */
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

  /** Removes all symbols created by a given file. */
  def removeFile(path: os.Path): Unit = synchronized {
    fileDocs.remove(path)
    fileToSymbols.remove(path).foreach { symbols =>
      symbols.foreach(definitions.remove)
    }
  }

  /** Returns ALL symbols at cursor position (companion class+object, val+def candidates). */
  def findSymbolsAt(path: os.Path, line: Int, char: Int): Vector[Symbol] = synchronized {
    logger.debug(s"Finding symbols at: $path:$line:$char")
    val fileDoc = fileDocs.get(path)
    logger.debug(s"fileDoc: $fileDoc")
    fileDoc.map { doc =>
      logger.debug(s"References: ${doc.references.map(r => s"${r.symbol} -> ${r.location.range}")}")
      val refSyms = doc.references.filter(r => isInside(line, char, r.location.range)).map(_.symbol)
      val defSyms = doc.definitions.filter(d => isInside(line, char, d.location.range)).map(_.symbol)
      val syms = (refSyms ++ defSyms).toVector
      logger.debug(s"Found symbols: $syms")
      syms
    }.getOrElse(Vector.empty)
  }

  /** Returns global definition locations for a symbol. Falls back to alternate descriptor. */
  def gotoDefinitions(symbol: Symbol): Vector[SymbolLocation] = synchronized {
    definitions.get(symbol) match
      case Some(loc) =>
        logger.debug(s"Goto definition for symbol: $symbol -> $loc")
        Vector(loc)
      case None =>
        val alt = SymbolUtils.alternateDescriptor(symbol)
        definitions.get(alt) match
          case Some(loc) =>
            logger.debug(s"Goto definition (alt) for symbol: $symbol -> $alt -> $loc")
            Vector(loc)
          case None =>
            logger.debug(s"Goto definition for symbol: $symbol -> (not found)")
            Vector.empty
  }

  /** Returns local definition location for a symbol, scoped to a specific file.
    * Used for local<N> symbols that live in SourceSemanticdb.definitions but not in the global map. */
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
