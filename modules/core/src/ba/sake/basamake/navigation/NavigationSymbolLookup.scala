package ba.sake.basamake.navigation

import org.eclipse.lsp4j.Location

object NavigationSymbolLookup {
  def candidateSymbolKeys(symbol: String): List[String] = {
    val clean = symbol
      .replace("()", "")
      .stripSuffix(".")
      .stripSuffix("#")
    val afterPackage =
      clean.lastIndexOf('/') match
        case idx if idx >= 0 => clean.substring(idx + 1)
        case _               => clean
    val segments = afterPackage.split('.').toList.filter(_.nonEmpty)
    segments match
      case Nil  => Nil
      case many => many.inits.toList.reverse.map(_.mkString(".")).filter(_.nonEmpty)
  }

  def firstDefinition(
      symbols: List[String],
      workspaceSlices: List[SemanticdbFileSlice],
      dependencySlices: List[SemanticdbFileSlice]
  ): Option[Location] =
    symbols.iterator
      .flatMap { symbol =>
        firstDefinitionInSlices(symbol, workspaceSlices)
          .orElse(firstDefinitionInSlices(symbol, dependencySlices))
      }
      .toList
      .headOption

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
