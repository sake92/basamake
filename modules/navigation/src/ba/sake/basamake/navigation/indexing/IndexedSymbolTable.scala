package ba.sake.basamake.navigation.indexing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, SymbolUtils}

/** Read-only dependency/JDK symbol index over `~/.basamake/deps/<fingerprint>/` caches.
  *
  * Routing: a package → fingerprints map (built from each cache's metadata.json) decides
  * which indexes could contain a symbol. Lookups are live LMDB point queries
  * (`LmdbSerializer.get`) — nothing is ever loaded into memory, the env is opened
  * per query. The RAM saving is the whole point of LMDB here.
  *
  * `keys`, `byPath` and `all` are intentionally empty (workspace-scoped semantics)
  * and `add`/`removeByPath` are no-ops — dep/JDK references only matter for user
  * code, which lives in the workspace in-memory table; dependency symbols are
  * resolved by symbol only. Since the index is immutable (`removeByPath` is a
  * no-op), an always-empty result can never go stale. `ensureIndexed` /
  * `ensureJdkIndexed` kick off background indexing on virtual threads.
  */
class IndexedSymbolTable extends SymbolTable with StrictLogging {

  // full dotted package (as listed in metadata.json) → fingerprints defining it
  private val route = new ConcurrentHashMap[String, java.util.Set[String]]()
  // fingerprint → source path (for reindex after corruption)
  private val sourcesByFp = new ConcurrentHashMap[String, os.Path]()
  // per-fingerprint single-flight locks (per-file extraction only)
  private val fpLocks = new ConcurrentHashMap[String, Object]()
  // fingerprints currently being indexed (dedupe across targets/calls)
  private val indexing = ConcurrentHashMap.newKeySet[String]()
  private val jdkIndexing = new AtomicBoolean(false)

  // Bounds concurrent background indexing. Parsing sources jars with scalameta is
  // memory-hungry — one virtual thread per jar meant ~90 jars parsed at once on
  // startup, spiking the committed heap past 1GB (and G1 never returned it).
  // 2 concurrent indexes keep the peak low; wall time is barely affected (parsing
  // is CPU-bound and was time-sliced anyway).
  private val indexLimiter = new java.util.concurrent.Semaphore(2)

  // ── public extra API ──────────────────────────────────────────

  /** Ensure each source jar is cached (indexed in background if needed) and registered
    * for routing. Idempotent — safe to call from data.json warm start AND BSP handshake. */
  def ensureIndexed(sources: List[os.Path]): Unit = {
    sources.foreach { src =>
      if !os.exists(src) then logger.debug(s"Skipping missing dependency source $src")
      else {
        val fp = Fingerprint.fromJarPath(src)
        sourcesByFp.put(fp, src)
        if isCached(fp, src) then register(fp, src)
        else if indexing.add(fp) then {
          logger.info(s"Indexing dependency source ${src.last} in background")
          indexInBackground(src, fp)
        }
      }
    }
  }

  /** Ensure the JDK src.zip (`<java.home>/lib/src.zip`) is cached and registered.
    * No-op when the runtime has no sources. */
  def ensureJdkIndexed(): Unit = {
    val javaHome = os.Path(System.getProperty("java.home"))
    val srcZip = javaHome / "lib" / "src.zip"
    if !os.exists(srcZip) then logger.info(s"No JDK sources at $srcZip — skipping JDK index")
    else {
      val fp = Fingerprint.fromJdk(javaHome, System.getProperty("java.version"))
      sourcesByFp.put(fp, srcZip)
      if isCached(fp, srcZip) then register(fp, srcZip)
      else if jdkIndexing.compareAndSet(false, true) then {
        logger.info(s"Indexing JDK sources $srcZip in background")
        Thread.ofVirtual().start(() => {
          try {
            indexLimiter.acquire()
            try {
              SourceJarIndexer.index(srcZip, fp)
              register(fp, srcZip)
            } catch {
              case NonFatal(e) => logger.warn(s"Failed to index JDK sources: ${e.getMessage}")
            } finally indexLimiter.release()
          } finally jdkIndexing.set(false)
        })
      }
    }
  }

  // ── SymbolTable impl ──────────────────────────────────────────

  override def get(symbol: String): Option[SymbolDefinition] = {
    val pkgOpt = SymbolUtils.packageOf(symbol)
    if pkgOpt.isEmpty then None
    else {
      val fps = route.get(pkgOpt.get)
      if fps == null then None
      else {
        var result: Option[SymbolDefinition] = None
        val it = fps.asScala.toList.sorted.iterator // deterministic first-wins
        while result.isEmpty && it.hasNext do {
          val fp = it.next()
          try {
            LmdbSerializer.get(indexPath(fp), symbol).foreach { d =>
              ensureEntryExtracted(fp, d.path)
              result = Some(d)
            }
          } catch {
            case NonFatal(e) => handleCorrupt(fp, e)
          }
        }
        result
      }
    }
  }

