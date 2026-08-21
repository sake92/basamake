package ba.sake.basamake.index.indexing

import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.index.{SymbolDefinition, SymbolUtils}

/** Read-only dependency/JDK symbol index over `<cacheRoot>/<fingerprint>/` caches
  * (cacheRoot is a constructor parameter — each server owns its cache location;
  * tests point it at ./tmp/, production derives it from config).
  *
  * Deterministic pipeline — no global routing, no heuristics:
  *   1. The caller passes the candidate source jars of the file's BSP target.
  *      Dep files get TARGET-SCOPED candidate sets instead: the owning jar
  *      first, then the union of the dep lists of every target that registered
  *      it (a target's list is its fully resolved classpath — see
  *      `candidatesForPath`).
  *   2. Each candidate is package-filtered via metadata.json: package-only
  *      metadata (from the classes jar) is sprinkled eagerly by `registerTarget`;
  *      a full index replaces it with parse-accurate packages.
  *   3. Only jars whose packages include the symbol's package are touched — a
  *      matching jar without an index is indexed IN THE BACKGROUND (single-flight
  *      per jar, globally bounded, progress events) while the lookup misses fast;
  *      its source files stay unpacked until a lookup hits them.
  *   4. `LmdbSerializer.get` does one exact LMDB point query per matching jar;
  *      a method-shaped miss falls back to a bounded prefix scan (overloads
  *      are indexed under `(+N)` keys).
  *   5. The first hit wins; if no candidate jar hits, the lookup ends with
  *      `None` — there is no other fallback search.
  *
  * The JDK `src.zip` is an implicit candidate (a dependency of every target that
  * BSP never lists); the package filter keeps it out of non-`java.*` lookups. It
  * is indexed eagerly at startup by a background thread; until that finishes, a
  * `java.*` lookup is a fast transient miss (the JDK is never indexed inline —
  * src.zip takes minutes). A dep jar with no metadata (no classes-jar sibling)
  * is unfilterable: it gets fully indexed on demand, which records its (possibly
  * empty) package set.
  */
