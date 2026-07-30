package ba.sake.basamake.navigation

import scala.meta.internal.semanticdb.Range

/** Result of the second AST pass: REFERENCE occurrences + document-scoped local defs.
  * `occurrences` carries reference occurrences only (definition occurrences live in
  * `SymbolTable` for globals and in `locals` for document-scoped locals).
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

/** A reference occurrence: a resolved (or unresolved, empty `symbol`) use of a
  * symbol at a source range. Definition sites are NOT represented here — they
  * live in `SymbolTable` (globals) or `ResolvedFile.locals` (document-scoped).
  */
final case class ReferenceOccurrence(
    symbol: String, // empty string = unresolved (mirrors compiler SUID-unresolved)
    range: Range
)
