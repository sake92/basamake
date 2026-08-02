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

// global symbol → definition
class SymbolTable extends StrictLogging {

  private val definitions = new ConcurrentHashMap[String, SymbolDefinition]()
  private val pathSymbols = new ConcurrentHashMap[os.Path, java.util.Set[String]]()

  def add(symDef: SymbolDefinition): Unit = {
    if SymbolUtils.isLocalSymbol(symDef.symbol) then
      logger.warn(s"Attempted to add local symbol ${symDef.symbol} to global SymbolTable; skipping")
      return
    definitions.put(symDef.symbol, symDef)
    pathSymbols.computeIfAbsent(symDef.path, _ => ConcurrentHashMap.newKeySet[String]()).add(symDef.symbol)
  }

  def removeByPath(path: os.Path): Unit = {
    val symbols = pathSymbols.remove(path)
    if (symbols != null) {
      symbols.forEach { symbol =>
        definitions.remove(symbol)
      }
    }
  }

  def get(symbol: String): Option[SymbolDefinition] =
    Option(definitions.get(symbol))

  def keys: Set[String] = definitions.keySet().asScala.toSet

  def all: Set[SymbolDefinition] = definitions.values().asScala.toSet

  def byPath(path: os.Path): Set[SymbolDefinition] = {
    val symbols = pathSymbols.get(path)
    if (symbols == null) Set.empty
    else symbols.asScala.flatMap(sym => Option(definitions.get(sym))).toSet
  }
}
