package ba.sake.basamake.index.scalasrc

import scala.meta.*
import ba.sake.basamake.index.{SymbolTable, SymbolUtils, ScopeStack, ImportScopeData}
/** Parses a scalameta `Import` stat into `ImportScopeData` entries.
  * Used by `ScopeStack` to populate import scopes during traversal.
  *
  * Also emits reference occurrences for the imported path segments and each
  * imported name, matching compiler behavior.
  */
object ImportScope {

  /** Parse an `Import` stat into a list of `Scope` entries (one per importer).
    * Each `Importer` becomes its own `ImportScopeData` pushed onto the scope stack.
    */
  def parse(
      imp: Import,
      scopeStack: ScopeStack,
      emitRef: (Tree, String) => Unit
  ): List[ImportScopeData] = {
    imp.importers.toList.map { importer =>
      val (prefix, altPrefixes) = resolveImportPrefix(importer.ref, scopeStack, emitRef)
      val candidatePrefixes = List(prefix) ++ altPrefixes
      val explicit = Map.newBuilder[String, String]
      val wildcards = List.newBuilder[String]
      val unimports = Set.newBuilder[String]
      val candidates = Map.newBuilder[String, List[String]]

      importer.importees.foreach { importee =>
        importee match {
          case Importee.Name(n) =>
            val name = n.value
            resolveImportName(name, prefix, scopeStack.symbolTable) match {
              case Some(sym) =>
                emitRef(n, sym)
                explicit += (name -> sym)
              case None =>
                val cands = emitUnresolvedImporteeForPrefixes(candidatePrefixes, name, emitRef, n, scopeStack.symbolTable)
                if (cands.nonEmpty) candidates += (name -> cands)
            }

          case Importee.Rename(from, to) =>
            val toName = to.value
            resolveImportName(from.value, prefix, scopeStack.symbolTable) match {
              case Some(sym) =>
                emitRef(to, sym)
                explicit += (toName -> sym)
              case None =>
                val cands = emitUnresolvedImporteeForPrefixes(candidatePrefixes, from.value, emitRef, to, scopeStack.symbolTable)
                if (cands.nonEmpty) candidates += (toName -> cands)
            }
            // Also emit ref for original name
            resolveImportName(from.value, prefix, scopeStack.symbolTable) match {
              case Some(sym) =>
                emitRef(from, sym)
                explicit += (from.value -> sym)
              case None =>
                val cands = emitUnresolvedImporteeForPrefixes(candidatePrefixes, from.value, emitRef, from, scopeStack.symbolTable)
                if (cands.nonEmpty) candidates += (from.value -> cands)
            }

          case Importee.Unimport(n) =>
            unimports += n.value

          case Importee.Wildcard() =>
            // Wildcard: no individual refs; prefix resolved above. ALL candidate
            // prefixes become wildcard owners (a `cats.syntax.all` may be an
            // OBJECT, not a package — request-time verification decides).
            wildcards ++= candidatePrefixes

          case Importee.Given(tpe) =>
            // `import a.b.{given Foo}` — resolve like a name import. v1: the
            // given name goes into `explicit` so goto-def on the importee and
            // on by-name given usages resolves; anonymous givens stay a non-goal.
            typeNameOf(tpe).foreach { name =>
              resolveImportName(name, prefix, scopeStack.symbolTable) match {
                case Some(sym) =>
                  emitRef(tpe, sym)
                  explicit += (name -> sym)
                case None =>
                  val cands = emitUnresolvedImporteeForPrefixes(candidatePrefixes, name, emitRef, tpe, scopeStack.symbolTable)
                  if (cands.nonEmpty) candidates += (name -> cands)
              }
            }

          case Importee.GivenAll() =>
            // `import a.b.given` — all givens: same as a wildcard (v1)
            wildcards ++= candidatePrefixes
        }
      }

      ImportScopeData(
        explicit = explicit.result(),
        wildcards = wildcards.result(),
        unimports = unimports.result(),
        candidates = candidates.result()
      )
    }
  }

  /** Last name segment of a given-import type: `given Foo`, `given a.b.C`, `given Foo[Int]`. */
  private def typeNameOf(tpe: Type): Option[String] = tpe match {
    case Type.Name(n)         => Some(n)
    case Type.Select(_, name) => Some(name.value)
    case Type.Apply(head, _)  => typeNameOf(head)
    case _                    => None
  }

