package ba.sake.basamake.index.indexing

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import ba.sake.basamake.index.{ReferenceOccurrence, ResolvedFile, SymbolDefinition}

/** Mutable, concurrent state for one workspace's source files.
  *
  * This is deliberately separate from [[WorkspaceIndex]]: the index owns the
  * indexing and navigation policy, while this store owns source membership,
  * open-buffer state, stamps, and per-path serialization. All collections stay
  * package-private so neighbouring implementation helpers can use them without
  * widening WorkspaceIndex's public API. */
/** Per-source state. `occurrences` and `locals` are populated only while the
  * file is open; `semanticdbPath` survives tab close. */
private[indexing] final case class SourceData(
    occurrences: Vector[ReferenceOccurrence],
    locals: Vector[SymbolDefinition],
    semanticdbPath: Option[os.Path]
)

private[indexing] object SourceData {
  val empty: SourceData = SourceData(Vector.empty, Vector.empty, None)
}

private[indexing] final class WorkspaceSourceState {
  private[indexing] val sources = new ConcurrentHashMap[os.Path, SourceData]()
  private[indexing] val openFiles = ConcurrentHashMap.newKeySet[os.Path]()
  private[indexing] val diskStamps = new ConcurrentHashMap[os.Path, (Long, Long)]()
  private[indexing] val semanticdbStamps = new ConcurrentHashMap[os.Path, (Long, Long)]()

  private final case class SourceParseCacheEntry(
      stamp: (Long, Long),
      occurrences: Vector[ReferenceOccurrence],
      locals: Vector[SymbolDefinition]
  )
  private val maxSourceParseCacheEntries = 128
  private val sourceParseCache = new ConcurrentHashMap[os.Path, SourceParseCacheEntry]()
  private val pathLocks = new ConcurrentHashMap[os.Path, Object]()

  def withPathLock[T](path: os.Path)(body: => T): T =
    pathLocks.computeIfAbsent(path, _ => new Object).synchronized(body)

  def awaitBufferReady(path: os.Path): Unit = {
    var waited = 0
    while (!diskStamps.containsKey(path) && waited < 200) {
      Thread.sleep(10)
      waited += 1
    }
  }

  def awaitAllBuffersReady(): Unit = {
    var waited = 0
    while (waited < 200) {
      val pending = openFiles.asScala.exists(p => !diskStamps.containsKey(p))
      if (!pending) return
      Thread.sleep(10)
      waited += 1
    }
  }

  def cachedParse(path: os.Path, stamp: (Long, Long)): Option[ResolvedFile] = {
    val cached = sourceParseCache.get(path)
    Option.when(cached != null && cached.stamp == stamp)(ResolvedFile(cached.occurrences, cached.locals))
  }

  def cacheParse(path: os.Path, stamp: (Long, Long), resolved: ResolvedFile): Unit = {
    if (sourceParseCache.size() >= maxSourceParseCacheEntries) sourceParseCache.clear()
    sourceParseCache.put(path, SourceParseCacheEntry(stamp, resolved.occurrences, resolved.locals))
  }

  def remove(path: os.Path): Unit = {
    openFiles.remove(path)
    sources.remove(path)
    diskStamps.remove(path)
    sourceParseCache.remove(path)
  }
}
