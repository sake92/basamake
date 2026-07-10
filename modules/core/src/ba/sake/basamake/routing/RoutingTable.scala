package ba.sake.basamake.routing

import ba.sake.basamake.bsp.BspConnectionId

/** Thread-safe routing table mapping BSP connections to owned document URIs.
  * Uses longest-path-prefix matching. Each connection registers source directory
  * URIs from buildTarget/sources. All public methods are synchronized. */
// TODO use a more efficient data structure for longest-prefix matching (e.g., Trie)
// TODO concurrenthashmap?
final class RoutingTable private (private var entries: Map[BspConnectionId, List[String]]) {

  /** Update (or replace) the source directory prefixes for a connection. */
  def update(connId: BspConnectionId, sourceDirs: List[String]): Unit = synchronized {
    entries = entries + (connId -> sourceDirs)
  }

  def remove(connId: BspConnectionId): Unit = synchronized {
    entries = entries - connId
  }

  /** Finds the owning connection for a URI via longest-prefix match.
    * @return Some(connId) if a connection owns this URI, None if no connection owns this URI. */
  def reverseLookup(uri: String): Option[BspConnectionId] = synchronized {
    entries.flatMap { (connId, dirs) =>
      dirs.collect { case dir if uri.startsWith(dir) => (dir.length, connId) }
    }.maxByOption(_._1).map(_._2)
  }

  def lookup(connId: BspConnectionId): Set[String] = synchronized {
    entries.get(connId).map(_.toSet).getOrElse(Set.empty)
  }
}

object RoutingTable:
  def empty: RoutingTable = new RoutingTable(Map.empty)
