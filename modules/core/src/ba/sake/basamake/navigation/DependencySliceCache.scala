package ba.sake.basamake.navigation

import java.util.concurrent.ConcurrentHashMap

/** Cache of parsed dependency-source slices. Owned by BuildServerManager so it
  * survives BSP re-attach/reload (a NavigationIndex is recreated per attach).
  * Keyed per dep (uri + fingerprint) so one bumped dep invalidates only itself. */
final class DependencySliceCache {

  private val slices =
    new ConcurrentHashMap[DependencySourceIndexing.DepKey, List[SemanticdbFileSlice]]()

  def get(key: DependencySourceIndexing.DepKey): Option[List[SemanticdbFileSlice]] =
    Option(slices.get(key))

  def put(key: DependencySourceIndexing.DepKey, value: List[SemanticdbFileSlice]): Unit = {
    slices.put(key, value)
    ()
  }

  def size: Int = slices.size()
}
