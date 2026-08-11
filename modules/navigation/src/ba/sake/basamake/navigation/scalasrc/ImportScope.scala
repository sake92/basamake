package ba.sake.basamake.navigation.scalasrc

import scala.meta.*
import ba.sake.basamake.navigation.{SymbolTable, SymbolUtils, ScopeStack, ImportScopeData}
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
                emitRef(n, "")
            }

          case Importee.Rename(from, to) =>
            val toName = to.value
            resolveImportName(from.value, prefix, scopeStack.symbolTable) match {
              case Some(sym) =>
                emitRef(to, sym)
                explicit += (toName -> sym)
              case None =>
                emitRef(to, "")
            }
            // Also emit ref for original name
            resolveImportName(from.value, prefix, scopeStack.symbolTable) match {
              case Some(sym) =>
                emitRef(from, sym)
                explicit += (from.value -> sym)
              case None =>
                emitRef(from, "")
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
                  emitRef(tpe, "")
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
          .getOrElse { emitRef(t, ""); SymbolUtils.packageOwner(List(n)) }

      case Term.Select(qual: Term.Ref, name) =>
        val qualOwner = resolveImportPrefix(qual, scopeStack, emitRef)
        val n = name.value
        val memberSym = SymbolUtils.termSymbol(qualOwner, n)
        if (scopeStack.symbolTable.get(memberSym).isDefined) {
          emitRef(name, memberSym)
          memberSym
        } else {
          // Try as package path: append name as sub-package
          val pkgPath = if (qualOwner.endsWith("/")) qualOwner + n + "/"
                        else qualOwner + "/" + n + "/"
          emitRef(name, "")
          pkgPath
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
}
