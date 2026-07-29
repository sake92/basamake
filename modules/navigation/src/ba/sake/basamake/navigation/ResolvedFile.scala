package ba.sake.basamake.navigation

import scala.meta.internal.semanticdb.Range

/** Result of the second AST pass: all occurrences + document-scoped local defs.
  * `occurrences` carries BOTH definition and reference occurrences for this file
  * (defs have isDefinition=true, refs have isDefinition=false).
  * `locals` are the document-scoped `local<N>` SymbolDefinitions (params keep
  * `m().(x)` and are NOT in `locals`; method-local vals/objects/classes/type-params
  * get `local<N>` and ARE in `locals`).
  */
final case class ResolvedFile(
    occurrences: Vector[ReferenceOccurrence],
    locals: Vector[SymbolDefinition]
)

object ResolvedFile {
  val empty: ResolvedFile = ResolvedFile(Vector.empty, Vector.empty)
}

/** Local mirror of core's SemanticdbOccurrence so the navigation module stays
  * decoupled from core. Conversion to SemanticdbOccurrence is trivial (same fields)
  * and happens at the downstream pure-source indexer.
  */
final case class ReferenceOccurrence(
    symbol: String, // empty string = unresolved (mirrors compiler SUID-unresolved)
    range: Range,
    isDefinition: Boolean
)
