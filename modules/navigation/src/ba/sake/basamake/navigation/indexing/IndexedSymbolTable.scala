package ba.sake.basamake.navigation.indexing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, SymbolUtils}

/** Read-only dependency/JDK symbol index over `~/.basamake/deps/<fingerprint>/` caches.
  *
  * Routing: lookups are scoped to CANDIDATE jars (the current file's BSP target
  * dependency sources, passed by the caller) — precise and cheap: only the target's
  * jars are point-queried. When no candidates are known (no BSP, resolver pass),
  * a package → fingerprints map (built from each cache's metadata.json) decides
  * which indexes could contain a symbol. Lookups are live LMDB point queries
  * (`LmdbSerializer.get`) — nothing is ever loaded into memory, the env is opened
  * per query. The RAM saving is the whole point of LMDB here.
  *
  * `keys`, `byPath` and `all` are intentionally empty (workspace-scoped semantics)
  * and `add`/`removeByPath` are no-ops — dep/JDK references only matter for user
  * code, which lives in the workspace in-memory table; dependency symbols are
  * resolved by symbol only. Since the index is immutable (`removeByPath` is a
  * no-op), an always-empty result can never go stale.
  *
  * Indexing is LAZY and target-scoped: `registerTarget` only records a target's
  * dependency sources (and registers already-cached jars for routing) — nothing
  * is parsed. `ensureIndexed` / `ensureIndexedFor` kick off background indexing
  * of the UNCACHED jars on virtual threads; a lookup that meets an uncached jar
  * queues it too (single-flight) and returns empty — the next request succeeds.
  *
  * Memory: concurrent background indexing is bounded (`indexLimiter`, 2 permits —
  * parsing ~90 source jars concurrently used to spike committed heap past 1GB;
  * index writes are streamed into LMDB, so no in-memory symbol table is built).
  */
class IndexedSymbolTable extends SymbolTable with StrictLogging {

  // full dotted package (as listed in metadata.json) → fingerprints defining it
  private val route = new ConcurrentHashMap[String, java.util.Set[String]]()
  // fingerprint → packages (metadata.json content, immutable once indexed).
  // Cached in memory so candidate lookups skip the file read+JSON parse on
  // every keystroke — `register` is the single validation point that fills it.
  private val packagesByFp = new ConcurrentHashMap[String, Set[String]]()
  // fingerprint → source path (for reindex after corruption)
  private val sourcesByFp = new ConcurrentHashMap[String, os.Path]()
  // fingerprints whose index is cached AND registered in `route` — lets
  // ensureIndexed skip the metadata.json read+stat on every keystroke
  private val registeredFps = ConcurrentHashMap.newKeySet[String]()
  // targetId → dependency source jars (registered, NOT indexed — see class docs)
  private val targetDeps = new ConcurrentHashMap[String, List[os.Path]]()
  // per-fingerprint single-flight locks (per-file extraction only)
  private val fpLocks = new ConcurrentHashMap[String, Object]()
  // fingerprints currently being indexed (dedupe across targets/calls)
  private val indexing = ConcurrentHashMap.newKeySet[String]()
  private val jdkIndexing = new AtomicBoolean(false)
  // fingerprint → the candidate jars of the request that first resolved a symbol
  // INTO this jar ("reach context"). When the user navigates from a workspace
  // file into dep source, we remember the owning target's deps, so lookups from
  // inside the dep file can continue along the same dependency chain instead of
  // falling to the global package route (which can pick a DIFFERENT version of
  // the same library — e.g. scala-library 2.12 instead of 3.8.4).
  private val jarCandidates = new ConcurrentHashMap[String, List[os.Path]]()
  // fingerprint → nanoTime of the last successful hit. Biases the route fallback
  // toward what the user is actually browsing (recency beats version: a Scala
  // 2.12 project and a Scala 3 project open side by side must not fight).
  private val recentFps = new ConcurrentHashMap[String, Long]()
  private val MaxRecentFps = 128 // clear-on-overflow — a cleared LRU degrades to version ordering