  /** Resolve the prefix of an import statement (e.g. `a.b.C` in `import a.b.C._`).
    * Returns (primary prefix, alternative prefixes): a member under a package
    * path may be an OBJECT (`cats.syntax.all`), not a sub-package — the term
    * candidate prefix is returned as an alternative and request-time
    * verification decides which exists. */
  def resolveImportPrefix(
      ref: Term.Ref,
      scopeStack: ScopeStack,
      emitRef: (Tree, String) => Unit
  ): (String, List[String]) = {
    ref match {
      case t: Term.Name =>
        val p = scopeStack.lookup(t.value, isType = false, inCallContext = false)
          .orElse(PredefSymbols.rawLookup(t.value))
          .map { sym => emitRef(t, sym); sym }
          .getOrElse {
            val pkgSym = SymbolUtils.packageOwner(List(t.value))
            emitRef(t, pkgSym)
            pkgSym
          }
        (p, Nil)

      case Term.Select(qual: Term.Ref, name) =>
        val (qualOwner, qualAlts) = resolveImportPrefix(qual, scopeStack, emitRef)
        val n = name.value
        val memberSym = SymbolUtils.termSymbol(qualOwner, n)
        if (scopeStack.symbolTable.get(memberSym).isDefined) {
          emitRef(name, memberSym)
          (memberSym, Nil)
        } else if (qualOwner.endsWith("/")) {
          val pkgPath = qualOwner + n + "/"
          emitRef(name, pkgPath)
          // `cats.syntax.all` may be an OBJECT, not a package — keep the term
          // candidate too; request-time verification decides which exists.
          val termAlt = SymbolUtils.termSymbol(qualOwner, n)
          emitRef(name, termAlt)
          (pkgPath, qualAlts ++ List(termAlt))
        } else {
          emitRef(name, "")
          (qualOwner + "/" + n + "/", qualAlts)
        }

      case other: Term.Ref =>
        (other.syntax.stripSuffix("._").stripSuffix(".*"), Nil)
    }
  }

  /** Probe every candidate prefix and accumulate the emitted candidates. */
  private def emitUnresolvedImporteeForPrefixes(
      prefixes: List[String],
      name: String,
      emitRef: (Tree, String) => Unit,
      tree: Tree,
      table: SymbolTable
  ): List[String] = {
    val all = List.newBuilder[String]
    prefixes.foreach { p =>
      all ++= emitUnresolvedImportee(tree, name, p, emitRef, table)
    }
    all.result().distinct
  }

  /** Try to resolve an importee name against a prefix owner. */
  private def resolveImportName(name: String, prefix: String, table: SymbolTable): Option[String] = {
    val typeSym = SymbolUtils.typeSymbol(prefix, name)
    val termSym = SymbolUtils.termSymbol(prefix, name)
    if (table.get(typeSym).isDefined) Some(typeSym)
    else if (table.get(termSym).isDefined) Some(termSym)
    else None
  }

  /** Source-parse fallback for an importee that misses the WORKSPACE symbol
    * table (resolvers have no dependency access — by design). When the import
    * prefix is a package path, emit BOTH plausible symbols for the importee
    * range AND return them as scope candidates so BODY usages resolve at
    * request time: a compiler would emit one of them (type for classes, term
    * for objects/traits/companions), and `getSymbol` resolves whichever the
    * dep jar defines. UNRESOLVED term-owner prefixes (dep companion members,
    * e.g. `IO.{pure => ioPure}`) emit the index-0 METHOD + term candidates —
    * the request-time prefix scan resolves the true overload keys; a
    * TABLE-RESOLVED term owner is a workspace object, so no candidates are
    * invented there. Importees inherited through package objects/traits (e.g.
    * `sttp.client3.basicRequest` living on trait `SttpApi`) stay a
    * compiler-level gap in source-parse mode. Other non-package owners keep
    * the empty-symbol miss. */
  private def emitUnresolvedImportee(tree: Tree, name: String, prefix: String, emitRef: (Tree, String) => Unit, table: SymbolTable): List[String] =
    if (prefix.endsWith("/")) {
      val syms = List(SymbolUtils.typeSymbol(prefix, name), SymbolUtils.termSymbol(prefix, name))
      syms.foreach(sym => emitRef(tree, sym))
      syms
    } else if ((prefix.endsWith(".") || prefix.endsWith("#")) && table.get(prefix).isEmpty) {
      // member importee under an UNRESOLVED (dep) term owner (`IO.pure`): the
      // member is a method or a val — emit both plausible shapes; the exact
      // key or the request-time prefix scan resolves whichever the dep jar
      // defines
      val syms = List(SymbolUtils.methodSymbol(prefix, name, 0), SymbolUtils.termSymbol(prefix, name))
      syms.foreach(sym => emitRef(tree, sym))
      syms
    } else { emitRef(tree, ""); Nil }
}
