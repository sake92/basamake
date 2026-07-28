package ba.sake.basamake.navigation

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.meta.internal.semanticdb.Range

// IMPORTANT: Do NOT store Scalameta Tree objects here. Only Strings and Range.
// TODO store file abs path?
case class SymbolDefinition(
  symbol: String,
  shortName: String,
  isType: Boolean,
  range: Option[Range]
)

// TODO trait ?
class SymbolTable {

  private val definitions = new ConcurrentHashMap[String, SymbolDefinition]()

  def add(symDef: SymbolDefinition): Unit = 
    definitions.put(symDef.symbol, symDef)

  def get(symbol: String): Option[SymbolDefinition] = 
    Option(definitions.get(symbol))

  def all: Set[SymbolDefinition] = definitions.values().asScala.toSet
}