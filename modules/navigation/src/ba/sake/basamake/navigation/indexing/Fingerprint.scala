package ba.sake.basamake.navigation.indexing

import java.security.MessageDigest
import scala.util.control.NonFatal

/** Cache-key derivation for `~/.basamake/deps/<fingerprint>/` index directories.
  *
  * The readable part comes from the maven coordinates: the groupId is read from the
  * sibling POM in the maven/coursier cache (`<artifact>-<version>.pom` next to the
  * sources jar, dots→underscores), the artifact/version from the jar filename. So
  * cache dirs are semi-readable when browsing `~/.basamake/deps/`. When no POM
  * exists (e.g. scala-lang published jars), it falls back to the filename-derived
  * scheme (`<artifact>_<version>`). The hash part is the first 8 hex chars of SHA-1
  * of the absolute path — guarantees uniqueness even when two repos/layouts share
  * coordinates.
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
    val groupPrefix = mavenGroupId(jarPath).map(_.replace('.', '_') + "-").getOrElse("")
    s"${groupPrefix}${readable}_${hash8(jarPath.toString)}"
  }

  /** Fingerprint for the JDK source archive, derived from the runtime home.
    * Example: `jdk-21.0.2_a1b2c3d4`. */
  def fromJdk(javaHome: os.Path, javaVersion: String): String =
    s"jdk-${javaVersion}_${hash8(javaHome.toString)}"

  /** groupId of the sources jar, from the sibling POM in the maven/coursier cache
    * (`<same-dir>/<artifact>-<version>.pom`). None when the pom is missing,
    * unparseable, or inherits its groupId from `<parent>` (no own `<groupId>`).
    * The pom is tiny — plain JDK DOM parsing is fine. */
  private def mavenGroupId(jarPath: os.Path): Option[String] = {
    if !jarPath.last.endsWith("-sources.jar") then return None
    val pom = jarPath / os.up / (jarPath.last.stripSuffix("-sources.jar") + ".pom")
    if !os.exists(pom) then return None
    try {
      val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
      dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      val project = dbf.newDocumentBuilder().parse(pom.toIO).getDocumentElement
      val children = project.getChildNodes
      var groupId: String = null
      var i = 0
      while (i < children.getLength && groupId == null) {
        val node = children.item(i)
        if (node.getNodeType == org.w3c.dom.Node.ELEMENT_NODE && node.getNodeName == "groupId") {
          groupId = node.getTextContent.trim
        }
        i += 1
      }
      Option(groupId).filter(_.nonEmpty)
    } catch {
      case NonFatal(e) => None
    }
  }

  private def hash8(s: String): String = {
    val digest = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"))
    digest.take(4).map(b => f"$b%02x").mkString
  }
}
