package ba.sake.basamake.util

object UriUtils {
  def normalizeUri(uri: String): String =
    try java.nio.file.Path.of(java.net.URI.create(uri)).toUri.toString
    catch case _: Exception => uri
}
