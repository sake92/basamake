package ba.sake.basamake.bsp

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import com.typesafe.scalalogging.StrictLogging

/** Two-phase routing for multi-BSP workspaces.
  *
  * Primary: ground-truth source directories from BSP's buildTarget/sources
  *   (longest-prefix match via [[RoutingTable]]).
  * Fallback: nearest-ancestor .bsp/ heuristic, cached per directory.
  */
class BspRouter extends StrictLogging {

  // Primary routing (ground truth)
  // Maps connection ID → list of source directory URIs (from buildTarget/sources)
  private val routingTable: RoutingTable = RoutingTable.empty

  // Fallback routing (bootstrap heuristic)
  // Maps directory path → set of connection IDs in the nearest .bsp/ ancestor
  private val bootstrapCache: ConcurrentHashMap[Path, Option[Set[BspConnectionId]]] =
    ConcurrentHashMap()

  // .bsp directory path → connection IDs spawned from it
  private val bspRoots: AtomicReference[Map[Path, Set[BspConnectionId]]] =
    AtomicReference(Map.empty)

  /** Register a .bsp root directory and its connection IDs.
    * Called when attaching a new connection (at LSP init or watcher detects new .bsp/). */
  def registerBspRoot(bspDir: Path, connIds: Set[BspConnectionId]): Unit = {
    val canonical = canonicalize(bspDir)
    bspRoots.updateAndGet { current =>
      current + (canonical -> (current.getOrElse(canonical, Set.empty) ++ connIds))
    }
    logger.debug(s"Registered BSP root $canonical → ${bspRoots.get()(canonical)}")
  }

  /** Remove a connection from a .bsp root. Deletes the root only when it no longer owns any connection IDs. */
  def unregisterBspRoot(bspDir: Path, connId: BspConnectionId): Unit = {
    val canonical = canonicalize(bspDir)
    bspRoots.updateAndGet { current =>
      current.get(canonical) match
        case Some(connIds) =>
          val updated = connIds - connId
          if updated.nonEmpty then current + (canonical -> updated)
          else current - canonical
        case None => current
    }
    bootstrapCache.clear()
    logger.debug(s"Unregistered connection $connId from BSP root $canonical")
  }

  /** Canonicalize a path for use as a map key. Falls back to the normalized
    * absolute path when the directory no longer exists on disk (e.g. a .bsp/
    * directory deleted between discovery and detach) — must never throw. */
  private def canonicalize(p: Path): Path =
    try p.toRealPath()
    catch case _: java.io.IOException => p.toAbsolutePath.normalize()

  /** Register ground-truth source directories from a BSP handshake. */
  def registerGroundTruth(connId: BspConnectionId, sourceDirs: List[String]): Unit = {
    routingTable.update(connId, sourceDirs)
    logger.debug(s"Ground truth registered for $connId: ${sourceDirs.size} dirs")
    sourceDirs.foreach(d => logger.debug(s"  $d"))
  }

  /** Remove a connection from ground-truth routing (on detach). */
  def unregisterGroundTruth(connId: BspConnectionId): Unit =
    routingTable.remove(connId)

  /** Flush entire bootstrap cache. Called when .bsp/ dirs change. */
  def invalidateBootstrapCache(): Unit = {
    bootstrapCache.clear()
    logger.debug("Bootstrap cache invalidated")
  }

  /** Route a document URI to its owning BSP connection.
    * Layer 1 (primary): RoutingTable longest-prefix match.
    * Layer 2 (fallback): Bootstrap heuristic — walk up to nearest .bsp/ ancestor.
    * Returns None if no BSP found. */
  def route(uri: String): Option[BspConnectionId] = {
    val filePath = uriToPath(uri)
    val candidates = routingTable.reverseLookupCandidates(uri)
    candidates match
      case Nil          => routeBootstrap(uri, Some(filePath))
      case connId :: Nil => Some(connId)
      case many         => tieBreakByNearestBspRoot(filePath, many).orElse(many.sortBy(_.value).headOption)
  }

  /** Walk up from the file's parent directory to find the nearest registered .bsp root.
    * Results are cached per directory — subsequent lookups in the same tree skip the walk. */
  private def routeBootstrap(uri: String, knownPath: Option[Path] = None): Option[BspConnectionId] = {
    val filePath = knownPath.getOrElse(uriToPath(uri))
    var dir = filePath.getParent
    if dir == null then return None

    val roots = bspRoots.get()
    val visited = mutable.ListBuffer[Path]()
    var found: Option[Set[BspConnectionId]] = None

    while dir != null && found.isEmpty do
      bootstrapCache.get(dir) match
        case null =>
          visited += dir
          // Check if this directory has a .bsp/ subdir that we know about
          val bspSubdir = dir.resolve(".bsp")
          try
            val canonical = bspSubdir.toRealPath()
            roots.get(canonical) match
              case Some(connIds) if connIds.nonEmpty =>
                found = Some(connIds)
              case _ => ()
          catch case _: java.nio.file.NoSuchFileException => ()
        case cached =>
          found = cached
      // Walk up to parent (stop at root)
      dir = if dir.getParent != null && dir.getParent != dir then dir.getParent else null

    // Cache result for all visited directories
    for v <- visited do bootstrapCache.put(v, found)

    // Return deterministic connection ID if any found
    found.flatMap(_.toList.sortBy(_.value).headOption)
  }

  private def tieBreakByNearestBspRoot(filePath: Path, candidates: List[BspConnectionId]): Option[BspConnectionId] = {
    val candidateSet = candidates.toSet
    val roots = bspRoots.get()
    var dir = filePath.getParent
    while dir != null do
      val bspSubdir = dir.resolve(".bsp")
      try
        val canonical = bspSubdir.toRealPath()
        roots.get(canonical).map(_.intersect(candidateSet)) match
          case Some(overlap) if overlap.nonEmpty =>
            return overlap.toList.sortBy(_.value).headOption
          case _ => ()
      catch case _: java.nio.file.NoSuchFileException => ()
      dir = if dir.getParent != null && dir.getParent != dir then dir.getParent else null
    None
  }

  /** Convert a file:// URI to a java.nio.file.Path. */
  private def uriToPath(uri: String): Path =
    try
      val u = java.net.URI.create(uri)
      Path.of(u)
    catch
      case _: Exception =>
        // Fallback: strip file:// prefix, handle 2 or 3 slashes
        val stripped = uri.stripPrefix("file://").stripPrefix("file:///")
        Path.of("/" + stripped)
}