  // Bounds concurrent background indexing. Parsing source jars with scalameta is
  // memory-hungry — one virtual thread per jar meant ~90 jars parsed at once on
  // startup, spiking the committed heap past 1GB. 2 concurrent indexes keep the
  // peak low; wall time is barely affected (parsing is CPU-bound and was
  // time-sliced anyway).
  private val indexLimiter = new java.util.concurrent.Semaphore(2)

  // ── public extra API ──────────────────────────────────────────

  /** Record a BSP target's dependency sources. Registers ALREADY-CACHED jars for
    * routing (so warm-start lookups work immediately) but indexes NOTHING — uncached
    * jars are indexed lazily by `ensureIndexed` / `ensureIndexedFor` / first lookup.
    * Idempotent — safe to call from data.json warm start AND every BSP handshake. */
  def registerTarget(targetId: String, sources: List[os.Path]): Unit = {
    targetDeps.put(targetId, sources)
    sources.foreach { src =>
      if os.exists(src) then {
        val fp = Fingerprint.fromJarPath(src)
        sourcesByFp.put(fp, src)
        if !registeredFps.contains(fp) && isCached(fp, src) then {
          register(fp, src)
          registeredFps.add(fp)
        }
      }
    }
  }

  /** Ensure the source jars of ONE target are cached: cached jars are registered for
    * routing, uncached ones are indexed in the background (single-flight per jar). */
  def ensureIndexedFor(targetId: String): Unit = {
    val sources = targetDeps.get(targetId)
    if (sources != null) ensureIndexed(sources)
  }

