package ba.sake.basamake.lsp.index

import scala.collection.mutable
import ba.sake.basamake.navigation.*

class WorkspaceIndex {
  // 1. Symbol -> Location za brzi GotoDef
  private val definitions = mutable.Map[Symbol, SymbolLocation]()

  // 2. Path -> SourceSemanticdb (potrebno da nađemo koji je Symbol pod kursorom)
  private val fileDocs = mutable.Map[os.Path, SourceSemanticdb]()

  // 3. Invertovana mapa za O(1) brisanje i re-indeksiranje fajlova
  private val fileToSymbols = mutable.Map[os.Path, mutable.Set[Symbol]]()

  /** Dodaje ili ažurira indeks za dati fajl */
  def indexFile(path: os.Path, doc: SourceSemanticdb): Unit = synchronized {
    // Ako fajl već postoji u indeksu, prvo ga obrišemo
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
    fileDocs.get(path).flatMap { doc =>
      // Prvo tražimo u referencama (najčešći slučaj za goto-def), pa u definicijama
      val refMatch = doc.references.find(r => isInside(line, char, r.location.range)).map(_.symbol)
      val defMatch = doc.definitions.find(d => isInside(line, char, d.location.range)).map(_.symbol)
      refMatch.orElse(defMatch)
    }
  }

  /** Vraća lokaciju definicije za dati simbol */
  def gotoDefinition(symbol: Symbol): Option[SymbolLocation] = synchronized {
    definitions.get(symbol)
  }

  // Pomoćna funkcija koja provjerava da li je kursor unutar range-a
  private def isInside(line: Int, char: Int, range: SymbolLocationRange): Boolean =
    if line < range.startLine || line > range.endLine then false
    else if line == range.startLine && char < range.startCharacter then false
    else if line == range.endLine && char >= range.endCharacter then false
    else true
}