package ba.sake.basamake.navigation

import ba.sake.basamake.util.UriUtils
import org.eclipse.lsp4j.Location

object NavigationLocationUtils {
  def postProcessLocations(locations: List[Location]): List[Location] = {
    val normalizedExisting = locations
      .flatMap(normalizeLocation)
      .filter(locationExists)
    normalizedExisting
      .groupBy(loc => s"${loc.getUri}:${loc.getRange.getStart.getLine}:${loc.getRange.getStart.getCharacter}:${loc.getRange.getEnd.getLine}:${loc.getRange.getEnd.getCharacter}")
      .values
      .map(_.head)
      .toList
  }

  private def normalizeLocation(loc: Location): Option[Location] =
    Option(loc).flatMap { l =>
      Option(l.getUri).map { uri =>
        val normalized = UriUtils.normalizeUri(uri)
        if normalized == uri then l
        else new Location(normalized, l.getRange)
      }
    }

  private def locationExists(loc: Location): Boolean =
    UriUtils.uriToPathOption(loc.getUri) match
      case Some(path) => os.exists(path)
      case None       => UriUtils.archivePathOption(loc.getUri).exists(os.exists(_))
}
