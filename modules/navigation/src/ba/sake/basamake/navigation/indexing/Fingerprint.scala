package ba.sake.basamake.navigation.indexing

import java.security.MessageDigest

/** Cache-key derivation for `~/.basamake/deps/<fingerprint>/` index directories.
  *
  * The readable part comes from the source-jar filename (`<artifact>-<version>-sources.jar`
  * → `<artifact>_<version>`), so cache dirs are semi-readable when browsing
  * `~/.basamake/deps/`. The hash part is the first 8 hex chars of SHA-1 of the absolute
  * path — guarantees uniqueness even when two different repos/layouts share coordinates.
  */
object Fingerprint {

  private val MavenName = "^(.+)-(\\d.*)$".r

  /** Fingerprint for a source jar / zip (e.g. a coursier-cached `-sources.jar`). */
  def fromJarPath(jarPath: os.Path): String = {
    val name = jarPath.last
    val stripped = name.stripSuffix("-sources.jar").stripSuffix(".jar")
    val readable = stripped match {
      case MavenName(artifact, version) => s"${artifact}_$version"
      case _                            => stripped
    }
    s"${readable}_${hash8(jarPath.toString)}"
  }

  /** Fingerprint for the JDK source archive, derived from the runtime home.
    * Example: `jdk-21.0.2_a1b2c3d4`. */
  def fromJdk(javaHome: os.Path, javaVersion: String): String =
    s"jdk-${javaVersion}_${hash8(javaHome.toString)}"

  private def hash8(s: String): String = {
    val digest = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"))
    digest.take(4).map(b => f"$b%02x").mkString
  }
}
