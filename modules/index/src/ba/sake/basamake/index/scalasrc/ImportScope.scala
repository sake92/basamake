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
      val prefix = resolveImportPrefix(importer.ref, scopeStack, emitRef)
      val explicit = Map.newBuilder[String, String]
      val wildcards = List.newBuilder[String]
      val unimports = Set.newBuilder[String]

      importer.importees.foreach { importee =>
        importee match {
          case Importee.Name(n) =>
            val name = n.value
            resolveImportName(name, prefix, scopeStack.symbolTable) match {
              case Some(sym) =>
                emitRef(n, sym)
                explicit += (name -> sym)
              case None =>
                emitUnresolvedImportee(n, name, prefix, emitRef)
            }

          case Importee.Rename(from, to) =>
            val toName = to.value
            resolveImportName(from.value, prefix, scopeStack.symbolTable) match {
              case Some(sym) =>
                emitRef(to, sym)
                explicit += (toName -> sym)
              case None =>
                emitUnresolvedImportee(to, from.value, prefix, emitRef)
            }
            // Also emit ref for original name
            resolveImportName(from.value, prefix, scopeStack.symbolTable) match {
              case Some(sym) =>
                emitRef(from, sym)
                explicit += (from.value -> sym)
              case None =>
                emitUnresolvedImportee(from, from.value, prefix, emitRef)
            }

          case Importee.Unimport(n) =>
            unimports += n.value

          case Importee.Wildcard() =>
            // Wildcard: no individual refs; prefix resolved above
            wildcards += prefix

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
                  emitUnresolvedImportee(tpe, name, prefix, emitRef)
              }
            }

          case Importee.GivenAll() =>
            // `import a.b.given` — all givens: same as a wildcard (v1)
            wildcards += prefix
        }
      }

      ImportScopeData(
        explicit = explicit.result(),
        wildcards = wildcards.result(),
        unimports = unimports.result()
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

  /** Resolve the prefix of an import statement (e.g. `a.b.C` in `import a.b.C._`). */
  def resolveImportPrefix(
      ref: Term.Ref,
      scopeStack: ScopeStack,
      emitRef: (Tree, String) => Unit
  ): String = {
    ref match {
      case t: Term.Name =>
        val n = t.value
        scopeStack.lookup(n, isType = false, inCallContext = false)
          .orElse(PredefSymbols.rawLookup(n))
          .map { sym => emitRef(t, sym); sym }
          .getOrElse {
            // package segment — emit the PACKAGE symbol (resolves to the package
            // object `<pkg>/package.` at cursor time, when one exists)
            val pkgSym = SymbolUtils.packageOwner(List(n))
            emitRef(t, pkgSym)
            pkgSym
          }

      case Term.Select(qual: Term.Ref, name) =>
        val qualOwner = resolveImportPrefix(qual, scopeStack, emitRef)
        val n = name.value
        val memberSym = SymbolUtils.termSymbol(qualOwner, n)
        if (scopeStack.symbolTable.get(memberSym).isDefined) {
          emitRef(name, memberSym)
          memberSym
        } else if (qualOwner.endsWith("/")) {
          // package path: append name as sub-package and emit its package symbol
          val pkgPath = qualOwner + n + "/"
          emitRef(name, pkgPath)
          pkgPath
        } else {
          emitRef(name, "")
          qualOwner + "/" + n + "/"
        }

      case other: Term.Ref =>
        other.syntax.stripSuffix("._").stripSuffix(".*")
    }
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
    * range so the dep index can answer the lookup: a compiler would emit one
    * of them (type for classes, term for objects/traits/companions), and
    * `getSymbol` resolves whichever the dep jar defines. Importees inherited
    * through package objects/traits (e.g. `sttp.client3.basicRequest` living
    * on trait `SttpApi`) stay a compiler-level gap in source-parse mode.
    * Non-package owners keep the empty-symbol miss. */
  private def emitUnresolvedImportee(tree: Tree, name: String, prefix: String, emitRef: (Tree, String) => Unit): Unit =
    if (prefix.endsWith("/")) {
      emitRef(tree, SymbolUtils.typeSymbol(prefix, name))
      emitRef(tree, SymbolUtils.termSymbol(prefix, name))
    } else emitRef(tree, "")
}
