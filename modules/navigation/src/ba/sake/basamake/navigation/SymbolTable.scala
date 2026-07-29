package ba.sake.basamake.navigation

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.meta.internal.semanticdb.Range

// IMPORTANT: Do NOT store Scalameta Tree objects here. Only Strings and Range.
case class SymbolDefinition(
  symbol: String,
  shortName: String,
  isType: Boolean,
  range: Range,    // mandatory (use Range(0,0,0,0) as last-resort stand-in)
  path: os.Path    // mandatory — absolute path to the source file declaring this symbol
)

class SymbolTable {

  private val definitions = new ConcurrentHashMap[String, SymbolDefinition]()
  private val pathSymbols = new ConcurrentHashMap[os.Path, java.util.Set[String]]()

  def add(symDef: SymbolDefinition): Unit = {
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
}
