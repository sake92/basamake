package ba.sake.basamake.navigation

import org.eclipse.lsp4j.Location

object NavigationSymbolLookup {
  def candidateSymbolKeys(symbol: String): List[String] = {
    val clean = symbol
      .replaceAll("\\([^)]*\\)", "") // strip method descriptors: (), (+1), ...
      .stripSuffix(".")
      .stripSuffix("#")
      .replace('#', '.')             // class-member separator -> owner-qualified form
    val strippedPkg = clean
      .stripPrefix("_root_/")
      .stripPrefix("_empty_/")
    val afterPackage =
      strippedPkg.lastIndexOf('/') match
        case idx if idx >= 0 => strippedPkg.substring(idx + 1)
        case _               => strippedPkg
    val segments = afterPackage.split('.').toList.filter(_.nonEmpty)
    val inits =
      segments match
        case Nil => Nil
        case many =>
          many.inits.toList.reverse
            .filter(_.size >= 2)
            .map(_.mkString("."))
            .filter(_.nonEmpty)
    val bareName = segments.lastOption.toList
    (List(clean, strippedPkg) ++ inits ++ bareName).filter(_.nonEmpty).distinct
  }

  def isLocalSymbol(symbol: String): Boolean =
    symbol.startsWith("local")

  def firstDefinition(
      symbols: List[String],
      currentFileUri: String,
      workspaceSlices: List[SemanticdbFileSlice],
      dependencySlices: List[SemanticdbFileSlice]
  ): Option[Location] =
    symbols.iterator.flatMap { symbol =>
      if isLocalSymbol(symbol) then
        // Local symbols: search only in current file
        workspaceSlices.find(_.sourceUri == currentFileUri)
          .flatMap(slice => firstDefinitionInSlices(symbol, List(slice)))
      else
        // Non-local symbols: search all files
        firstDefinitionInSlices(symbol, workspaceSlices)
          .orElse(firstDefinitionInSlices(symbol, dependencySlices))
    }.toList.headOption

  def firstDefinitionInSlices(symbol: String, slices: List[SemanticdbFileSlice]): Option[Location] = {
    val keys = symbol +: candidateSymbolKeys(symbol)
    slices.iterator
      .flatMap { slice =>
        keys.iterator.flatMap(key => slice.symbolDefinitions.getOrElse(key, Nil).iterator)
      }
      .toList
      .headOption
  }
}
