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
      val unimports = Set.newBuilder[String]

      importer.importees.foreach {
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
          ()
      }

      ImportScopeData(
        explicit = explicit.result(),
        wildcards = if (importer.importees.exists(_.isInstanceOf[Importee.Wildcard])) List(prefix) else Nil,
        unimports = unimports.result()
      )
    }
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
