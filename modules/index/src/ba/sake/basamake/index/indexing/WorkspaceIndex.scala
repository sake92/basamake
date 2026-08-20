package ba.sake.basamake.index.indexing

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*
import scala.util.boundary, boundary.break
import com.typesafe.scalalogging.StrictLogging
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.index.*
import ba.sake.basamake.index.scalasrc.{ScalaDefinitionsExtractor, ScalaReferencesResolver }
import ba.sake.basamake.index.javasrc.{JavaDefinitionsExtractor, JavaReferencesResolver}

/** Per-source index state. `occurrences`/`locals` are populated only while the
  * file is open; `semanticdbPath` is workspace-level state that survives tab close. */
private final case class SourceData(
    occurrences: Vector[ReferenceOccurrence],
    locals: Vector[SymbolDefinition],
    semanticdbPath: Option[os.Path]
)

private object SourceData {
  val empty: SourceData = SourceData(Vector.empty, Vector.empty, None)
}

class WorkspaceIndex(workspacePath: os.Path, symbolTable: SymbolTable, depsTable: Option[IndexedSymbolTable] = None, ignorePatterns: Vector[String] = Vector.empty, progressListener: IndexingProgressListener = IndexingProgressListener.noop, debugSymbolTableDump: Boolean = false, slowFallbackThresholdMs: Option[Long] = None) extends StrictLogging {

  // One map for ALL workspace sources (keyed by path): the keySet IS the live
  // source list (no separate knownSources), semanticdbPath is the pairing,
  // occurrences/locals are the open-file data. ConcurrentHashMap so queries
  // never block on BSP-compile invalidations (no synchronized).
  private val sourcesMap = new ConcurrentHashMap[os.Path, SourceData]()
  // open files — separate set: an open file with zero references must stay "open"
  private val openFiles = ConcurrentHashMap.newKeySet[os.Path]()

  // serialize debug-dump file writes (os.write.over is not atomic)
  private val dumpLock = new Object

  // ── debug dump throttling ─────────────────────────────────────
  // index_sources.txt stays SYNCHRONOUS (cheap, few ms — tests rely on it being
  // fresh after invalidate). symbol_table.txt is the heavy one (serializes the
  // whole symbol table, e.g. 12.9MB for msc-backend) — it used to run on the
  // BSP event thread after EVERY compile; it's now deferred to a background
  // flusher that writes at most once per interval, only when dirty.
  private val DumpFlushIntervalMs = 60_000L
  private val symbolTableDirty = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val dumpFlusherStarted = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Lazy-start the background symbol-table flusher (one virtual thread). */
  private def ensureDumpFlusher(): Unit = {
    if (!dumpFlusherStarted.compareAndSet(false, true)) return
    Thread.ofVirtual().start(() => {
      var running = true
      while (running) {
        try Thread.sleep(DumpFlushIntervalMs)
        catch { case _: InterruptedException => running = false }
        if (running && symbolTableDirty.getAndSet(false)) writeSymbolTableDump()
      }
    })
  }

  /** True while a BSP-compile invalidation is tearing down + rebuilding the
    * symbol table — gotoDefinitions retries once in this window (see below). */
  @volatile private var invalidating = false

  /** Set when initialize is interrupted mid-fallback — records the startup
    * failure instead of silently continuing with partial data. */
  private val startupFailed = new java.util.concurrent.atomic.AtomicBoolean(false)
  private[index] def didStartupFail: Boolean = startupFailed.get()

  /** disk (mtime, size) at the time of the last buffer refresh — lets onDidChange
    * skip the per-keystroke re-parse: occurrences only depend on DISK content
    * (semanticdb parse or source parse of the file on disk), so an unchanged
    * disk file yields identical occurrences no matter how much the buffer moves. */
  private val diskStamps = new ConcurrentHashMap[os.Path, (Long, Long)]()

  /** .semanticdb path → (mtime, size) of the last successfully indexed file —
    * invalidate skips unchanged files, so a compile no longer re-parses the
    * whole semanticdb output (~1376 files ≈ 1.5s on the BSP thread) when
    * nothing changed. */
  private val semanticdbStamps = new ConcurrentHashMap[os.Path, (Long, Long)]()

  /** Bounded cache of SOURCE-PARSE results for files without semanticdb (dep
    * jars, JDK sources, unpaired workspace files). A parse costs up to seconds
    * (resolver lookups); onDidClose drops the open-file occurrences, so a tab
    * reopen would re-parse — this cache makes reopen instant. Keyed by disk
    * stamp; cleared wholesale on overflow (same pattern as Fingerprint's memo). */
  private val MaxSourceParseCacheEntries = 128
  private final case class SourceParseCacheEntry(
      stamp: (Long, Long),
      occurrences: Vector[ReferenceOccurrence],
      locals: Vector[SymbolDefinition]
  )
  private val sourceParseCache = new ConcurrentHashMap[os.Path, SourceParseCacheEntry]()

  /** Immutable snapshot of the startup semanticdb roots, published BEFORE broad
    * Pass A begins. onDidOpen's direct single-source pairing reads it while
    * initialize is still walking roots concurrently. Replaced only at the start
    * of a new initialization run. */
  @volatile private var activeRoots: List[SemanticdbDirs] = Nil