  override def byPath(path: os.Path): Set[SymbolDefinition] = Set.empty
  // Dep/JDK references only matter for user code, which lives in the workspace
  // in-memory table (CompositeSymbolTable.byPath covers that). Dependency symbols
  // are resolved by symbol only (get). The index is immutable (removeByPath is a
  // no-op), so an always-empty result can never go stale.

  override def add(symDef: SymbolDefinition): Unit =
    logger.warn(s"IndexedSymbolTable is read-only — ignoring add of ${symDef.symbol}")

  override def removeByPath(path: os.Path): Unit = () // dependency tables are immutable

  override def keys: Set[String] = Set.empty

  override def all: Set[SymbolDefinition] = Set.empty
  // Nothing enumerates dep/JDK symbols in production: CompositeSymbolTable.all
  // reads only the workspace table (debug dumps, packagesOf run at index time on
  // the in-memory build table). Lookups are symbol-based point queries.

  // ── internals ─────────────────────────────────────────────────

  private def isCached(fp: String, source: os.Path): Boolean =
    val dir = SourceJarIndexer.cacheRoot / os.RelPath(fp)
    CacheMetadata.load(dir).exists(meta =>
      CacheMetadata.isValid(meta, source) && os.isDir(dir / "index.lmdb")
    )

  private def register(fp: String, source: os.Path): Unit = {
    CacheMetadata.load(SourceJarIndexer.cacheRoot / os.RelPath(fp)) match {
      case Some(meta) if CacheMetadata.isValid(meta, source) =>
        meta.packages.foreach { pkg =>
          route.computeIfAbsent(pkg, _ => ConcurrentHashMap.newKeySet[String]()).add(fp)
        }
      case _ => ()
    }
  }

  /** Corrupt/missing LMDB env surfaced by a query — wipe + reindex at most ONCE
    * per fingerprint: a concurrent reindex must not be killed by repeated wipes
    * from polling lookups. */
  private def handleCorrupt(fp: String, e: Throwable): Unit = {
    val dir = SourceJarIndexer.cacheRoot / os.RelPath(fp)
    logger.warn(s"Corrupt index at $dir — wiping and reindexing: ${e.getMessage}")
    if indexing.add(fp) then {
      os.remove.all(dir)
      Option(sourcesByFp.get(fp)) match {
        case Some(src) => indexInBackground(src, fp)
        case None      => indexing.remove(fp)
      }
    }
  }

  private def indexPath(fp: String): os.Path =
    SourceJarIndexer.cacheRoot / os.RelPath(fp) / "index.lmdb"

  /** Index one source in the background (virtual thread). Caller must have claimed
    * the fingerprint in `indexing`; the claim is released when the thread finishes.
    * Concurrent work is bounded by `indexLimiter` (see above). */
  private def indexInBackground(src: os.Path, fp: String): Unit = {
    Thread.ofVirtual().start(() => {
      try {
        indexLimiter.acquire()
        try {
          SourceJarIndexer.index(src, fp)
          register(fp, src)
        } catch {
          case NonFatal(e) => logger.warn(s"Failed to index $src: ${e.getMessage}")
        } finally indexLimiter.release()
      } finally indexing.remove(fp)
    })
  }

  /** Lazy per-file unpacking: indexes are built eagerly (LMDB only), but individual
    * source files are written to disk on first lookup hit — the LSP Location must
    * point at a real file for the editor to open it. Idempotent + single-flight per fp. */
  private def ensureEntryExtracted(fp: String, defPath: os.Path): Unit = {
    val srcRoot = SourceJarIndexer.cacheRoot / os.RelPath(fp) / "src"
    if (!defPath.startsWith(srcRoot)) return
    if (os.exists(defPath)) return
    fpLocks.computeIfAbsent(fp, _ => new Object).synchronized {
      if (!os.exists(defPath)) {
        Option(sourcesByFp.get(fp)) match {
          case Some(src) =>
            val entryPath = defPath.relativeTo(srcRoot).toString
            try SourceJarIndexer.extractEntry(src, fp, entryPath)
            catch { case NonFatal(e) => logger.warn(s"Failed to extract $entryPath for $fp: ${e.getMessage}") }
          case None =>
            logger.warn(s"No source known for $fp — cannot extract")
        }
      }
    }
  }
}
