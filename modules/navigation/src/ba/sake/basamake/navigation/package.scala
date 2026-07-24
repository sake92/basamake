package ba.sake.basamake.navigation

/** Globally unique symbol, e.g. `com/example/Main#` or `com/example/Main#main().`
  * Even local symbols are encoded as global symbols, e.g. `src/main/scala/Main.scala#local0`
  */
opaque type Symbol = String
object Symbol:
  def apply(symbol: String): Symbol = symbol
  extension (s: Symbol)
    def value: String = s

/** 
  * Indexes are 0-based, inclusive start, exclusive end (LSP / SemanticDB standard).
  */
case class SymbolLocationRange(
    startLine: Int,
    startCharacter: Int,
    endLine: Int,
    endCharacter: Int
)

case class SymbolLocation(
    path: os.Path,
    range: SymbolLocationRange
)