class IndexedSymbolTable(
    progressListener: IndexingProgressListener = IndexingProgressListener.noop,
    /** Root of the dep/JDK cache. Instance-level on purpose: each server owns
      * its cache location (tests point it at ./tmp/); no global mutation. */
    cacheRoot: os.Path = SourceJarIndexer.defaultCacheRoot
) extends StrictLogging {

  // fingerprint → packages (from metadata.json: classes-jar-derived pre-index,
  // parse-accurate after). Memory cache; absence = "no metadata" (unfilterable).
  private val packagesByFingerprint = new ConcurrentHashMap[String, Set[String]]()
  // fingerprint → source jar path (ownership for dep-file lookups, extraction, reindex)
  private val sourcesByFingerprint = new ConcurrentHashMap[String, os.Path]()
  // fingerprint → target ids that registered it (a jar can serve multiple targets)
  private val targetsByFingerprint = new ConcurrentHashMap[String, Set[String]]()
  // target id → source jars of that target (the FULL resolved classpath from BSP)
  private val sourcesByTarget = new ConcurrentHashMap[String, List[os.Path]]()
  // fingerprints with a fully built index — lookups skip the metadata read + lock
  private val indexedFingerprints = ConcurrentHashMap.newKeySet[String]()
  // metadata generation in flight (single-flight per fingerprint)
  private val metadataInProgress = ConcurrentHashMap.newKeySet[String]()
  // per-fingerprint single-flight locks (per-file extraction only)
  private val fingerprintLocks = new ConcurrentHashMap[String, Object]()
  // fingerprints with no known source — warn once, not per lookup
  private val noSourceFingerprints = ConcurrentHashMap.newKeySet[String]()
  // fingerprints whose LMDB query failed — warn once per session, not per lookup
  private val queryFailureWarned = ConcurrentHashMap.newKeySet[String]()
  // fingerprints with a background index in flight (single-flight per jar:
  // a duplicate trigger is a no-op, and a FAILED index unmarks so a later
  // lookup retries)
  private val indexingInProgress = ConcurrentHashMap.newKeySet[String]()
  /** Max concurrent background jar/JDK indexes. Default: leave one CPU for the
    * build server and interactive work, capped at 4 — beyond that jar parsing
    * is memory-bandwidth bound, not CPU bound, and would starve a parallel
    * scalac compile of the machine. Tests lower it to prove the cap. */
  @volatile var maxConcurrentIndexes: Int =
    math.max(1, math.min(Runtime.getRuntime.availableProcessors() - 1, 4))
  private lazy val indexPermits = new java.util.concurrent.Semaphore(maxConcurrentIndexes)
  /** Background indexes currently running (test instrumentation). */
  private[indexing] val activeIndexCount = new java.util.concurrent.atomic.AtomicInteger(0)
  /** Background index spawns (test instrumentation). */
  private[indexing] val backgroundIndexStarts = new java.util.concurrent.atomic.AtomicInteger(0)
  /** Background indexes completed, success or failure (test instrumentation).
    * Incremented in the index thread's finally — the reliable "done" signal:
    * activeIndexCount is 0 both BEFORE the thread first runs and AFTER it
    * finishes, so it can't distinguish the two. */
  private[indexing] val backgroundIndexCompletions = new java.util.concurrent.atomic.AtomicInteger(0)

  /** JDK sources zip + its fingerprint — implicit candidate for every lookup. */
  private val jdkSource: Option[(os.Path, String)] = {
    val javaHome = os.Path(System.getProperty("java.home"))
    val srcZip = javaHome / "lib" / "src.zip"
    if os.exists(srcZip) then
      Some(srcZip -> Fingerprint.fromJdk(javaHome, System.getProperty("java.version")))
    else None
  }

  // ── public extra API ──────────────────────────────────────────

  /** Record a BSP target's dependency source jars and sprinkle the package-only
    * metadata.json for each (classes-jar directory scan — cheap, background,
    * single-flight, persisted). Nothing is INDEXED here; full indexing happens
    * on demand inside `get`. Idempotent — safe from the data.json warm start
    * AND every BSP handshake. `targetId` scopes the jar set for cross-jar
    * candidate derivation (dep files resolve against the union of classpaths of
    * the targets that contain their owning jar). */
  def registerTarget(sources: List[os.Path]): Unit = registerTarget(None, sources)

  /** Register with an explicit target id (BSP tid or warm-start source root). */
  def registerTarget(targetId: String, sources: List[os.Path]): Unit =
    registerTarget(Some(targetId), sources)

  private def registerTarget(targetId: Option[String], sources: List[os.Path]): Unit = {
    val fingerprints = sources.flatMap { src =>
      if os.exists(src) then {
        val fingerprint = Fingerprint.fromJarPath(src)
        sourcesByFingerprint.put(fingerprint, src)
        ensureMetadata(fingerprint, src)
        Some(fingerprint)
      } else None
    }
    targetId.foreach { tid =>
      sourcesByTarget.put(tid, sources)
      fingerprints.foreach { fp =>
        targetsByFingerprint.compute(fp, (_, old) => Option(old).getOrElse(Set.empty) + tid)
      }
    }
  }

  /** Index the JDK sources once in the background (big jar — a cold first
    * `java.*` lookup must not block on it). No-op when cached or when the
    * runtime has no sources. */
  def ensureJdkIndexed(): Unit = {
    jdkSource.foreach { case (srcZip, fingerprint) =>
      sourcesByFingerprint.put(fingerprint, srcZip)
      if !isFullyIndexed(fingerprint) then {
        logger.info(s"Indexing JDK sources $srcZip in background")
        startBackgroundIndex(fingerprint, srcZip, IndexingPhase.Jdk)
      }
    }
  }

  /** Candidate jars relevant to a file that lives INSIDE the deps cache (an
    * extracted dep source opened via goto-def): the jar owning the file FIRST
    * (deterministic precedence), then the union of the dep lists of every
    * target that registered the owning jar — a target's list is its fully
    * resolved classpath, so refs inside the file see exactly what the compiler
    * saw. Owning jar recovered from metadata.json when not registered this
    * session. JDK files return nothing — the implicit JDK candidate in `get`
    * covers them. */
  def candidatesForPath(path: os.Path): List[os.Path] = {
    val root = cacheRoot
    if (!path.startsWith(root)) return Nil
    val segments = path.relativeTo(root).segments
    val srcIdx = segments.indexOf("src")
    if (srcIdx <= 0) return Nil
    val fingerprint = segments.take(srcIdx).mkString("/")
    if (jdkSource.exists(_._2 == fingerprint)) return Nil
    val own = Option(sourcesByFingerprint.get(fingerprint)).toList
    val fromMeta = if (own.isEmpty) {
      CacheMetadata.load(root / os.RelPath(fingerprint))
        .map(_.sourcePath)
        .filter(p => os.exists(os.Path(p)))
        .map(os.Path(_))
        .toList
    } else Nil
    val owning = (own ++ fromMeta).distinct
    if (owning.isEmpty) Nil
    else {
      val tids = Option(targetsByFingerprint.get(fingerprint)).getOrElse(Set.empty)
      val union = tids.toList.flatMap(tid => Option(sourcesByTarget.get(tid)).getOrElse(Nil))
      (owning ++ union).distinct
    }
  }

  /** The ONLY dependency lookup. See the class docs for the pipeline. */
  def get(symbol: String, candidates: List[os.Path]): Option[SymbolDefinition] = {
    val pkgOpt = SymbolUtils.packageOf(symbol)
    if pkgOpt.isEmpty then return None // default-package symbols are not resolvable
    val pkg = pkgOpt.get
    val all = candidates.map(c => c -> Fingerprint.fromJarPath(c)) ++ jdkSource.toList
    val it = all.iterator
    var found: Option[SymbolDefinition] = None
    while it.hasNext && found.isEmpty do {
      val (src, fingerprint) = it.next()
      if os.exists(src) then {
        val pkgsOpt = packagesOf(fingerprint, src) // None = no metadata → unfilterable
        if pkgsOpt.forall(_.contains(pkg)) then {
          if ensureIndexed(fingerprint, src) then {
            try {
              LmdbSerializer.get(indexPath(fingerprint), symbol) match {
                case Some(d) =>
                  ensureEntryExtracted(fingerprint, d.path)
                  found = Some(d)
                case None =>
                  // Method-shaped miss → prefix scan for the true overloads
                  // (exact key carried a wrong/unknown overload index — e.g.
                  // parse-time member candidates are always index-0). The scan
                  // is bounded and package-filtered; it replaces the exact miss
                  // ONLY for this shape — type/term misses stay hard misses.
                  if (isMethodShaped(symbol)) {
                    val prefix = symbol.takeWhile(_ != '(') + "("
                    LmdbSerializer.getPrefix(indexPath(fingerprint), prefix).headOption.foreach { d =>
                      ensureEntryExtracted(fingerprint, d.path)
                      found = Some(d)
                    }
                  }
              }
            } catch {
              case NonFatal(e) =>
                if queryFailureWarned.add(fingerprint) then
                  logger.warn(s"LMDB query failed for $fingerprint: ${e.getMessage} — returning None (delete the cache dir to rebuild)")
            }
          }
        }
      }
    }
    found
  }

  // ── internals ─────────────────────────────────────────────────

  /** True for method/constructor-shaped symbols: `name().`, `name(+N).`. */
  private def isMethodShaped(symbol: String): Boolean =
    symbol.matches(".*\\(\\+\\d*\\)\\.")

  private def indexPath(fingerprint: String): os.Path =
    cacheRoot / os.RelPath(fingerprint) / "index.lmdb"

  private def cacheDir(fingerprint: String): os.Path =
    cacheRoot / os.RelPath(fingerprint)

  /** Packages of one fingerprint, memory-cached. None = no metadata → the jar
    * can't be filtered and is indexed on demand instead. */
  private def packagesOf(fingerprint: String, src: os.Path): Option[Set[String]] =
    if packagesByFingerprint.containsKey(fingerprint) then Option(packagesByFingerprint.get(fingerprint))
    else {
      val pkgs = accuratePackages(fingerprint).orElse {
        CacheMetadata.load(cacheDir(fingerprint))
          .filter(meta => !meta.indexed && CacheMetadata.isValid(meta, src))
          .map(_.packages.toSet) // package-only sprinkle metadata (indexed = false)
      }
      pkgs.foreach(packagesByFingerprint.put(fingerprint, _))
      pkgs
    }

  /** Parse-accurate packages recorded by a full index, if the index exists.
    * Indexed jars WITHOUT packages (empty-stub sources jars — scala3-library
    * stubs, the pre-fix JDK caches) get their packages derived from the source
    * archive ONCE, synchronously: a stub stays `Some(empty)` (every lookup is
    * then package-filtered OUT — no pointless LMDB probes), a real archive
    * (e.g. the JDK src.zip) becomes filterable and `isFullyIndexed` turns true,
    * which also stops the per-session "Indexing JDK sources" attempt. */
  private def accuratePackages(fingerprint: String): Option[Set[String]] =
    CacheMetadata.load(cacheDir(fingerprint)).flatMap { meta =>
      val valid = Option(sourcesByFingerprint.get(fingerprint)) match {
        case Some(src) => CacheMetadata.isValid(meta, src)
        case None      => true // source unknown this session — trust the cache
      }
      if valid && meta.indexed && os.isDir(cacheDir(fingerprint) / "index.lmdb") then {
        if (meta.packages.nonEmpty) Some(meta.packages.toSet)
        else {
          val src = Option(sourcesByFingerprint.get(fingerprint)).map(_.toString).getOrElse(meta.sourcePath)
          val pkgs =
            try SourceJarIndexer.packagesOfSource(os.Path(src))
            catch { case NonFatal(e) => logger.warn(s"Failed to derive packages for $src: ${e.getMessage}"); Set.empty }
          if (pkgs.nonEmpty) {
            try CacheMetadata.save(cacheDir(fingerprint), meta.copy(packages = pkgs.toList.sorted))
            catch { case NonFatal(e) => logger.warn(s"Failed to persist packages for $fingerprint: ${e.getMessage}") }
          }
          Some(pkgs)
        }
      } else None
    }

  private def isFullyIndexed(fingerprint: String): Boolean = accuratePackages(fingerprint).isDefined

  /** Sprinkle the package-only metadata.json (from the classes jar) — cheap zip
    * directory scan, background + single-flight, persisted. Runs once per jar
    * per machine; absent classes jar → no metadata (jar stays unfilterable).
    * Never overwrites an accurate post-index metadata (checked again before save). */
  private def ensureMetadata(fingerprint: String, src: os.Path): Unit = {
    val current = CacheMetadata.load(cacheDir(fingerprint)).exists(meta => CacheMetadata.isValid(meta, src))
    if !current && metadataInProgress.add(fingerprint) then {
      Thread.ofVirtual().start(() => {
        try {
          SourceJarIndexer.classesJarOf(src).foreach { classesJar =>
            val pkgs = SourceJarIndexer.packagesOfClassesJar(classesJar)
            if pkgs.nonEmpty && !isFullyIndexed(fingerprint) then {
              CacheMetadata.save(cacheDir(fingerprint), CacheMetadata(
                sourcePath = src.toString,
                sourceSize = os.size(src),
                sourceMtime = os.mtime(src),
                packages = pkgs.toList.sorted,
                indexed = false,
                formatVersion = CacheMetadata.FormatVersion
              ))
            }
          }
        } catch {
          case NonFatal(e) => logger.warn(s"Failed to derive packages for $src: ${e.getMessage}")
        } finally {
          metadataInProgress.remove(fingerprint)
        }
      })
    }
  }

  /** Fast path: returns true only when the jar's index is usable NOW (in-memory
    * or on disk from an earlier session/server). A cold jar is NEVER indexed
    * inline — the lookup misses fast and the index is built on a background
    * virtual thread. The JDK is the exception: only the startup background
    * thread (ensureJdkIndexed) indexes it — a cold JDK lookup is a fast
    * transient miss instead of a minutes-long block. */
  private def ensureIndexed(fingerprint: String, src: os.Path): Boolean =
    if indexedFingerprints.contains(fingerprint) then true
    else if jdkSource.exists(_._2 == fingerprint) && !isFullyIndexed(fingerprint) then false
    else if isFullyIndexed(fingerprint) then { // indexed by an earlier session/server
      indexedFingerprints.add(fingerprint)
      true
    } else {
      startBackgroundIndex(fingerprint, src, IndexingPhase.Dependencies)
      false
    }

  /** Single-flight background index of one jar, triggered by the first lookup
    * that needs it (the JDK uses the same path from ensureJdkIndexed).
    * Later lookups miss fast until the index is ready. Concurrency: at most
    * `maxConcurrentIndexes` sources index at once (global semaphore — virtual
    * threads park on it, no carrier pinning); the same fingerprint never
    * indexes twice (indexingInProgress set). A failed index unmarks the
    * fingerprint so a later lookup retries. Cross-server races on the shared
    * cache dir are still serialized by SourceJarIndexer's per-fingerprint
    * ReentrantLock. */
  private def startBackgroundIndex(fingerprint: String, src: os.Path, phase: IndexingPhase): Unit =
    if indexingInProgress.add(fingerprint) then {
      backgroundIndexStarts.incrementAndGet()
      Thread.ofVirtual().start(() => {
        try {
          // uninterruptible park: these threads are never interrupted (same
          // reasoning as SourceJarIndexer's ReentrantLock — park, don't pin)
          indexPermits.acquireUninterruptibly()
          try {
            activeIndexCount.incrementAndGet()
            SourceJarIndexer.index(src, fingerprint, cacheRoot, (done, total, name) =>
              progressListener.onProgress(phase, done, total, name))
            indexedFingerprints.add(fingerprint)
            accuratePackages(fingerprint).foreach(packagesByFingerprint.put(fingerprint, _))
          } finally {
            activeIndexCount.decrementAndGet()
            indexPermits.release()
          }
        } catch {
          case NonFatal(e) =>
            logger.warn(s"Failed to index $src: ${e.getMessage}")
            progressListener.onProgress(phase, 1, 1, s"Indexing failed: ${src.last}")
        } finally {
          indexingInProgress.remove(fingerprint)
          backgroundIndexCompletions.incrementAndGet()
        }
      })
    }

  /** Lazy per-file unpacking: indexes are built eagerly (LMDB only), but individual
    * source files are written to disk on first lookup hit — the LSP Location must
    * point at a real file for the editor to open it. Idempotent + single-flight per fingerprint. */
  private def ensureEntryExtracted(fingerprint: String, defPath: os.Path): Unit = {
    val srcRoot = cacheDir(fingerprint) / "src"
    if (!defPath.startsWith(srcRoot)) return
    if (os.exists(defPath)) return
    fingerprintLocks.computeIfAbsent(fingerprint, _ => new Object).synchronized {
      if (!os.exists(defPath)) {
        Option(sourcesByFingerprint.get(fingerprint)) match {
          case Some(src) =>
            val entryPath = defPath.relativeTo(srcRoot).toString
            try SourceJarIndexer.extractEntry(src, fingerprint, entryPath, cacheRoot)
            catch { case NonFatal(e) => logger.warn(s"Failed to extract $entryPath for $fingerprint: ${e.getMessage}") }
          case None =>
            if (noSourceFingerprints.add(fingerprint)) logger.warn(s"No source known for $fingerprint — cannot extract")
        }
      }
    }
  }
}
