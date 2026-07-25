package ba.sake.basamake.navigation

import scala.collection.mutable

/** Per-scope overload index tracker.
  * Mutable state, one instance per class/object/trait body.
  * Methods are tracked by name -> next index; constructors use a single counter. */
private[navigation] final class OverloadTracker {
  val methodIdx = mutable.Map.empty[String, Int]
  var ctorIdx: Int = 0
}

/** Body-level scope tracker for local variable/param resolution.
  * Only used inside method bodies — tracks local val/var/def/param bindings.
  * No global resolution, no imports, no namespaces. */
private[navigation] final class LocalScope(parent: Option[LocalScope]) {
  private val entries = mutable.Map.empty[String, Symbol]

  def define(name: String, symbol: Symbol): Unit =
    entries(name) = symbol

  def lookup(name: String): Option[Symbol] =
    entries.get(name).orElse(parent.flatMap(_.lookup(name)))

  def child(): LocalScope = new LocalScope(Some(this))
}

private[navigation] object LocalScope {
  def empty: LocalScope = new LocalScope(None)
}