  /** Ensure each source jar is cached (indexed in background if needed) and registered
    * for routing. Idempotent and cheap after the first call — registered jars are
    * skipped without re-reading their metadata. */
  def ensureIndexed(sources: List[os.Path]): Unit = {
    sources.foreach { src =>
      if !os.exists(src) then logger.debug(s"Skipping missing dependency source $src")
      else {
        val fp = Fingerprint.fromJarPath(src)
        sourcesByFp.put(fp, src)
        if registeredFps.contains(fp) then ()
        else if isCached(fp, src) then {
          register(fp, src)
          registeredFps.add(fp)
        } else if indexing.add(fp) then {
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
      if registeredFps.contains(fp) then ()
      else if isCached(fp, srcZip) then {
        register(fp, srcZip)
        registeredFps.add(fp)
      } else if jdkIndexing.compareAndSet(false, true) then {
        logger.info(s"Indexing JDK sources $srcZip in background")
        Thread.ofVirtual().start(() => {
          try {
            indexLimiter.acquire()
            try {
              SourceJarIndexer.index(srcZip, fp)
              register(fp, srcZip)
              registeredFps.add(fp)
            } catch {
              case NonFatal(e) => logger.warn(s"Failed to index JDK sources: ${e.getMessage}")
            } finally indexLimiter.release()
          } finally jdkIndexing.set(false)
        })
      }
    }
  }

  // ── SymbolTable impl ──────────────────────────────────────────

  /** Candidate jars relevant to a file that lives INSIDE the deps cache (an
    * extracted dep source opened via goto-def): the jar owning the file (derived
    * from the cache path — `<cacheRoot>/<fp>/src/<entry>`), plus the candidate
    * context that first resolved into that jar ([[jarCandidates]]), if any.
    *
    * The BspManager feeds this into `get(symbol, candidates)` so lookups from
    * dep files stay scoped to the right jar. Without it they'd fall to the
    * global package route, which can resolve a symbol from a DIFFERENT version
    * of the same library (e.g. scala-library 2.12 instead of 3.8.4).
    *
    * JDK files are excluded: src.zip can't round-trip `Fingerprint.fromJarPath`,
    * and the JDK is the only registerer of `java.*` packages, so the route
    * already resolves them correctly.
    *
    * The source path is recovered from metadata.json when the jar isn't
    * registered this session (file extracted by a previous session — indexing
    * is lazy, so `sourcesByFp` may not know it yet). */
  def candidatesForPath(path: os.Path): List[os.Path] = {
    val root = SourceJarIndexer.cacheRoot
    if (!path.startsWith(root)) return Nil
    val segments = path.relativeTo(root).segments
    val srcIdx = segments.indexOf("src")
    if (srcIdx <= 0) return Nil // need at least the fingerprint segment before src/
    val fp = segments.take(srcIdx).mkString("/")
    if (fp.startsWith("jdk-")) return Nil
    val own = Option(sourcesByFp.get(fp)).toList
    val fromMeta = if (own.isEmpty) {
      // not registered this session — recover the source jar from the cache metadata
      CacheMetadata.load(root / os.RelPath(fp))
        .map(_.sourcePath)
        .filter(p => os.exists(os.Path(p)))
        .map(os.Path(_))
        .toList
    } else Nil
    val reached = Option(jarCandidates.get(fp)).getOrElse(Nil)
    (own ++ fromMeta ++ reached).distinct
  }

  /** Global-route lookup (fallback when no candidate jars are known). */
  override def get(symbol: String): Option[SymbolDefinition] = get(symbol, Nil)

  /** Candidate-scoped lookup: point-query ONLY the given jars (the current file's
    * BSP target dependency sources). More precise than the global route (a symbol
    * shared by two jars resolves to the target's jar, not sorted first-wins) and
    * cheaper (no queries against unrelated targets). Uncached candidates are queued
    * for background indexing and skipped — an empty result is transient, the next
    * request succeeds. Falls back to the global route on a miss (covers the JDK,
    * which is never part of a target's dependency sources). */
  def get(symbol: String, candidates: List[os.Path]): Option[SymbolDefinition] = {
    if (candidates.isEmpty) getFromRoute(symbol)
    else getFromCandidates(symbol, candidates).orElse(getFromRoute(symbol))
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
        packagesByFp.put(fp, meta.packages.toSet) // put, not putIfAbsent — a re-register after reindex must overwrite
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
      registeredFps.remove(fp)
      os.remove.all(dir)
      Option(sourcesByFp.get(fp)) match {
        case Some(src) => indexInBackground(src, fp)
        case None      => indexing.remove(fp)
      }
    }
  }

  private def indexPath(fp: String): os.Path =
    SourceJarIndexer.cacheRoot / os.RelPath(fp) / "index.lmdb"

  /** Packages of one fingerprint — pure in-memory lookup of the value captured
    * at `register` time (metadata.json is immutable once the index is created,
    * so no file read is ever needed for registered jars). Falls back to a
    * one-time metadata.json read ONLY for the rare cached-but-not-yet-registered
    * window and populates the cache — the steady state is a map hit. */
  private def metadataPackages(fp: String): Option[Set[String]] =
    Option(packagesByFp.get(fp)).orElse {
      Option(sourcesByFp.get(fp)).flatMap { src =>
        CacheMetadata.load(SourceJarIndexer.cacheRoot / os.RelPath(fp))
          .filter(meta => CacheMetadata.isValid(meta, src))
          .map(meta => {
            val pkgs = meta.packages.toSet
            packagesByFp.put(fp, pkgs)
            pkgs
          })
      }
    }

  /** Candidate-scoped point queries. Iterates the candidate jars in order; first
    * hit wins. Uncached candidates are queued for background indexing (single-flight)
    * so a retry resolves them — never blocks the request. */
  private def getFromCandidates(symbol: String, candidates: List[os.Path]): Option[SymbolDefinition] = {
    val pkgOpt = SymbolUtils.packageOf(symbol)
    if pkgOpt.isEmpty then return None
    val pkg = pkgOpt.get
    var result: Option[SymbolDefinition] = None
    val it = candidates.iterator
    while result.isEmpty && it.hasNext do {
      val src = it.next()
      if os.exists(src) then {
        val fp = Fingerprint.fromJarPath(src)
        if registeredFps.contains(fp) || isCached(fp, src) then {
          // package pre-filter: only query jars whose metadata lists the package
          metadataPackages(fp) match {
            case Some(pkgs) if pkgs.contains(pkg) =>
              try {
                LmdbSerializer.get(indexPath(fp), symbol).foreach { d =>
                  ensureEntryExtracted(fp, d.path)
                  jarCandidates.put(fp, candidates)
                  touchRecent(fp)
                  result = Some(d)
                }
              } catch {
                case NonFatal(e) => handleCorrupt(fp, e)
              }
            case _ => ()
          }
        } else if indexing.add(fp) then {
          logger.info(s"Indexing dependency source ${src.last} in background (lookup miss)")
          indexInBackground(src, fp)
        }
      }
    }
    result
  }

  /** Fallback lookup through the package-route map (built from the metadata.json of
    * every registered jar). Ordering: recently-hit fingerprints first (recency LRU —
    * the user's current working set wins, e.g. a Scala 2.12 project next to a Scala
    * 3 project), then higher versions first (an old scala-library must never beat a
    * new one), then lexicographic as a deterministic tie-break. The candidate path
    * above is preferred — the route is only a best-effort fallback. */
  private def getFromRoute(symbol: String): Option[SymbolDefinition] = {
    val pkgOpt = SymbolUtils.packageOf(symbol)
    if pkgOpt.isEmpty then None
    else {
      val fps = route.get(pkgOpt.get)
      if fps == null then None
      else {
        val ordered = fps.asScala.toList.sortWith { (a, b) =>
          val la = recentFps.getOrDefault(a, -1L)
          val lb = recentFps.getOrDefault(b, -1L)
          if (la != lb) la > lb
          else {
            val va = versionOf(a)
            val vb = versionOf(b)
            (va, vb) match {
              case (Some(x), Some(y)) if x != y => cmpVersion(x, y) > 0
              case _                            => a < b
            }
          }
        }
        if (fps.size() > 1) logger.debug(
          s"Route fallback for $symbol: ${fps.size()} jar(s) register package $pkgOpt, picked ${ordered.headOption.getOrElse("?")}"
        )
        var result: Option[SymbolDefinition] = None
        val it = ordered.iterator
        while result.isEmpty && it.hasNext do {
          val fp = it.next()
          try {
            LmdbSerializer.get(indexPath(fp), symbol).foreach { d =>
              ensureEntryExtracted(fp, d.path)
              touchRecent(fp)
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

  /** Record a successful hit for the route-fallback recency bias. Bounded: an
    * overflowing LRU is cleared wholesale — a cold LRU just means the fallback
    * degrades to version-first ordering. */
  private def touchRecent(fp: String): Unit = {
    recentFps.put(fp, System.nanoTime())
    if (recentFps.size() > MaxRecentFps) recentFps.clear()
  }

  /** Numeric version of a fingerprint's last segment, for route ordering.
    * `scala-library_3.8.4_<hash>` → Some(List(3, 8, 4)); unparseable names
    * (`a-sources_<hash>`) → None (falls back to lexicographic). */
  private val HashSuffixRe = "_[0-9a-f]{8}$$".r
  private val VersionTailRe = "_([0-9][^_]*)$$".r
  private def versionOf(fp: String): Option[List[Long]] = {
    val name = HashSuffixRe.replaceFirstIn(fp.split('/').last, "")
    // findFirstMatchIn, NOT the regex extractor — `case VersionTailRe(v)` would
    // require a FULL-string match and never hit a version suffix
    VersionTailRe.findFirstMatchIn(name).map(_.group(1)).flatMap { v =>
      val parsed = v.split('.').toList.map(_.toLongOption)
      if parsed.forall(_.isDefined) then Some(parsed.flatten) else None
    }
  }

  /** Element-wise version comparison: [3, 8, 4] > [2, 12, 21]; a prefix is
    * smaller (2.12 < 2.12.21). */
  private def cmpVersion(a: List[Long], b: List[Long]): Int = {
    val n = math.min(a.length, b.length)
    var i = 0
    while i < n do {
      if (a(i) != b(i)) return a(i).compare(b(i))
      i += 1
    }
    a.length.compare(b.length)
  }

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
          registeredFps.add(fp)
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
