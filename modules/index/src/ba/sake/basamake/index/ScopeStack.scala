package ba.sake.basamake.index

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
  * `methodImports` maps an imported name → its OWNER (Java static single-type
  * imports, where the member could be a method — probed via overload scan).
  * `candidates` maps an imported name → plausible FULL dep symbols (unverified
  * at parse time — the resolver's table has no dependency access). Emitted as
  * dual type+term pairs for package-prefixed importees; verified at REQUEST
  * time by `WorkspaceIndex.getSymbol` against the file's real BSP candidates.
  */
final case class ImportScopeData(
    explicit: Map[String, String],
    wildcards: List[String], // prefix owners, inner-to-outer
    unimports: Set[String],  // names excluded from wildcard resolution
    methodImports: Map[String, String] = Map.empty, // name → owner type symbol
    /** name → plausible FULL dep symbols (unverified at parse time). */
    candidates: Map[String, List[String]] = Map.empty
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
              ScopeStack.findMethodOverload(ownerKey, name, symbolTable).foreach(sym => break(Some(sym)))
            }
            val termSym = SymbolUtils.termSymbol(ownerKey, name)
            if (symbolTable.get(termSym).isDefined) break(Some(termSym))
          }

        case ImportScopeData(explicit, wildcards, unimports, methodImports, _) =>
          // Java static single-type import of a METHOD: the member is stored as
          // name → owner type; probe the owner's overloads first so a call binds
          // to the method symbol (the term symbol is never in the table).
          if (!isType && inCallContext) {
            methodImports.get(name).foreach { owner =>
              ScopeStack.findMethodOverload(owner, name, symbolTable).foreach(sym => break(Some(sym)))
            }
          }
          explicit.get(name).foreach { sym =>
            val matchesShape = if (isType) sym.endsWith("#") else !sym.endsWith("#")
            // table-verify: an explicit import whose symbol is absent (e.g. a
            // Java static import of an unknown member, stored as its term
            // symbol) must NOT resolve — it would emit a bogus ref.
            if (matchesShape && symbolTable.get(sym).isDefined) break(Some(sym))
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
                ScopeStack.findMethodOverload(prefix, name, symbolTable).foreach(sym => break(Some(sym)))
              }

              // `import foo._` also exposes the package OBJECT's members
              // (`foo/package.<name>`) — e.g. `file`/`uri`/`url` from
              // sbt's `package object sbt`. Only for package prefixes.
              if (prefix.endsWith("/")) {
                val pkgObj = prefix + "package."
                if (isType) {
                  val ts = SymbolUtils.typeSymbol(pkgObj, name)
                  if (symbolTable.get(ts).isDefined) break(Some(ts))
                  val te = SymbolUtils.termSymbol(pkgObj, name)
                  if (symbolTable.get(te).isDefined) break(Some(te))
                } else {
                  if (inCallContext) {
                    ScopeStack.findMethodOverload(pkgObj, name, symbolTable).foreach(sym => break(Some(sym)))
                  }
                  val termSym = SymbolUtils.termSymbol(pkgObj, name)
                  if (symbolTable.get(termSym).isDefined) break(Some(termSym))
                }
              }
            }
          }
      }
    }
    None
  }

  /** Candidate symbols for `name` when `lookup` returned None: plausible dep
    * symbols the parse-time table cannot verify. Shadowing still wins: any
    * LocalScope/OwnerScope binding the name (even in the other shape) kills
    * the candidate list — a local `x: IO` must not resolve `IO` to a dep.
    * Collects from import scopes (explicit candidates + wildcard prefixes,
    * incl. package-object members for package prefixes) top-down, deduped. */
  def lookupCandidates(name: String, isType: Boolean): List[String] = {
    val out = List.newBuilder[String]
    val it = stack.iterator
    while (it.hasNext) {
      it.next() match {
        case LocalScope(bindings) =>
          if (bindings.contains(name)) return Nil
        case OwnerScope(ownerKey) =>
          if (symbolTable.get(SymbolUtils.typeSymbol(ownerKey, name)).isDefined ||
              symbolTable.get(SymbolUtils.termSymbol(ownerKey, name)).isDefined) return Nil
        case ImportScopeData(_, wildcards, unimports, _, candidates) =>
          if (!unimports.contains(name)) {
            candidates.get(name).foreach { syms =>
              out ++= syms.filter(s => if (isType) s.endsWith("#") else !s.endsWith("#"))
            }
            if (isType) {
              for (prefix <- wildcards) {
                out += SymbolUtils.typeSymbol(prefix, name)
                out += SymbolUtils.termSymbol(prefix, name)
                if (prefix.endsWith("/")) {
                  out += SymbolUtils.typeSymbol(prefix + "package.", name)
                  out += SymbolUtils.termSymbol(prefix + "package.", name)
                }
              }
            } else {
              for (prefix <- wildcards) {
                out += SymbolUtils.termSymbol(prefix, name)
                if (prefix.endsWith("/"))
                  out += SymbolUtils.termSymbol(prefix + "package.", name)
              }
            }
          }
      }
    }
    out.result().distinct
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

object ScopeStack {
  /** Defensive cap for overload-index scans. SemanticDB method symbols are
    * `name().`, `name(+1).`, `name(+2).`, … — overload sets are normally
    * contiguous, but best-effort/partial tables can have gaps. The cap keeps a
    * runaway scan impossible (each probe is a symbol-table lookup). */
  val MaxOverloadIndex: Int = 100
  /** Consecutive misses tolerated before giving up the scan. Large enough to
    * cross realistic gaps (e.g. a table that only holds overloads 9+) without
    * letting pathological cases scan forever. */
  val OverloadMissThreshold: Int = 10

  /** Find the first existing overload of `name` under `owner` in `symbolTable`.
    *
    * Bounded dynamic scan: probes `0..MaxOverloadIndex`, returning the first
    * hit; stops after `OverloadMissThreshold` consecutive misses. This replaces
    * the old fixed `0..8` cap, which left large/sparse overload sets (index > 8)
    * falsely unresolved. */
  def findMethodOverload(owner: String, name: String, symbolTable: SymbolTable): Option[String] = {
    var idx = 0
    var misses = 0
    while (idx <= MaxOverloadIndex && misses < OverloadMissThreshold) {
      val methodSym = SymbolUtils.methodSymbol(owner, name, idx)
      if (symbolTable.get(methodSym).isDefined) return Some(methodSym)
      misses += 1
      idx += 1
    }
    None
  }
}
