package ba.sake.basamake.navigation.indexing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.{SymbolTable, InMemorySymbolTable, SymbolDefinition, SymbolUtils}

/** Read-only dependency/JDK symbol index over `~/.basamake/deps/<fingerprint>/` caches.
  *
  * Routing: a package → fingerprints map (built from each cache's metadata.json) decides
  * which indexes could contain a symbol before any table is loaded. Tables load lazily
  * into memory on first hit (single-flight per fingerprint) — LMDB stays the durable
  * format, lookups stay pure hashmap gets.
  *
  * `keys` is intentionally empty (workspace-scoped semantics) and `add`/`removeByPath`
  * are no-ops — dependency symbols are immutable once indexed. `ensureIndexed` /
  * `ensureJdkIndexed` kick off background indexing on virtual threads.
  */
class IndexedSymbolTable extends SymbolTable with StrictLogging {

  // full dotted package (as listed in metadata.json) → fingerprints defining it
  private val route = new ConcurrentHashMap[String, java.util.Set[String]]()
  // fingerprint → source path (for reindex after corruption)
  private val sourcesByFp = new ConcurrentHashMap[String, os.Path]()
  // fingerprint → loaded in-memory table
  private val loaded = new ConcurrentHashMap[String, InMemorySymbolTable]()
  // per-fingerprint single-flight load locks
  private val fpLocks = new ConcurrentHashMap[String, Object]()
  // fingerprints currently being indexed (dedupe across targets/calls)
  private val indexing = ConcurrentHashMap.newKeySet[String]()
  private val jdkIndexing = new AtomicBoolean(false)

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
          Thread.ofVirtual().start(() => {
            try {
              SourceJarIndexer.index(src, fp)
              register(fp, src)
            } catch {
              case NonFatal(e) => logger.warn(s"Failed to index $src: ${e.getMessage}")
            } finally indexing.remove(fp)
          })
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
            SourceJarIndexer.index(srcZip, fp)
            register(fp, srcZip)
          } catch {
            case NonFatal(e) => logger.warn(s"Failed to index JDK sources: ${e.getMessage}")
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
          ensureLoaded(it.next()).flatMap(_.get(symbol)).foreach(d => result = Some(d))
        }
        result
      }
    }
  }

  override def byPath(path: os.Path): Set[SymbolDefinition] =
    loaded.values().asScala.iterator.flatMap(_.byPath(path)).toSet

  override def add(symDef: SymbolDefinition): Unit =
    logger.warn(s"IndexedSymbolTable is read-only — ignoring add of ${symDef.symbol}")

  override def removeByPath(path: os.Path): Unit = () // dependency tables are immutable

  override def keys: Set[String] = Set.empty

  override def all: Set[SymbolDefinition] =
    loaded.values().asScala.iterator.flatMap(_.all).toSet

  // ── internals ─────────────────────────────────────────────────

  private def isCached(fp: String, source: os.Path): Boolean =
    val dir = SourceJarIndexer.cacheRoot / fp
    CacheMetadata.load(dir).exists(meta =>
      CacheMetadata.isValid(meta, source) && os.exists(dir / "index.lmdb")
    )

  private def register(fp: String, source: os.Path): Unit = {
    CacheMetadata.load(SourceJarIndexer.cacheRoot / fp) match {
      case Some(meta) if CacheMetadata.isValid(meta, source) =>
        meta.packages.foreach { pkg =>
          route.computeIfAbsent(pkg, _ => ConcurrentHashMap.newKeySet[String]()).add(fp)
        }
      case _ => ()
    }
  }

  private def ensureLoaded(fp: String): Option[InMemorySymbolTable] = {
    val existing = loaded.get(fp)
    if existing != null then Some(existing)
    else fpLocks.computeIfAbsent(fp, _ => new Object).synchronized {
      val again = loaded.get(fp)
      if again != null then Some(again)
      else {
        val dir = SourceJarIndexer.cacheRoot / fp
        try {
          val table = LmdbSerializer.load(dir / "index.lmdb")
          loaded.put(fp, table)
          logger.info(s"Loaded dep index $fp (${table.all.size} symbols)")
          Some(table)
        } catch {
          case NonFatal(e) =>
            logger.warn(s"Corrupt index at $dir — wiping and reindexing: ${e.getMessage}")
            os.remove.all(dir)
            Option(sourcesByFp.get(fp)).foreach(src => ensureIndexed(List(src)))
            None
        }
      }
    }
  }
}
