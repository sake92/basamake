package ba.sake.basamake.navigation

import org.eclipse.lsp4j.Location

object NavigationSymbolLookup {

  /** Local symbol regex: `local` followed by digits, optionally with `+digits` suffix.
    * Matches compiler-produced local symbols like `local0`, `local1`, `local2+1`.
    * Global symbols that happen to start with "local" (e.g. `localDate#`) are NOT matched. */
  private val localSymbolRegex = """^local\d+(\+\d+)?$""".r

  /** Returns true if `symbol` is a true SemanticDB local symbol (document-scoped).
    * Global symbols like `localDate#` or `localMethod().` are not local. */
  def isLocalSymbol(symbol: String): Boolean =
    localSymbolRegex.matches(symbol)

  /** Looks up the first definition for a list of candidate symbols.
    * Searches workspace first, then dependency slices.
    * Local symbols are searched only in the current file; non-local symbols use all slices. */
  def firstDefinition(
      symbols: List[String],
      currentFileUri: String,
      workspaceSlices: List[SemanticdbFileSlice],
      dependencySlices: List[SemanticdbFileSlice]
  ): Option[Location] =
    symbols.iterator.flatMap { symbol =>
      if isLocalSymbol(symbol) then
        // Local symbols: search only in current file
        workspaceSlices
          .find(_.sourceUri == currentFileUri)
          .flatMap(slice => firstDefinitionInSlices(symbol, List(slice)))
      else
        // Non-local symbols: exact match across workspace then dependencies
        firstDefinitionInSlices(symbol, workspaceSlices)
          .orElse(firstDefinitionInSlices(symbol, dependencySlices))
    }.toList.headOption

  /** Finds the first matching definition location across slices using exact symbol match.
    * No candidate key expansion is performed. */
  def firstDefinitionInSlices(symbol: String, slices: List[SemanticdbFileSlice]): Option[Location] =
    slices.iterator
      .flatMap(_.symbolDefinitions.getOrElse(symbol, Nil).iterator)
      .toList
      .headOption
}
