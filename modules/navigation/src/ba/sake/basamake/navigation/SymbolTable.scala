package ba.sake.basamake.navigation

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.meta.internal.semanticdb.Range
import com.typesafe.scalalogging.StrictLogging

// IMPORTANT: Do NOT store Scalameta Tree objects here. Only Strings and Range.
case class SymbolDefinition(
  symbol: String,
  shortName: String,
  isType: Boolean,
  range: Range,    // mandatory (use Range(0,0,0,0) as last-resort stand-in)
  path: os.Path    // mandatory — absolute path to the source file declaring this symbol
)

/** Workspace symbol table: mutable index of workspace sources (definitions,
  * path-keyed invalidation, enumeration for wrapperScan/debug dumps).
  * Dependency/JDK symbols are NOT part of this interface — they live in
  * IndexedSymbolTable, a separate read-only lookup service. */
trait SymbolTable {
  def get(symbol: String): Option[SymbolDefinition]
  def byPath(path: os.Path): Set[SymbolDefinition]
  def add(symDef: SymbolDefinition): Unit
  def removeByPath(path: os.Path): Unit
  def keys: Set[String]
  def all: Set[SymbolDefinition]
}

// global symbol → definition (workspace sources)
class InMemorySymbolTable extends SymbolTable with StrictLogging {

  private val definitions = new ConcurrentHashMap[String, SymbolDefinition]()
  private val pathSymbols = new ConcurrentHashMap[os.Path, java.util.Set[String]]()

  override def add(symDef: SymbolDefinition): Unit = {
    if SymbolUtils.isLocalSymbol(symDef.symbol) then
      logger.warn(s"Attempted to add local symbol ${symDef.symbol} to global SymbolTable; skipping")
      return
    definitions.put(symDef.symbol, symDef)
    pathSymbols.computeIfAbsent(symDef.path, _ => ConcurrentHashMap.newKeySet[String]()).add(symDef.symbol)
  }

  override def removeByPath(path: os.Path): Unit = {
    val symbols = pathSymbols.remove(path)
    if (symbols != null) {
      symbols.forEach { symbol =>
        definitions.remove(symbol)
      }
    }
  }

  override def get(symbol: String): Option[SymbolDefinition] =
    Option(definitions.get(symbol))

  override def keys: Set[String] = definitions.keySet().asScala.toSet

  override def all: Set[SymbolDefinition] = definitions.values().asScala.toSet

  override def byPath(path: os.Path): Set[SymbolDefinition] = {
    val symbols = pathSymbols.get(path)
    if (symbols == null) Set.empty
    else symbols.asScala.flatMap(sym => Option(definitions.get(sym))).toSet
  }
}