  // ── test seams ────────────────────────────────────────────────
  private val semanticdbIndexCount = new java.util.concurrent.atomic.AtomicLong(0)
  private val bufferRefreshCount = new java.util.concurrent.atomic.AtomicLong(0)
  private val directPairCount = new java.util.concurrent.atomic.AtomicLong(0)
  private[index] def indexedSemanticdbFiles: Long = semanticdbIndexCount.get()
  private[index] def bufferRefreshCountValue: Long = bufferRefreshCount.get()
  private[index] def directPairCountValue: Long = directPairCount.get()
  private[index] def symbolTableDumpDirty: Boolean = symbolTableDirty.get()
  private[index] def flushSymbolTableDump(): Unit = {
    symbolTableDirty.set(false)
    writeSymbolTableDump()
  }
  private[index] def setInvalidating(v: Boolean): Unit = invalidating = v
  /** Test seam: called right after the startup roots snapshot is published and
    * before broad Pass A begins. Tests block here to hold bulk initialization
    * while exercising onDidOpen's direct single-source pairing. */
  private[index] var afterRootsPublishedHook: () => Unit = () => ()

  /** Test seam: called at the start of each Pass B fallback extraction job
    * (before reading/extracting the file). Tests block here to observe the
    * bound on simultaneously-running fallback jobs without time-based
    * assertions. */
  private[index] var fallbackJobHook: os.Path => Unit = _ => ()

  /** Number of CPU-bound Pass B worker threads (tests shrink it to assert the
    * concurrency bound; production uses [[WorkspaceIndex.DefaultFallbackWorkerCount]]). */
  private[index] var fallbackWorkerCount: Int = WorkspaceIndex.DefaultFallbackWorkerCount

  /** Deterministic phase-event sink (ordered): lets tests verify startup phase
    * ordering — e.g. that direct pairing for an opened file completes before
    * broad Pass A / fallback Pass B — without asserting real elapsed time. */
  private val phaseEvents = new java.util.concurrent.CopyOnWriteArrayList[String]()
  private[index] def phaseEventLog: List[String] = phaseEvents.asScala.toList
  private def recordPhaseEvent(event: String): Unit = phaseEvents.add(event)

