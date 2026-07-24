package ba.sake.basamake.navigation

import scala.collection.mutable

/** Per-scope overload index tracker.
  * Mutable state, one instance per class/object/trait body.
  * Methods are tracked by name -> next index; constructors use a single counter. */
private[navigation] final class OverloadTracker {
  val methodIdx = mutable.Map.empty[String, Int]
  var ctorIdx: Int = 0
}

/** Stack-based scope tracker for same-file reference resolution.
  * Each scope maps simple names to full SemanticDB symbols.
  * Term and type namespaces are tracked separately so companions coexist.
  * Imports store base paths for constructing symbols on resolution. */
private[navigation] final class ScopeTracker(parent: Option[ScopeTracker]) {
  private val termEntries = mutable.Map.empty[String, Symbol]
  private val typeEntries = mutable.Map.empty[String, Symbol]
  private val importPaths = mutable.Map.empty[String, String]

  def defineTerm(name: String, symbol: Symbol): Unit =
    termEntries(name) = symbol

  def defineType(name: String, symbol: Symbol): Unit =
    typeEntries(name) = symbol

  def defineImport(name: String, basePath: String): Unit =
    importPaths(name) = basePath

  /** Resolve a name across both namespaces and imports.
    * Returns all matching symbols (term + type).
    * Companions and name shadowing produce multiple results. */
  def resolve(name: String): Vector[Symbol] =
    val local = typeEntries.get(name).toVector ++ termEntries.get(name).toVector
    if local.nonEmpty then local
    else
      importPaths.get(name) match
        case Some(path) => Vector(SymbolUtils.typeSymbol(Symbol(path), name))
        case None       => parent.map(_.resolve(name)).getOrElse(Vector.empty)

  def child(): ScopeTracker = new ScopeTracker(Some(this))
}

private[navigation] object ScopeTracker {
  def empty: ScopeTracker = new ScopeTracker(None)
}
