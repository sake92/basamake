package ba.sake.basamake.navigation

object NavigationUriUtils {
  def normalizeUri(uri: String): String =
    try java.nio.file.Path.of(java.net.URI.create(uri)).toUri.toString
    catch case _: Exception => uri

  def canonicalFileUri(uri: String): String = {
    val withoutJarPrefix = uri.stripPrefix("jar:")
    val archiveOnly = withoutJarPrefix.takeWhile(_ != '!')
    val decoded =
      try java.net.URLDecoder.decode(archiveOnly, java.nio.charset.StandardCharsets.UTF_8)
      catch case _: Exception => archiveOnly
    try java.nio.file.Path.of(java.net.URI.create(decoded)).toUri.toString
    catch case _: Exception => decoded
  }

  def uriToPathOption(uri: String): Option[os.Path] =
    try Some(os.Path(java.net.URI.create(uri)))
    catch case _: Exception =>
      try Some(os.Path(uri))
      catch case _: Exception => None

  def archivePathOption(uri: String): Option[os.Path] =
    if uri.startsWith("jar:") then
      try Some(os.Path(java.net.URI.create(canonicalFileUri(uri))))
      catch case _: Exception => None
    else None
}
