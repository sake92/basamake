package ba.sake.basamake.navigation

import java.security.MessageDigest
import org.eclipse.lsp4j.{Position, Range, SymbolKind}

final case class SourceDefinition(
    name: String,
    kind: SymbolKind,
    symbol: String,
    ownerName: String,
    range: Range
)

object DependencySourceParsing {

  private final case class MavenCoordinates(groupId: String, artifactId: String, version: String)

  def extractDefinitions(fileName: String, content: String): List[SourceDefinition] =
    if fileName.endsWith(".scala") then ScalaSourceParser.extractDefinitions(content, fileName)
    else if fileName.endsWith(".java") then JavaSourceParser.extractDefinitions(content, fileName)
    else Nil

  def dependencyCacheKey(archiveUri: String): String = {
    val hash8 = stableHash(archiveUri)
    mavenCoordinates(archiveUri)
      .map { coords =>
        val gav =
          s"${sanitizePathSegment(coords.groupId)}-${sanitizePathSegment(coords.artifactId)}-${sanitizePathSegment(coords.version)}"
        s"$gav-$hash8"
      }
      .getOrElse(hash8)
  }

  private def mavenCoordinates(archiveUri: String): Option[MavenCoordinates] = {
    val normalizedArchiveUri =
      if archiveUri.startsWith("jar:") then archiveUri.stripPrefix("jar:").takeWhile(_ != '!')
      else archiveUri
    val path = try java.net.URI.create(NavigationUriUtils.canonicalFileUri(normalizedArchiveUri)).getPath
    catch case _: Exception => normalizedArchiveUri
    val segments = path.split('/').toList.filter(_.nonEmpty)
    val maven2Index = segments.lastIndexOf("maven2")
    if maven2Index < 0 then None
    else {
      val tail = segments.drop(maven2Index + 1)
      if tail.length < 4 then None
      else {
        val groupParts = tail.dropRight(3)
        val artifactId = tail(tail.length - 3)
        val version = tail(tail.length - 2)
        if groupParts.isEmpty || artifactId.isEmpty || version.isEmpty then None
        else Some(MavenCoordinates(groupParts.mkString("."), artifactId, version))
      }
    }
  }

  private def sanitizePathSegment(value: String): String =
    value.map {
      case c if c.isLetterOrDigit || c == '.' || c == '-' || c == '_' => c
      case _                                                           => '_'
    }

  private def stableHash(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest
      .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .take(4)
      .map(b => f"${b & 0xff}%02x")
      .mkString
}
