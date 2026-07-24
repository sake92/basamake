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
  * Imports store base paths for constructing symbols on resolution. */
private[navigation] final class ScopeTracker(parent: Option[ScopeTracker]) {
  private val entries = mutable.Map.empty[String, Symbol]
  private val importPaths = mutable.Map.empty[String, String]

  def define(name: String, symbol: Symbol): Unit =
    entries(name) = symbol

  def defineImport(name: String, basePath: String): Unit =
    importPaths(name) = basePath

  /** Resolve a name by searching this scope, then parent scopes.
    * First checks direct definitions, then import entries. */
  def resolve(name: String): Option[Symbol] =
    entries.get(name).orElse {
      importPaths.get(name).map { path =>
        SymbolUtils.typeSymbol(Symbol(path), name)
      }
    }.orElse(parent.flatMap(_.resolve(name)))

  def child(): ScopeTracker = new ScopeTracker(Some(this))
}

private[navigation] object ScopeTracker {
  def empty: ScopeTracker = new ScopeTracker(None)
}
