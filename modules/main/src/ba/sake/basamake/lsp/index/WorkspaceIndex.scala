package ba.sake.basamake.lsp.index

import scala.collection.mutable
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.*

class WorkspaceIndex extends StrictLogging {
  // 1. Symbol -> Location za brzi GotoDef
  val definitions = mutable.Map[Symbol, SymbolLocation]()

  // 2. Path -> SourceSemanticdb (potrebno da nađemo koji je Symbol pod kursorom)
  // TODO scaffeine last 50??
  private val fileDocs = mutable.Map[os.Path, SourceSemanticdb]()

  // 3. Invertovana mapa za O(1) brisanje i re-indeksiranje fajlova
  private val fileToSymbols = mutable.Map[os.Path, mutable.Set[Symbol]]()

  /** Dodaje ili ažurira indeks za dati fajl */
  def indexFile(path: os.Path, doc: SourceSemanticdb): Unit = synchronized {
    logger.debug(s"Indexing source file: $path")
    removeFile(path)
    fileDocs(path) = doc
    val createdSymbols = mutable.Set[Symbol]()

    for defn <- doc.definitions do
      definitions(defn.symbol) = defn.location
      createdSymbols += defn.symbol

    fileToSymbols(path) = createdSymbols
  }

  /** Uklanja sve simbole koji su nastali u danom fajlu */
  def removeFile(path: os.Path): Unit = synchronized {
    fileDocs.remove(path)
    fileToSymbols.remove(path).foreach { symbols =>
      symbols.foreach(definitions.remove)
    }
  }

  /** Pretvara (Path, Line, Char) u Symbol koji se nalazi na toj poziciji */
  def findSymbolAt(path: os.Path, line: Int, char: Int): Option[Symbol] = synchronized {
    logger.debug(s"Finding symbol at: $path:$line:$char")
    val fileDoc = fileDocs.get(path)
    logger.debug(s"fileDoc: $fileDoc")
    fileDoc.flatMap { doc =>
      // Prvo tražimo u referencama (najčešći slučaj za goto-def), pa u definicijama
      logger.debug(s"References: ${doc.references.map(r => s"${r.symbol} -> ${r.location.range}")}")
      val refMatch = doc.references.find(r => isInside(line, char, r.location.range)).map(_.symbol)
      val defMatch = doc.definitions.find(d => isInside(line, char, d.location.range)).map(_.symbol)
      val sym = refMatch.orElse(defMatch)
      logger.debug(s"Found symbol: $sym")
      sym
    }
  }

  /** Vraća lokaciju definicije za dati simbol */
  def gotoDefinition(symbol: Symbol): Option[SymbolLocation] = synchronized {
    val loc = definitions.get(symbol)
    logger.debug(s"Goto definition for symbol: $symbol -> $loc")
    loc
  }

  // Pomoćna funkcija koja provjerava da li je kursor unutar range-a
  private def isInside(line: Int, char: Int, range: SymbolLocationRange): Boolean =
    if line < range.startLine || line > range.endLine then false
    else if line == range.startLine && char < range.startCharacter then false
    else if line == range.endLine && char >= range.endCharacter then false
    else true
}