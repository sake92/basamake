package ba.sake.basamake.navigation

import scala.collection.mutable
import scala.util.boundary, boundary.break

/** Scope ADT for the reference resolver's scope stack.
  * Walked top-down: local → owner → imports → Predef/global.
  * Resolution order mirrors Scala's actual shadowing (locals shadow imports).
  */
sealed trait Scope

/** Local bindings from the current block/method body.
  * `bindings` maps source-level name → resolved symbol key.
  * Uses a mutable map so that nested definitions can add bindings to the
  * enclosing block scope without the block scope having to know about them.
  */
final case class LocalScope(bindings: collection.mutable.Map[String, String]) extends Scope

/** Owner scope: probes the `SymbolTable` for members of `ownerKey`.
  * The owner key is a SemanticDB global symbol (e.g. `"pkg/Foo#"`, `"pkg/Bar."`).
  */
final case class OwnerScope(ownerKey: String) extends Scope

/** Import scope: parsed from a single `import` statement.
  * `explicit` maps the imported name (or rename target) → resolved symbol key.
  * `wildcards` holds wildcard prefix owners, inner-to-outer order.
  * `unimports` holds names excluded from wildcard resolution.
  */
final case class ImportScopeData(
    explicit: Map[String, String],
    wildcards: List[String], // prefix owners, inner-to-outer
    unimports: Set[String]   // names excluded from wildcard resolution
) extends Scope

/** Mutable scope stack wrapping `ArrayStack[Scope]` with a `lookup` method
  * that probes the `SymbolTable` for owner-scope and import-scope resolution.
  */
class ScopeStack(val symbolTable: SymbolTable) {

  private val stack = mutable.ArrayStack.empty[Scope]

  def push(scope: Scope): Unit = stack.push(scope)
  def pop(): Unit = if (stack.nonEmpty) stack.pop()
  def isEmpty: Boolean = stack.isEmpty

  /** Walk the stack top-down (last-pushed first). Resolution order:
    *   1. LocalScope: exact name match, with optional `isType` shape filter.
    *   2. OwnerScope: probe SymbolTable for type/term/method members.
    *   3. ImportScope: explicit imports first, then wildcards.
    *   4. (fallback handled by caller — Predef, _empty_/, etc.)
    *
    * @param name          source-level identifier name
    * @param isType        true if this reference is in type position
    * @param inCallContext true if this reference is the `fun` of a call (`foo(...)`)
    * @return Some(symbol) if resolved, None otherwise
    */
  def lookup(name: String, isType: Boolean, inCallContext: Boolean): Option[String] = boundary {
    val it = stack.iterator
    while (it.hasNext) {
      it.next() match {
        case LocalScope(bindings) =>
          bindings.get(name).foreach { sym =>
            val matchesShape = if (isType) sym.endsWith("#") else !sym.endsWith("#")
            if (matchesShape) break(Some(sym))
          }

        case OwnerScope(ownerKey) =>
          if (isType || name.headOption.exists(_.isUpper)) {
            val typeSym = SymbolUtils.typeSymbol(ownerKey, name)
            if (symbolTable.get(typeSym).isDefined) break(Some(typeSym))
            // For type lookups, also try term symbol (objects used as types, e.g. `o: Obj`)
            val termSym = SymbolUtils.termSymbol(ownerKey, name)
            if (symbolTable.get(termSym).isDefined) break(Some(termSym))
          }
          if (!isType) {
            if (inCallContext) {
              var idx = 0
              while (idx <= 8) {
                val methodSym = SymbolUtils.methodSymbol(ownerKey, name, idx)
                if (symbolTable.get(methodSym).isDefined) break(Some(methodSym))
                idx += 1
              }
            }
            val termSym = SymbolUtils.termSymbol(ownerKey, name)
            if (symbolTable.get(termSym).isDefined) break(Some(termSym))
          }

        case ImportScopeData(explicit, wildcards, unimports) =>
          explicit.get(name).foreach { sym =>
            val matchesShape = if (isType) sym.endsWith("#") else !sym.endsWith("#")
            if (matchesShape) break(Some(sym))
          }
          if (!unimports.contains(name)) {
            for (prefix <- wildcards) {
              val candidate =
                if (isType) {
                  val ts = SymbolUtils.typeSymbol(prefix, name)
                  if (symbolTable.get(ts).isDefined) Some(ts)
                  else {
                    val te = SymbolUtils.termSymbol(prefix, name)
                    if (symbolTable.get(te).isDefined) Some(te) else None
                  }
                } else Some(SymbolUtils.termSymbol(prefix, name))
              candidate.foreach(c => if (symbolTable.get(c).isDefined) break(Some(c)))

              if (!isType && inCallContext) {
                var idx = 0
                while (idx <= 8) {
                  val methodSym = SymbolUtils.methodSymbol(prefix, name, idx)
                  if (symbolTable.get(methodSym).isDefined) break(Some(methodSym))
                  idx += 1
                }
              }
            }
          }
      }
    }
    None
  }

  /** Get all explicit import bindings currently in scope (for re-export resolution). */
  def currentImportExplicits: Map[String, String] = {
    stack.toList.reverse.collect {
      case ImportScopeData(explicit, _, _) => explicit
    }.foldLeft(Map.empty[String, String])(_ ++ _)
  }

  /** Add a binding to the topmost LocalScope on the stack.
    * Used by the resolver to add val/var bindings without pushing/popping.
    */
  def addLocalBinding(name: String, symbol: String): Unit = {
    stack.iterator.find(_.isInstanceOf[LocalScope]).foreach {
      case LocalScope(bindings) => bindings(name) = symbol
      case _ => ()
    }
  }
}