  private def elapsedMs(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000L

  // gitignore engine — built once at construction, used by the walk AND by the
  // entry-point guards below. Replaced wholesale on .gitignore changes (volatile
  // so concurrent guard calls never see a half-mutated engine).
  @volatile private var ignoreEngine = new GitIgnoreEngine(workspacePath, ignorePatterns)

  /** Table the resolvers see for ONE file: workspace symbols + dep symbols
    * scoped to the file's candidates. Workspace files get JDK-scoped candidates
    * (`candidatesForPath` returns Nil for workspace files — the JDK is the
    * implicit candidate in `IndexedSymbolTable.get`). Dep files get
    * target-scoped candidates (owning jar first, then the union of containing
    * targets' classpaths — see IndexedSymbolTable.candidatesForPath). */
  private def resolverTableFor(path: os.Path): SymbolTable = depsTable match {
    case Some(deps) =>
      val candidates = deps.candidatesForPath(path)
      new SymbolTable {
        override def get(symbol: String) = symbolTable.get(symbol).orElse(deps.get(symbol, candidates))
        override def byPath(p: os.Path) = symbolTable.byPath(p)
        override def add(sd: SymbolDefinition) = symbolTable.add(sd)
        override def removeByPath(p: os.Path) = symbolTable.removeByPath(p)
        override def all = symbolTable.all
        override def symbolsIn(pkgOwner: String, name: String) = symbolTable.symbolsIn(pkgOwner, name)
      }
    case None => symbolTable
  }

  /** Re-read `.gitignore` rules — called by BspManager on .gitignore change events. */
  def reloadIgnores(): Unit = ignoreEngine = new GitIgnoreEngine(workspacePath, ignorePatterns)

  /** True for paths that must never enter the index: gitignored paths INSIDE the
    * workspace (e.g. `.worktrees/`, `node_modules/`) or under an always-skip dir.
    * Paths OUTSIDE the workspace (deps/JDK sources opened via goto-def) are never
    * blocked — GitIgnoreEngine treats everything outside its root as ignored. */
  private def isIgnoredWorkspacePath(p: os.Path): Boolean = {
    if !p.startsWith(workspacePath) then return false
    var cur = p
    while cur.startsWith(workspacePath) do {
      if GitIgnoreEngine.alwaysSkipDirNames.contains(cur.last) then return true
      cur = cur / os.up
    }
    ignoreEngine.isIgnored(p, isDir = os.isDir(p))
  }

  // per-path lock: serializes source parsing (refreshOpenBuffer) with the
  // occurrence-consuming queries (definition/references/findSymbolsAt). didOpen/
  // didChange parse on a background thread now; a request on a freshly opened
  // file waits for its parse (≤ ~1s) instead of racing it into a transient miss.
  private val pathLocks = new ConcurrentHashMap[os.Path, Object]()
  private def withPathLock[T](path: os.Path)(body: => T): T =
    pathLocks.computeIfAbsent(path, _ => new Object).synchronized(body)

  /** Wait (bounded) until the open-file buffer state for `path` has been computed
    * at least once — `diskStamps` is written at the END of refreshOpenBuffer.
    * didOpen/didChange run on a background thread; a request can arrive before
    * that thread even started, so the per-path lock alone is not enough (it only
    * helps once the parse holds it). MUST be called BEFORE taking the lock —
    * otherwise the parse can never complete (lock held by the waiter). */
  private def awaitBufferReady(path: os.Path): Unit = {
    var waited = 0
    while (!diskStamps.containsKey(path) && waited < 200) {
      Thread.sleep(10)
      waited += 1
    }
  }

  /** Like [[awaitBufferReady]] but for ALL currently open files — references
    * scans every open file, so each must have its buffer state computed at
    * least once (their didOpen parses run on background threads too). Bounded:
    * a pathological file just times out and the scan proceeds with what it has. */
  private def awaitAllBuffersReady(): Unit = {
    var waited = 0
    while (waited < 200) {
      val pending = openFiles.asScala.exists(p => !diskStamps.containsKey(p))
      if (!pending) return
      Thread.sleep(10)
      waited += 1
    }
  }

  // ── initialize ──────────────────────────────────────────────
  def initialize(roots: List[SemanticdbDirs]): Unit = {
    logger.info(s"Initializing workspace index at $workspacePath")
    val tInitStart = System.nanoTime()
    val relevantExtensions = Set("scala", "java", "sbt")
    def skip(p: os.Path): Boolean =
      if os.isDir(p) then GitIgnoreEngine.alwaysSkipDirNames.contains(p.last) || ignoreEngine.isIgnored(p, isDir = true)
      else if os.isFile(p) then !relevantExtensions.contains(p.ext) || ignoreEngine.isIgnored(p, isDir = false)
      else true

    val tDiscoveryStart = System.nanoTime()
    val sources = os.walk(workspacePath, skip = skip)
    val fileGroups = sources.groupBy(_.ext)
    val scalaFiles = fileGroups.getOrElse("scala", Vector.empty)
    val sbtFiles = fileGroups.getOrElse("sbt", Vector.empty)
    val javaFiles = fileGroups.getOrElse("java", Vector.empty)
    logger.info(s"Found files: scala=${scalaFiles.size}, sbt=${sbtFiles.size}, java=${javaFiles.size} (${elapsedMs(tDiscoveryStart)}ms)")

    sourcesMap.clear()
    semanticdbStamps.clear()
    // a file opened between the walk and the clear must not be dropped
    openFiles.forEach(p => sourcesMap.putIfAbsent(p, SourceData.empty))
    val allFiles = scalaFiles.toSet ++ sbtFiles.toSet ++ javaFiles.toSet
    allFiles.foreach(p => sourcesMap.put(p, SourceData.empty))

    // Publish the immutable startup-roots snapshot BEFORE broad Pass A — onDidOpen
    // direct pairing reads it while initialize is still walking roots concurrently.
    activeRoots = roots
    recordPhaseEvent("roots-published")
    afterRootsPublishedHook()

    val total = allFiles.size.toLong
    var done = 0L
    def report(msg: String): Unit =
      progressListener.onProgress(IndexingPhase.Workspace, done.min(total), total, msg)
    report("scanning workspace")

    // Pass A: index semanticdb DEFINITION occurrences from BSP-provided
    // (sourceRootDir, semanticdbDir) pairs into symbolTable, pair with sources.
    // No workspace-wide .semanticdb walk — only explicit dirs from data.json / BSP compile.
    if (roots.nonEmpty) {
      logger.info(s"Indexing semanticdb from ${roots.size} target root(s)")
      val tPassAStart = System.nanoTime()
      var pairedTotal = 0
      var defsTotal = 0
      for (root <- roots if os.exists(root.semanticdbDir) && os.exists(root.sourceRootDir)) {
        val semDir = root.semanticdbDir
        val srcRoot = root.sourceRootDir
        if (ignoreEngine.isInsideNestedRepo(srcRoot)) {
          logger.warn(s"Skipping semanticdb root inside nested git repo: $srcRoot")
        } else {
          val res = SemanticdbIndexing.indexSemanticdbDir(semDir, srcRoot, workspacePath, symbolTable)
          val (accepted, rejected) = res.pairs.partition((src, _) => !ignoreEngine.isInsideNestedRepo(src))
          rejected.keySet.foreach { src =>
            logger.warn(s"Source inside nested git repo, skipping semanticdb pair: $src")
            symbolTable.removeByPath(src)
          }
          accepted.foreach { case (src, semPath) => setSemanticdbPath(src, semPath) }
          pairedTotal += accepted.size
          defsTotal += res.definitionsIndexed
          done += accepted.size.toLong
          report(s"semanticdb ${accepted.size} files")
          logger.info(s"Indexed ${accepted.size} semanticdb-paired source files from ${semDir}")
        }
      }
      val paired = sourcesMap.values().asScala.count(_.semanticdbPath.isDefined)
      logger.info(s"Total semanticdb-paired source files: $paired")
      logger.info(s"Semanticdb Pass A done: ${elapsedMs(tPassAStart)}ms, roots=${roots.size}, paired=$pairedTotal, definitionsIndexed=$defsTotal")
      recordPhaseEvent("pass-a-done")
    }

    // Pass B: extract from source AST for files WITHOUT semanticdb, on a
    // short-lived, NAMED, BOUNDED platform-thread executor. NEVER one virtual
    // thread per file: virtual threads do not create CPU capacity and flood the
    // shared scheduler, starving the BSP task (the previous implementation
    // delayed the first BSP handshake by ~90s on a ~1950-file workspace).
    // One extractor instance per job; SymbolTable writes stay concurrent-safe
    // (ConcurrentHashMap-backed). The initialization coordinator awaits the
    // latch; the temporary executor is always shut down in `finally`.
    val passBFiles = (scalaFiles ++ sbtFiles).filter(p => sourcesMap.get(p).semanticdbPath.isEmpty) ++
      javaFiles.filter(p => sourcesMap.get(p).semanticdbPath.isEmpty)
    val passBDone = new AtomicLong(0L)
    val passBOk = new AtomicLong(0L)
    val passBFail = new AtomicLong(0L)
    val tPassBStart = System.nanoTime()
    val workerCount = fallbackWorkerCount
    val latch = new CountDownLatch(passBFiles.length)
    val executor = Executors.newFixedThreadPool(workerCount, new ThreadFactory {
      private val threadSeq = new java.util.concurrent.atomic.AtomicLong(0)
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, s"basamake-fallback-${threadSeq.incrementAndGet()}")
        t.setDaemon(true)
        t
      }
    })
    try {
      passBFiles.foreach { path =>
        executor.execute(() => {
          try {
            fallbackJobHook(path)
            val tJobStart = System.nanoTime()
            logger.debug(s"Extracting definitions from $path")
            val is = os.read.inputStream(path)
            try {
              if (path.ext == "java") {
                val extractor = JavaDefinitionsExtractor(symbolTable)
                extractor.extract(path.last, is, path)
              } else {
                val extractor = ScalaDefinitionsExtractor(symbolTable)
                extractor.extract(path.last, is, path)
              }
            } finally is.close()
            passBOk.incrementAndGet()
            // opt-in DEBUG diagnostics: only when the user sets a threshold in
            // config (never on the default INFO path, never per-file at INFO)
            slowFallbackThresholdMs.foreach { thr =>
              val jobMs = elapsedMs(tJobStart)
              if jobMs > thr then
                logger.debug(s"Slow fallback extraction: $path (parser=${path.ext}, ${jobMs}ms)")
            }
          } catch {
            case e: Exception =>
              passBFail.incrementAndGet()
              logger.warn(s"Failed to extract $path: ${e.getMessage}")
          } finally {
            val n = done + passBDone.incrementAndGet()
            progressListener.onProgress(IndexingPhase.Workspace, n.min(total), total, path.last)
            latch.countDown()
          }
        })
      }
      try latch.await()
      catch {
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          startupFailed.set(true)
          logger.error("Interrupted while waiting for fallback extraction to finish — startup aborted")
          return
      }
    } finally {
      executor.shutdown()
    }
    done += passBFiles.size.toLong
    logger.info(s"Fallback Pass B done: ${elapsedMs(tPassBStart)}ms, files=${passBFiles.size}, workers=$workerCount, ok=${passBOk.get()}, failed=${passBFail.get()}")
    recordPhaseEvent(s"pass-b-done:files=${passBFiles.size}:workers=$workerCount:ok=${passBOk.get()}:failed=${passBFail.get()}")

