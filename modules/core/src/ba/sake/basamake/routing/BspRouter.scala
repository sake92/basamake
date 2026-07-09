package ba.sake.basamake.routing

import ba.sake.basamake.bsp.BspConnectionId
import com.typesafe.scalalogging.StrictLogging
import java.nio.file.{Files, Path}
import scala.collection.mutable

/** Two-phase routing for multi-BSP workspaces.
  *
  * Primary: ground-truth source directories from BSP's buildTarget/sources
  *   (longest-prefix match via [[RoutingTable]]).
  * Fallback: nearest-ancestor .bsp/ heuristic, cached per directory.
  */
class BspRouter extends StrictLogging {

  // Primary routing (ground truth)
  private val routingTable: RoutingTable = RoutingTable.empty

  // Fallback routing (bootstrap heuristic)
  // Maps directory path → set of connection IDs in the nearest .bsp/ ancestor
  private val bootstrapCache: mutable.HashMap[Path, Option[Set[BspConnectionId]]] =
    mutable.HashMap.empty

  // .bsp directory path → connection IDs spawned from it
  private var bspRoots: Map[Path, Set[BspConnectionId]] = Map.empty

  /** Register a .bsp root directory and its connection IDs.
    * Called when attaching a new connection (at LSP init or watcher detects new .bsp/). */
  def registerBspRoot(bspDir: Path, connIds: Set[BspConnectionId]): Unit = {
    val canonical = bspDir.toRealPath()
    bspRoots = bspRoots + (canonical -> (bspRoots.getOrElse(canonical, Set.empty) ++ connIds))
    logger.debug(s"Registered BSP root $canonical → ${bspRoots(canonical)}")
  }

  /** Remove a .bsp root (all its connections detached). */
  def unregisterBspRoot(bspDir: Path): Unit = {
    val canonical = bspDir.toRealPath()
    bspRoots = bspRoots - canonical
    logger.debug(s"Unregistered BSP root $canonical")
  }

  /** Register ground-truth source directories from a BSP handshake. */
  def registerGroundTruth(connId: BspConnectionId, sourceDirs: List[String]): Unit = {
    routingTable.update(connId, sourceDirs)
    logger.info(s"Ground truth registered for $connId: ${sourceDirs.size} dirs")
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
  def route(uri: String): Option[BspConnectionId] =
    routingTable.lookup(uri) match
      case some @ Some(_) => some
      case None           => routeBootstrap(uri)

  /** Walk up from the file's parent directory to find the nearest registered .bsp root.
    * Results are cached per directory — subsequent lookups in the same tree skip the walk. */
  private def routeBootstrap(uri: String): Option[BspConnectionId] = {
    val filePath = uriToPath(uri)
    var dir = filePath.getParent
    if dir == null then return None

    val visited = mutable.ListBuffer[Path]()
    var found: Option[Set[BspConnectionId]] = None

    while dir != null && found.isEmpty do
      bootstrapCache.get(dir) match
        case Some(cached) =>
          found = cached
        case None =>
          visited += dir
          // Check if this directory has a .bsp/ subdir that we know about
          val bspSubdir = dir.resolve(".bsp")
          try
            val canonical = bspSubdir.toRealPath()
            bspRoots.get(canonical) match
              case Some(connIds) if connIds.nonEmpty =>
                found = Some(connIds)
              case _ => ()
          catch case _: java.nio.file.NoSuchFileException => ()
      // Walk up to parent (stop at root)
      dir = if dir.getParent != null && dir.getParent != dir then dir.getParent else null

    // Cache result for all visited directories
    for v <- visited do bootstrapCache(v) = found

    // Return first connection ID if any found
    found.flatMap(_.headOption)
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
