package ba.sake.basamake.index.indexing

import java.security.MessageDigest
import scala.util.control.NonFatal

/** Cache-key derivation for `~/.cache/basamake/deps/<fingerprint>/` index directories.
  *
  * The fingerprint is the cache dir RELATIVE to the cache root (joined with
  * `os.RelPath`, never parsed back). The groupId — read from the sibling POM in
  * the maven/coursier cache (`<artifact>-<version>.pom` next to the sources jar,
  * dots→underscores) — becomes a directory: `com_lihaoyi/upickle_3_4.0.0_<hash>`.
  * When no POM exists (e.g. scala-lang published jars), it stays flat:
  * `antlr4-runtime_4.7.2_<hash>`. The hash part is the first 8 hex chars of SHA-1
  * of the absolute jar path — guarantees uniqueness even when two repos/layouts
  * share coordinates.
  */
object Fingerprint {

  private val MavenName = "^(.+)-(\\d.*)$".r

  // Fingerprint memo: jar paths (and their sibling POMs) are immutable in the
  // coursier cache, so a parsed POM never goes stale. This matters because
  // IndexedSymbolTable.get calls fromJarPath for EVERY candidate jar on every
  // lookup — a DOM parse of the POM per call measured ~0.4-1.1ms, which with
  // ~370 dep jars per target added up to ~100-400ms per goto-def/hover.
  // Bounded: clear-on-overflow keeps the memo from growing unbounded.
  private val MaxCachedFingerprints = 10000
  private val fingerprintCache = new java.util.concurrent.ConcurrentHashMap[String, String]()

  /** Relative cache dir for a source jar / zip (e.g. a coursier-cached `-sources.jar`). */
  def fromJarPath(jarPath: os.Path): String = {
    val key = jarPath.toString
    val cached = fingerprintCache.get(key)
    if cached != null then cached
    else {
      val computed = computeFingerprint(jarPath)
      if fingerprintCache.size() >= MaxCachedFingerprints then fingerprintCache.clear()
      fingerprintCache.put(key, computed)
      computed
    }
  }

  private def computeFingerprint(jarPath: os.Path): String = {
    val name = jarPath.last
    val stripped = name.stripSuffix("-sources.jar").stripSuffix(".jar")
    val readable = stripped match {
      case MavenName(artifact, version) => s"${artifact}_$version"
      case _                            => stripped
    }
    val group = mavenGroupId(jarPath).map(_.replace('.', '_'))
    val hashed = s"${readable}_${hash8(jarPath.toString)}"
    group match {
      case Some(g) => s"$g/$hashed"
      case None    => hashed
    }
  }

  /** Fingerprint for the JDK source archive, derived from the runtime home.
    * Example: `jdk-21.0.2_a1b2c3d4`. */
  def fromJdk(javaHome: os.Path, javaVersion: String): String =
    s"jdk-${javaVersion}_${hash8(javaHome.toString)}"

  /** Maven coordinates of a sources jar (coursier layout), for user-facing
    * messages: `(groupId, artifactId, version)` — groupId from the sibling POM,
    * artifactId + version from the file name (`<artifact>-<version>-sources.jar`).
    * None when the layout is not a maven sources jar (no POM, no version pattern). */
  def mavenCoordinates(jarPath: os.Path): Option[(String, String, String)] = {
    if !jarPath.last.endsWith("-sources.jar") then return None
    jarPath.last.stripSuffix("-sources.jar") match {
      case MavenName(artifact, version) => mavenGroupId(jarPath).map(group => (group, artifact, version))
      case _                            => None
    }
  }

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