    report(s"Indexed $total files")
    // Async initialize: files may be opened while indexing runs. The map
    // re-seed above only preserved their PRESENCE — restore their buffer state
    // (occurrences/locals) and prefer semanticdb occurrences now that pairing
    // is done. Without this, goto-def in such tabs returns empty until the
    // user edits or saves the file.
    val tCatchUpStart = System.nanoTime()
    val openBefore = openFiles.size
    openFiles.forEach(p => refreshOpenBuffer(p))
    logger.info(s"Open-buffer catch-up: ${elapsedMs(tCatchUpStart)}ms, refreshed=$openBefore")
    recordPhaseEvent("catch-up-done")

    val pairedFinal = sourcesMap.values().asScala.count(_.semanticdbPath.isDefined)
    logger.info(s"Workspace indexing finished: ${elapsedMs(tInitStart)}ms total, semanticdb-paired=$pairedFinal, fallback-extracted=${passBFiles.size}")
    recordPhaseEvent("init-done")
    writeDebugDump()
  }

  /** Debug dump: .basamake/index_sources.txt + (opt-in) symbol_table.txt —
    * which source files are paired with which .semanticdb files, and the full
    * symbol table. index_sources.txt is written at initialize AND refreshed
    * after every index state change (cheap). symbol_table.txt is the heavy one
    * (serializes the whole symbol table, e.g. 12.9MB for msc-backend) — it is
    * OPT-IN (`debugSymbolTableDump`), so the default startup path never pays
    * for it. */
  private def writeDebugDump(): Unit = {
    writeIndexSourcesDump()
    if (debugSymbolTableDump) writeSymbolTableDump()
  }

  /** Cheap refresh after index state changes (invalidate / file create+delete):
    * index_sources.txt synchronously (tests + freshness), symbol_table.txt
    * deferred to the throttled background flusher — the full-table serialize
    * (~13MB) must not run on the BSP event thread after every compile. */
  private def refreshDebugDump(): Unit = {
    writeIndexSourcesDump()
    if (debugSymbolTableDump) {
      symbolTableDirty.set(true)
      ensureDumpFlusher()
    }
  }

  private def writeIndexSourcesDump(): Unit = {
    try {
      val pairs = sourcesMap.entrySet().asScala.flatMap { e =>
        e.getValue.semanticdbPath.map(sem => e.getKey -> sem)
      }.toMap
      val allSources = sourcesMap.keySet().asScala.toSet
      val dump = SemanticdbIndexing.dumpPairs(pairs, allSources, workspacePath)
      val dumpDir = workspacePath / ".basamake"
      os.makeDir.all(dumpDir)
      dumpLock.synchronized {
        os.write.over(dumpDir / "index_sources.txt", dump)
      }
    } catch {
      case e: Exception => logger.warn(s"Failed to write index_sources.txt: ${e.getMessage}")
    }
  }

  private def writeSymbolTableDump(): Unit = {
    try {
      val dumpDir = workspacePath / ".basamake"
      os.makeDir.all(dumpDir)
      dumpLock.synchronized {
        os.write.over(dumpDir / "symbol_table.txt", symbolTable.all.toVector.sortBy(_.symbol).mkString("\n"), createFolders = true)
      }
    } catch {
      case e: Exception => logger.warn(s"Failed to write symbol_table.txt: ${e.getMessage}")
    }
  }

  // ── onDidOpen/Change/Save/Close ──────────────────────────────
  def onDidOpen(path: os.Path): Unit = {
    if isIgnoredWorkspacePath(path) then return
    openFiles.add(path)
    sourcesMap.putIfAbsent(path, SourceData.empty)
    // Direct single-source semanticdb pairing against the startup root snapshot:
    // one candidate file read+parse (NOT the broad root walk). Without it, an
    // opened file waits behind ALL fallback jobs for its semanticdb occurrences.
    if (sourcesMap.get(path).semanticdbPath.isEmpty) pairSourceDirectly(path)
    // a success parses SemanticDB occurrences, a miss uses existing source parsing
    refreshOpenBuffer(path)
  }

  /** Try to pair ONE opened source with its cached `.semanticdb` file, using the
    * conventional candidate path under each known startup root. Synchronous and
    * small by design (single file read+parse) — deliberately no separate task.
    * Stores the pairing atomically via `setSemanticdbPath`; definition insertion
    * is idempotent under the SymbolTable's key-replacement semantics, so racing
    * with broad Pass A is harmless (whichever path wins stores a valid pairing,
    * and neither path calls removeByPath during initial pairing). */
  private def pairSourceDirectly(path: os.Path): Unit = {
    val t0 = System.nanoTime()
    boundary {
      for (root <- activeRoots) {
        if os.exists(root.sourceRootDir) && os.exists(root.semanticdbDir) && !ignoreEngine.isInsideNestedRepo(root.sourceRootDir) then {
          SemanticdbIndexing.pairSourceFromRoot(path, root.sourceRootDir, root.semanticdbDir, workspacePath, symbolTable) match {
            case Some(semPath) =>
              setSemanticdbPath(path, semPath)
              directPairCount.incrementAndGet()
              recordPhaseEvent(s"direct-pair:$path")
              logger.info(s"Direct semanticdb pairing: $path <- $semPath (${elapsedMs(t0)}ms)")
              break()
            case None => ()
          }
        }
      }
    }
  }

  def onDidChange(path: os.Path): Unit = {
    if isIgnoredWorkspacePath(path) then return
    openFiles.add(path)
    // Occurrences only depend on DISK content — skip the re-parse while the
    // disk file is unchanged (typing = no refresh; the old code re-parsed the
    // whole file's semanticdb occurrences on EVERY keystroke, serializing the
    // single lsp4j message thread).
    if (diskStamps.get(path) != diskStampOf(path)) refreshOpenBuffer(path)
  }

  def onDidSave(path: os.Path): Unit = withPathLock(path) {
    if !isIgnoredWorkspacePath(path) then {
      openFiles.add(path)
      sourcesMap.putIfAbsent(path, SourceData.empty)
      // re-extract SymbolTable for this path
      symbolTable.removeByPath(path)
      val data = sourcesMap.get(path)
      if (data != null && data.semanticdbPath.isDefined) {
        try {
          val defs = SemanticdbIndexing.parseDefinitions(data.semanticdbPath.get, path)
          defs.foreach(symbolTable.add)
        } catch { case _: Exception => () }
      } else if (path.ext == "scala" || path.ext == "sbt") {
        withSourceStream(path) { is =>
          val extractor = ScalaDefinitionsExtractor(symbolTable)
          extractor.extract(path.last, is, path)
        }
      } else if (path.ext == "java") {
        withSourceStream(path) { is =>
          val extractor = JavaDefinitionsExtractor(symbolTable)
          extractor.extract(path.last, is, path)
        }
      }
      refreshOpenBuffer(path)
    }
  }

  def onDidClose(path: os.Path): Unit = {
    // A closed tab keeps its workspace-level state (semanticdbPath); only the
    // open-file occurrences/locals are emptied.
    openFiles.remove(path)
    sourcesMap.computeIfPresent(path, (_, sd) => sd.copy(occurrences = Vector.empty, locals = Vector.empty))
  }

  /** Files removed from disk (watcher delete events, rename old paths).
    * Purges ALL state for them — buffer state and workspace-level state
    * (semanticdb pairing + SymbolTable definitions). */
  def onFilesDeleted(paths: Set[os.Path]): Unit = {
    paths.foreach { path =>
      openFiles.remove(path)
      sourcesMap.remove(path)
      diskStamps.remove(path)
      sourceParseCache.remove(path)
      symbolTable.removeByPath(path)
    }
    refreshDebugDump()
  }

  /** New source files on disk (watcher create events, rename new paths).
    * Semanticdb pairing arrives with the next compile (invalidate).
    * Gitignored paths are dropped — they must never enter the source list. */
  def onFilesCreated(paths: Set[os.Path]): Unit = {
    val accepted = paths.filterNot(isIgnoredWorkspacePath)
    if (accepted.isEmpty) return
    accepted.foreach(p => sourcesMap.putIfAbsent(p, SourceData.empty))
    refreshDebugDump()
  }

  // ── invalidate (BSP compile callback) ────────────────────────

  /** Re-index `.semanticdb` files after a BSP compile.
    * Called from BspConnection.compile's onAfterCompile callback via BspManager.
    * Uses per-target (sourceRootDir, semanticdbDir) pairs for direct URI resolution
    * — no climbing. Additive — does not touch existing per-file paths. */
  def invalidate(roots: List[SemanticdbDirs]): Unit = {
    if (roots.isEmpty) return
    logger.info(s"Invalidating workspace index (${roots.size} semanticdb root(s))")
    invalidating = true
    try {
      for (root <- roots if os.exists(root.sourceRootDir) && os.exists(root.semanticdbDir) && !ignoreEngine.isInsideNestedRepo(root.sourceRootDir)) {
        val srcRoot = root.sourceRootDir
        val semDir = root.semanticdbDir
        val semFiles = os.walk(semDir).filter(_.ext == "semanticdb").toList
        var paired = 0
        for (semPath <- semFiles) {
          // skip files unchanged since their last successful index — a compile
          // rewrites ALL semanticdb files, but only the changed ones matter
          if (semanticdbStamps.get(semPath) != stampOf(semPath)) {
            if (indexSemanticdbFile(semPath, srcRoot)) paired += 1
          }
        }
        logger.info(s"Invalidated $paired/${semFiles.size} semanticdb files from $semDir")
      }
    } finally {
      invalidating = false
    }
    refreshDebugDump()
  }

  /** Index a single .semanticdb file: parse definitions, pair with source via direct
    * sourceRoot / uri resolution (+ ancestor-climbing fallback), update SymbolTable.
    * @return true if the file was paired with a source */
  private def indexSemanticdbFile(semPath: os.Path, sourceRoot: os.Path): Boolean = {
    try {
      val docs = scala.meta.internal.semanticdb.TextDocuments.parseFrom(os.read.bytes(semPath))
      var paired = false
      for (doc <- docs.documents.toVector if doc.uri.nonEmpty) {
        SemanticdbIndexing.resolveSourcePath(semPath, doc.uri, sourceRoot, workspacePath) match {
          case Some(src) if !ignoreEngine.isInsideNestedRepo(src) =>
            setSemanticdbPath(src, semPath)
            symbolTable.removeByPath(src)
            SemanticdbIndexing.parseDefinitions(semPath, src).foreach(symbolTable.add)
            if (openFiles.contains(src)) refreshOpenBuffer(src)
            paired = true
          case Some(src) =>
            logger.warn(s"Source inside nested git repo, skipping semanticdb pair: $src")
          case None =>
            logger.warn(s"No source match for $semPath (uri=${doc.uri}, sourceRoot=$sourceRoot)")
        }
      }
      // only record the stamp after a successful parse — a transient failure
      // stays un-stamped and is retried on the next invalidate
      semanticdbStamps.put(semPath, stampOf(semPath))
      semanticdbIndexCount.incrementAndGet()
      paired
    } catch {
      case e: Exception => logger.warn(s"Failed to index $semPath: ${e.getMessage}"); false
    }
  }

  private def stampOf(p: os.Path): (Long, Long) =
    try (os.mtime(p), os.size(p)) catch { case _: Exception => (-1L, -1L) }

  private def setSemanticdbPath(src: os.Path, semPath: os.Path): Unit =
    sourcesMap.compute(src, (_, old) => {
      val current = if (old == null) SourceData.empty else old
      current.copy(semanticdbPath = Some(semPath))
    })

  // ── queries ─────────────────────────────────────────────────
  def findSymbolsAt(path: os.Path, line: Int, char: Int): Vector[String] = {
    awaitBufferReady(path)
    withPathLock(path) {
      val result = Vector.newBuilder[String]

      val data = sourcesMap.get(path)
      // Probe ref occurrences (refs only — defs live in SymbolTable / open-file locals)
      val occs = if (data == null) Vector.empty else data.occurrences
      val enclosingRefs = occs.filter(o => isInsideRange(line, char, o.range))
      if (enclosingRefs.nonEmpty) {
        val minLen = enclosingRefs.map(o => rangeLength(o.range)).min
        result ++= enclosingRefs.filter(o => rangeLength(o.range) == minLen).map(_.symbol)
      }

      // Probe local defs (locals have exact range info for cursor-on-def-site)
      val localDefs = if (data == null) Vector.empty else data.locals
      val enclosingLocals = localDefs.filter(ld => isInsideRange(line, char, ld.range))
      result ++= enclosingLocals.map(_.symbol)

      // Probe global defs via SymbolTable for this file
      val globalDefs = symbolTable.byPath(path)
      val enclosingGlobals = globalDefs.filter(sd => isInsideRange(line, char, sd.range))
      result ++= enclosingGlobals.map(_.symbol)

      result.result().distinct
    }
  }

  def gotoDefinitions(path: os.Path, line: Int, char: Int, depCandidates: List[os.Path] = Nil): Vector[SymbolDefinition] = {
    // An in-flight invalidation tears down + rebuilds the symbol table per file
    // on the BSP thread; a lookup landing in that window can transiently return
    // empty. Retry briefly (only when refs were found but resolution came up
    // empty — a cursor on a def site must NOT trigger the wait).
    var result = resolveDefinitions(path, line, char, depCandidates)
    var attempts = 0
    while (result.contains(Vector.empty) && invalidating && attempts < 20) {
      Thread.sleep(50)
      result = resolveDefinitions(path, line, char, depCandidates)
      attempts += 1
    }
    result.getOrElse(Vector.empty)
  }

  /** One-shot resolution: None = no refs under the cursor (def site), Some =
    * resolution outcome (possibly empty — symbol not found yet). */
  private def resolveDefinitions(path: os.Path, line: Int, char: Int, depCandidates: List[os.Path]): Option[Vector[SymbolDefinition]] = {
    awaitBufferReady(path)
    withPathLock(path) {
      val data = sourcesMap.get(path)
      // All occurrences are references (defs live in SymbolTable / open-file locals).
      val references = if (data == null) Vector.empty else data.occurrences
      val localDefs = if (data == null) Vector.empty else data.locals
      val localDefinitionsMap = localDefs.map(ld => ld.symbol -> ld).toMap

      val referencesUnderCursor = references.filter(o => isInsideRange(line, char, o.range))
      // Cursor on a def site (not a ref) → return empty. "Go to definition" from the
      // definition itself is noise; the user wants references there, not "go to self".
      if (referencesUnderCursor.isEmpty) None
      else {
        val local = referencesUnderCursor.flatMap(o => localDefinitionsMap.get(o.symbol))
        val candidates =
          if (local.nonEmpty) local
          else referencesUnderCursor.flatMap(o => getSymbol(o.symbol, depCandidates))
        // Filter out the location the cursor is already on (self-filter for refs).
        // Also filter synthetic zero-range symbols (e.g. evidence params desugared
        // from context bounds have no source position and would navigate to (0,0)).
        Some(candidates.filterNot { sd =>
          val zeroRange = sd.range.startLine == 0 && sd.range.startCharacter == 0 &&
            sd.range.endLine == 0 && sd.range.endCharacter == 0
          zeroRange || (sd.path == path && isInsideRange(line, char, sd.range))
        })
      }
    }
  }

  /** v1: scan only occurrences in CURRENTLY OPEN FILES.
    * Cross-workspace references are explicitly out of scope for v1. */
  def references(path: os.Path, line: Int, char: Int, includeDeclaration: Boolean, depCandidates: List[os.Path] = Nil): Vector[SymbolDefinition] = {
    awaitBufferReady(path)
    awaitAllBuffersReady()
    withPathLock(path) {
      val targetSymbols = findSymbolsAt(path, line, char).toSet
      if (targetSymbols.isEmpty) Vector.empty
      else {
        val results = Vector.newBuilder[SymbolDefinition]

        // Scan ref occurrences across all open files
        for (openPath <- openFiles.asScala) {
          val data = sourcesMap.get(openPath)
          val occs = if (data == null) Vector.empty else data.occurrences
          for (occ <- occs if targetSymbols.contains(occ.symbol)) {
            results += SymbolDefinition(
              symbol = occ.symbol,
              shortName = occ.symbol,
              isType = SymbolUtils.isTypeSymbol(occ.symbol),
              range = occ.range,
              path = openPath
            )
          }
        }

        // If includeDeclaration, append the def site from SymbolTable or locals
        if (includeDeclaration) {
          val openLocals = openFiles.asScala.iterator.flatMap { p =>
            val d = sourcesMap.get(p)
            if (d == null) Iterator.empty else d.locals.iterator
          }.toVector
          for (sym <- targetSymbols) {
            // Try locals first, then SymbolTable (dep lookups scoped to candidates)
            val defOpt = openLocals.find(ld => ld.symbol == sym).orElse(getSymbol(sym, depCandidates))
            defOpt.foreach(d => results += d)
          }
        }

        results.result().distinct
      }
    }
  }

  // ── internal helpers ─────────────────────────────────────────

  /** Symbol lookup: workspace first, then the dep/JDK index scoped to the file's
    * BSP target candidates. With empty candidates the dep lookup is JDK-scoped
    * (see IndexedSymbolTable.get).
    * Package symbols (`pkg/` — import-prefix segments, incl. compiler-emitted
    * semanticdb occurrences) resolve to the package OBJECT (`pkg/package.`);
    * Scala-specific — Java emits no package-segment refs. */
  def getSymbol(symbol: String, depCandidates: List[os.Path]): Option[SymbolDefinition] = {
    val target = if (symbol.endsWith("/")) symbol + "package." else symbol
    symbolTable.get(target).orElse(depsTable.flatMap(_.get(target, depCandidates)))
  }

  private def refreshOpenBuffer(path: os.Path): Unit = withPathLock(path) {
    if (openFiles.contains(path)) {
      val streamOpt = try Some(os.read.inputStream(path)) catch { case _: Exception => None }
      streamOpt match {
        case None => ()
        case Some(is) =>
        try {
        val current = sourcesMap.get(path)
        val semPathOpt = if (current == null) None else current.semanticdbPath
        val (occs, locals) = semPathOpt match {
          case Some(semPath) =>
            val res = SemanticdbIndexing.parseOccurrences(semPath, path)
            if (res.complete) {
              // Gap-merge: semanticdb refs are authoritative where present, but
              // the compiler DROPS occurrences it can't resolve (empty-symbol
              // cross-document SUID refs). Fill positions with NO semanticdb
              // ref from source-parse so goto-def has a fallback everywhere.
              val sp = sourceParseCached(path, is)
              val extra = sp.occurrences.filterNot { o =>
                res.occurrences.exists(r => sameStart(r.range, o.range))
              }
              (res.occurrences ++ extra, res.locals)
            } else {
              // Partial -Ybest-effort ref symbols (e.g. `utils.` not `_empty_/utils.`)
              // — fall back to source parsing for occurrences. Defs in SymbolTable
              // are full symbols and stay authoritative.
              logger.debug(s"Semanticdb for $path has short ref symbols — falling back to source parse")
              val rf = sourceResolve(path, is)
              (rf.occurrences, rf.locals)
            }
          case None =>
            logger.debug(s"Resolving references from source for $path")
            val rf = sourceParseCached(path, is)
            (rf.occurrences, rf.locals)
        }
        sourcesMap.compute(path, (_, old) => {
          val base = if (old == null) SourceData.empty else old
          base.copy(occurrences = occs, locals = locals)
        })
        diskStamps.put(path, diskStampOf(path))
        bufferRefreshCount.incrementAndGet()
        } finally is.close()
      }
    }
  }

  private def diskStampOf(p: os.Path): (Long, Long) =
    try (os.mtime(p), os.size(p)) catch { case _: Exception => (-1L, -1L) }

  /** True when two ranges start at the same position (position-level merge key:
    * a semanticdb ref covers the position — the source-parse ref is redundant). */
  private def sameStart(a: Range, b: Range): Boolean =
    a.startLine == b.startLine && a.startCharacter == b.startCharacter

  /** Stamp-cached source parse (shared by the no-semanticdb path and gap-merge). */
  private def sourceParseCached(path: os.Path, is: java.io.InputStream): ResolvedFile = {
    val stamp = diskStampOf(path)
    val cached = sourceParseCache.get(path)
    if (cached != null && cached.stamp == stamp) ResolvedFile(cached.occurrences, cached.locals)
    else {
      val rf = sourceResolve(path, is)
      // bounded recent-files cache: tab close/reopen of a source-parsed
      // file (deps, JDK, unpaired workspace files) skips the re-parse
      if (sourceParseCache.size() >= MaxSourceParseCacheEntries) sourceParseCache.clear()
      sourceParseCache.put(path, SourceParseCacheEntry(stamp, rf.occurrences, rf.locals))
      rf
    }
  }

  /** Open `path` as a stream and pass it to `f` (parse from the stream — no
    * intermediate String). None when the file can't be opened (deleted race). */
  private def withSourceStream[T](path: os.Path)(f: java.io.InputStream => T): Option[T] = {
    val streamOpt = try Some(os.read.inputStream(path)) catch { case _: Exception => None }
    streamOpt.map { is =>
      try f(is)
      finally is.close()
    }
  }

  private def sourceResolve(path: os.Path, is: java.io.InputStream): ResolvedFile =
    if (path.ext == "java") {
      val resolver = new JavaReferencesResolver(resolverTableFor(path))
      resolver.resolve(path.last, is, path)
    } else {
      val resolver = new ScalaReferencesResolver(resolverTableFor(path))
      resolver.resolve(path.last, is, path)
    }

  // ── range helpers ────────────────────────────────────────────

  /** True when (line, char) is inside `r`, with END-EXCLUSIVE semantics:
    * `r.start <= cursor < r.end`. Multiline-safe: a cursor on any middle line
    * of a multiline range is inside, and the end position itself is outside. */
  private def isInsideRange(line: Int, char: Int, r: Range): Boolean = {
    val afterStart = line > r.startLine || (line == r.startLine && char >= r.startCharacter)
    val beforeEnd = line < r.endLine || (line == r.endLine && char < r.endCharacter)
    afterStart && beforeEnd
  }

  private def rangeLength(r: Range): Long =
    (r.endLine.toLong - r.startLine.toLong) * 100000 + (r.endCharacter.toLong - r.startCharacter.toLong)

}

object WorkspaceIndex {
  /** Conservative cap for CPU-bound fallback extraction — leaves CPU capacity
    * for LSP, BSP, and JDK work: half the cores, at least 2, at most 8.
    * Fallback parsing must NEVER run one-virtual-thread-per-file: virtual
    * threads do not create CPU capacity and flood the shared scheduler,
    * starving the BSP task (the pre-bounded implementation delayed the first
    * BSP handshake by ~90s on a ~1950-file workspace). */
  val DefaultFallbackWorkerCount: Int = math.max(2, math.min(Runtime.getRuntime.availableProcessors() / 2, 8))
}
