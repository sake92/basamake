# Indexing Progress + Dep Priority Scheduling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show live "indexing N/M" progress in VS Code's status bar (per-domain LSP `workDoneProgress` items: workspace / dependencies / JDK) and index the JDK + scala-lang dependency sources before everything else.

**Architecture:** The navigation module emits progress events through a new `IndexingProgressListener` interface; the main module implements it with LSP `window/workDoneProgress` (`createProgress` → `$/progress` begin/report/end, throttled, capability-gated, fail-safe). `IndexedSymbolTable` replaces its semaphore + fire-and-forget virtual threads with a 2-worker `PriorityBlockingQueue` (JDK = priority 0, scala-lang jars = 1, rest = 2). `initialize()` returns early; workspace indexing runs on a background thread (vscode-languageclient only registers the `window/workDoneProgress/create` handler AFTER the initialize handshake completes, so synchronous `initialize` cannot report progress).

**Tech Stack:** Scala 3.7.4, lsp4j 1.0.0 (`LanguageClient.createProgress` / `notifyProgress`), os-lib, munit. Build tool: deder.

**Spec:** `docs/superpowers/specs/2026-08-10-indexing-progress-design.md`

---

### Task 1: `IndexingProgressListener` + workspace progress events in `WorkspaceIndex`

**Files:**
- Create: `modules/navigation/src/ba/sake/basamake/navigation/indexing/IndexingProgressListener.scala`
- Modify: `modules/navigation/src/ba/sake/basamake/navigation/indexing/WorkspaceIndex.scala`
- Test: `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/WorkspaceIndexProgressTest.scala` (new)

- [ ] **Step 1: Write the failing test**

Create `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/WorkspaceIndexProgressTest.scala`:

```scala
package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, Range => SdbRange, SymbolOccurrence}
import ba.sake.basamake.navigation.{SymbolTable, InMemorySymbolTable}

/** Records IndexingProgressListener events as (phase, done, total, message). */
final class RecordingProgressListener extends IndexingProgressListener {
  val events = new java.util.concurrent.CopyOnWriteArrayList[(IndexingPhase, Long, Long, String)]()
  override def onProgress(phase: IndexingPhase, done: Long, total: Long, message: String): Unit =
    events.add((phase, done, total, message))
  def ofPhase(p: IndexingPhase): List[(Long, Long, String)] =
    events.asScala.toList.collect { case (`p`, done, total, msg) => (done, total, msg) }
}

class WorkspaceIndexProgressTest extends FunSuite {

  private def freshRoot(prefix: String): os.Path = {
    val root = os.pwd / "tmp" / s"$prefix-${System.currentTimeMillis()}"
    os.makeDir.all(root)
    root
  }

  test("initialize reports per-file workspace progress with correct total") {
    val root = freshRoot("ws-progress")
    try {
      os.write(root / "A.scala", "object A")
      os.write(root / "B.scala", "object B")
      os.write(root / "C.java", "class C {}")
      os.write(root / "README.md", "not a source") // must NOT count

      val listener = new RecordingProgressListener
      val idx = new WorkspaceIndex(root, new InMemorySymbolTable, progressListener = listener)
      idx.initialize(List.empty)

      val evs = listener.ofPhase(IndexingPhase.Workspace)
      assertEquals(evs.head, (0L, 3L, "scanning workspace"))
      assertEquals(evs.map(_._3).toSet, Set(3L), "every event must carry total=3")
      assertEquals(evs.map(_._2).distinct, List(0L, 1L, 2L, 3L), "done must be monotonic 0..3")
      assertEquals(evs.last, (3L, 3L, "Indexed 3 files"))
    } finally os.remove.all(root)
  }

  test("initialize counts semanticdb-paired files in progress") {
    val root = freshRoot("ws-progress-sem")
    try {
      val srcDir = root / "src" / "main" / "scala"
      os.makeDir.all(srcDir)
      val semDir = root / "target" / "scala-3.8.4" / "meta" / "META-INF" / "semanticdb" / "src" / "main" / "scala"
      os.makeDir.all(semDir)

      val utilsContent = "object utils:\n  def getMsg() = \"bla\"\n"
      val mainContent = "object Main:\n  def main(args: Array[String]): Unit =\n    println(ext.getMsg())\n"
      os.write(srcDir / "utils.scala", utilsContent)
      os.write(srcDir / "Main.scala", mainContent)

      val utilsDoc = TextDocument(
        schema = Schema.SEMANTICDB4,
        uri = "src/main/scala/utils.scala",
        text = utilsContent,
        language = Language.SCALA,
        symbols = Nil,
        occurrences = List(
          SymbolOccurrence(symbol = "_empty_/utils.", range = Some(SdbRange(0, 7, 0, 12)), role = SymbolOccurrence.Role.DEFINITION),
          SymbolOccurrence(symbol = "_empty_/utils.getMsg().", range = Some(SdbRange(1, 6, 1, 12)), role = SymbolOccurrence.Role.DEFINITION)
        )
      )
      val mainDoc = TextDocument(
        schema = Schema.SEMANTICDB4,
        uri = "src/main/scala/Main.scala",
        text = mainContent,
        language = Language.SCALA,
        symbols = Nil,
        occurrences = List(
          SymbolOccurrence(symbol = "_empty_/utils.", range = Some(SdbRange(2, 12, 2, 15)), role = SymbolOccurrence.Role.REFERENCE),
          SymbolOccurrence(symbol = "_empty_/utils.getMsg().", range = Some(SdbRange(2, 16, 2, 22)), role = SymbolOccurrence.Role.REFERENCE)
        )
      )
      os.write(semDir / "utils.scala.semanticdb", TextDocuments(List(utilsDoc)).toByteArray)
      os.write(semDir / "Main.scala.semanticdb", TextDocuments(List(mainDoc)).toByteArray)

      val listener = new RecordingProgressListener
      val idx = new WorkspaceIndex(root, new InMemorySymbolTable, progressListener = listener)
      idx.initialize(List(SemanticdbDirs(srcDir, semDir)))

      val evs = listener.ofPhase(IndexingPhase.Workspace)
      // both files paired via semanticdb (done jumps 0 → 2), no extraction pass
      assertEquals(evs.map(_._2).distinct, List(0L, 2L))
      assertEquals(evs.last, (2L, 2L, "Indexed 2 files"))
    } finally os.remove.all(root)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `deder exec -t test -m modules-navigation-test`
Expected: FAIL — `value progressListener is not a member of WorkspaceIndex` (and `IndexingProgressListener` not found).

- [ ] **Step 3: Create the listener**

Create `modules/navigation/src/ba/sake/basamake/navigation/indexing/IndexingProgressListener.scala`:

```scala
package ba.sake.basamake.navigation.indexing

/** Indexing domains reported as progress. */
enum IndexingPhase:
  case Workspace, Dependencies, Jdk

/** Callback for indexing progress — emitted by the navigation module, consumed
  * by the LSP layer (workDoneProgress). The navigation module never touches LSP. */
trait IndexingProgressListener:
  /** @param done units completed
    * @param total units to complete
    * @param message short human-readable detail (file/jar name) */
  def onProgress(phase: IndexingPhase, done: Long, total: Long, message: String): Unit

object IndexingProgressListener:
  val noop: IndexingProgressListener = (_, _, _, _) => ()
```

- [ ] **Step 4: Wire the listener into `WorkspaceIndex.initialize`**

Modify `modules/navigation/src/ba/sake/basamake/navigation/indexing/WorkspaceIndex.scala`:

1. Add the constructor param (line 23):

```scala
class WorkspaceIndex(workspacePath: os.Path, symbolTable: SymbolTable, ignorePatterns: Vector[String] = Vector.empty, progressListener: IndexingProgressListener = IndexingProgressListener.noop) extends StrictLogging {
```

2. Replace the entire `initialize` method (current lines 59-115) with:

```scala
  def initialize(roots: List[SemanticdbDirs]): Unit = {
    logger.info(s"Initializing workspace index at $workspacePath")
    val relevantExtensions = Set("scala", "java")
    def skip(p: os.Path): Boolean =
      if os.isDir(p) then GitIgnoreEngine.alwaysSkipDirNames.contains(p.last) || ignoreEngine.isIgnored(p, isDir = true)
      else if os.isFile(p) then !relevantExtensions.contains(p.ext) || ignoreEngine.isIgnored(p, isDir = false)
      else true

    val sources = os.walk(workspacePath, skip = skip)
    val fileGroups = sources.groupBy(_.ext)
    val scalaFiles = fileGroups.getOrElse("scala", Vector.empty)
    val javaFiles = fileGroups.getOrElse("java", Vector.empty)
    logger.info(s"Found files: scala=${scalaFiles.size}, java=${javaFiles.size}")

    sourcesMap.clear()
    // a file opened between the walk and the clear must not be dropped
    openFiles.forEach(p => sourcesMap.putIfAbsent(p, SourceData.empty))
    (scalaFiles.toSet ++ javaFiles.toSet).foreach(p => sourcesMap.put(p, SourceData.empty))

    val total = (scalaFiles.toSet ++ javaFiles.toSet).size.toLong
    var done = 0L
    def report(msg: String): Unit =
      progressListener.onProgress(IndexingPhase.Workspace, done, total, msg)
    report("scanning workspace")

    // Pass A: index semanticdb DEFINITION occurrences from BSP-provided
    // (sourceRootDir, semanticdbDir) pairs into symbolTable, pair with sources.
    // No workspace-wide .semanticdb walk — only explicit dirs from data.json / BSP compile.
    if (roots.nonEmpty) {
      logger.info(s"Indexing semanticdb from ${roots.size} target root(s)")
      for (root <- roots if os.exists(root.semanticdbDir) && os.exists(root.sourceRootDir)) {
        val semDir = root.semanticdbDir
        val srcRoot = root.sourceRootDir
        val pairs = SemanticdbIndexing.indexSemanticdbDir(semDir, srcRoot, workspacePath, symbolTable)
        pairs.foreach { case (src, semPath) => setSemanticdbPath(src, semPath) }
        done += pairs.size.toLong
        report(s"semanticdb ${pairs.size} files")
        logger.info(s"Indexed ${pairs.size} semanticdb-paired source files from ${semDir}")
      }
      val paired = sourcesMap.values().asScala.count(_.semanticdbPath.isDefined)
      logger.info(s"Total semanticdb-paired source files: $paired")
    }

    // Pass B: extract from source AST for files WITHOUT semanticdb
    for (path <- scalaFiles if sourcesMap.get(path).semanticdbPath.isEmpty) {
      logger.debug(s"Extracting definitions from $path")
      try {
        val content = os.read(path)
        val extractor = ScalaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
      done += 1
      report(path.last)
    }
    for (path <- javaFiles if sourcesMap.get(path).semanticdbPath.isEmpty) {
      logger.debug(s"Extracting definitions from $path")
      try {
        val content = os.read(path)
        val extractor = JavaDefinitionsExtractor(symbolTable)
        extractor.extractFromContent(path.last, content, path)
      } catch {
        case e: Exception => logger.warn(s"Failed to extract $path: ${e.getMessage}")
      }
      done += 1
      report(path.last)
    }

    report(s"Indexed $total files")
    writeDebugDump()
  }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `deder exec -t test -m modules-navigation-test`
Expected: PASS — the two new tests plus all existing navigation tests (the default `noop` listener keeps every other `WorkspaceIndex` construction compiling and silent).

- [ ] **Step 6: Commit**

```bash
git add modules/navigation/src/ba/sake/basamake/navigation/indexing/IndexingProgressListener.scala modules/navigation/src/ba/sake/basamake/navigation/indexing/WorkspaceIndex.scala modules/navigation/test/src/ba/sake/basamake/navigation/indexing/WorkspaceIndexProgressTest.scala
git commit -m "Report workspace indexing progress via IndexingProgressListener"
```

---

### Task 2: Per-entry progress callback in `SourceJarIndexer`

**Files:**
- Modify: `modules/navigation/src/ba/sake/basamake/navigation/indexing/SourceJarIndexer.scala`
- Test: `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/SourceJarIndexerTest.scala` (append)

- [ ] **Step 1: Write the failing test**

Append to `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/SourceJarIndexerTest.scala` (inside the class):

```scala
  test("progress callback reports per-source-entry done/total") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir) // 2 source entries (Foo.java, Baz.scala)

    val fingerprint = "test_progress_bd5a1f"
    cleanCache(fingerprint)

    val events = scala.collection.mutable.ListBuffer[(Long, Long, String)]()
    SourceJarIndexer.index(jarPath, fingerprint, (done, total, name) => events += ((done, total, name)))

    assertEquals(events.last, (2L, 2L, jarPath.last), "total must count source entries only")
    assertEquals(events.map(_._1), List(1L, 2L), "done must increment per source entry")
    assert(events.forall(_._2 == 2L), "every event carries the pre-counted total")
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `deder exec -t test -m modules-navigation-test`
Expected: FAIL — `index` has no parameter named `progress`.

- [ ] **Step 3: Add the callback + entry pre-count**

Modify `SourceJarIndexer.scala`:

1. Change the signature (line 40):

```scala
  def index(source: os.Path, fingerprint: String, progress: (Long, Long, String) => Unit = (_, _, _) => ()): Unit = {
```

2. Replace the `val sink = try { ... }` block (current lines 59-89) with (note: `zip.entries()` returns a fresh enumeration each call, so counting then iterating the same `ZipFile` is fine):

```scala
    val sink = try {
      val zip = new ZipFile(source.toIO)
      try {
        // pre-count source entries for honest progress totals
        // (zip.size() includes directories and non-source files)
        val totalEntries = zip.entries().asScala.count(e => !e.isDirectory && isSourceEntry(e.getName)).toLong
        var doneEntries = 0L
        LmdbSerializer.streamingSave(indexPath, cacheDir) { sink =>
          val scalaExtractor = new ScalaDefinitionsExtractor(sink)
          val javaExtractor = new JavaDefinitionsExtractor(sink)
          zip.entries().asScala.foreach { entry =>
            if (!entry.isDirectory && isSourceEntry(entry.getName)) {
              doneEntries += 1
              try {
                val entryPath = entry.getName
                val content = new String(zip.getInputStream(entry).readAllBytes(), "UTF-8")
                // the recorded def path is where the file WILL live once extracted
                val extractedPath = srcRoot / os.RelPath(entryPath)
                if (entryPath.endsWith(".java"))
                  javaExtractor.extractFromContent(entryPath, content, extractedPath)
                else
                  scalaExtractor.extractFromContent(entryPath, content, extractedPath)
              } catch {
                case NonFatal(e) =>
                  logger.warn(s"Skipping unindexable entry ${entry.getName} in $source: ${e.getMessage}")
              }
              progress(doneEntries, totalEntries, source.last)
            }
          }
        }
      } finally zip.close()
    } catch {
      case e: Exception =>
        os.remove.all(cacheDir)
        logger.error(s"Failed to index $source: ${e.getMessage}", e)
        throw e
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `deder exec -t test -m modules-navigation-test`
Expected: PASS — the new test + all existing `SourceJarIndexerTest` tests (default callback keeps old call sites compiling).

- [ ] **Step 5: Commit**

```bash
git add modules/navigation/src/ba/sake/basamake/navigation/indexing/SourceJarIndexer.scala modules/navigation/test/src/ba/sake/basamake/navigation/indexing/SourceJarIndexerTest.scala
git commit -m "Report per-entry progress from SourceJarIndexer"
```

---

### Task 3: Priority job scheduler in `IndexedSymbolTable`

**Files:**
- Modify: `modules/navigation/src/ba/sake/basamake/navigation/indexing/IndexedSymbolTable.scala`
- Test: `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/IndexedSymbolTableProgressTest.scala` (new)

- [ ] **Step 1: Write the failing tests**

Create `modules/navigation/test/src/ba/sake/basamake/navigation/indexing/IndexedSymbolTableProgressTest.scala`:

```scala
package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

class IndexedSymbolTableProgressTest extends FunSuite, TestCacheRoot {

  private def cacheDir(fp: String) = SourceJarIndexer.cacheRoot / os.RelPath(fp)

  private def cleanCache(fp: String): Unit = {
    if (os.exists(cacheDir(fp))) os.remove.all(cacheDir(fp))
  }

  private def eventually(cond: => Boolean, timeoutMs: Long = 20000): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(50)
    cond
  }

  /** jar with package com.example: class Foo, object Baz */
  private def buildJar(tempDir: os.Path, name: String): os.Path = {
    val jarPath = tempDir / name
    val javaFile = """package com.example;
public class Foo {
    public void bar() {}
}
"""
    val scalaFile = """package com.example
object Baz {
  def qux(): Unit = ()
}
"""
    val zip = new ZipOutputStream(new FileOutputStream(jarPath.toIO))
    try {
      zip.putNextEntry(new ZipEntry("Foo.java")); zip.write(javaFile.getBytes("UTF-8")); zip.closeEntry()
      zip.putNextEntry(new ZipEntry("Baz.scala")); zip.write(scalaFile.getBytes("UTF-8")); zip.closeEntry()
    } finally zip.close()
    jarPath
  }

  test("jobs are queued by priority: scala-lang jars before normal jars") {
    val tempDir = os.temp.dir()
    val normalJar = buildJar(tempDir, "zzz-normal-sources.jar")
    val scalaJar = buildJar(tempDir, "scala3-library_3-3.8.4-sources.jar")
    cleanCache(Fingerprint.fromJarPath(normalJar))
    cleanCache(Fingerprint.fromJarPath(scalaJar))

    // workerCount=0 → no worker threads → the queue stays observable
    val deps = new IndexedSymbolTable(workerCount = 0)
    deps.ensureIndexed(List(normalJar, scalaJar))

    assertEquals(deps.queuedJobs,
      List("scala3-library_3-3.8.4-sources.jar", "zzz-normal-sources.jar"),
      "scala-lang jar must be picked before the normal jar despite later enqueue")
  }

  test("JDK job is queued first (priority 0) when src.zip exists") {
    val srcZip = os.Path(System.getProperty("java.home")) / "lib" / "src.zip"
    assume(os.exists(srcZip), "JDK src.zip required for this test")

    val tempDir = os.temp.dir()
    val normalJar = buildJar(tempDir, "zzz-normal-sources.jar")
    cleanCache(Fingerprint.fromJarPath(normalJar))

    val deps = new IndexedSymbolTable(workerCount = 0)
    deps.ensureJdkIndexed()
    deps.ensureIndexed(List(normalJar))

    assertEquals(deps.queuedJobs.head, "src.zip", "JDK must be first in the queue")
  }

  test("org.scala-lang group via sibling POM also gets priority") {
    val tempDir = os.temp.dir()
    // Fingerprint.fromJarPath reads <same-dir>/<artifact>.pom for the groupId
    val pomJar = buildJar(tempDir, "foo-sources.jar")
    os.write(tempDir / "foo.pom",
      """<project><groupId>org.scala-lang</groupId><artifactId>foo</artifactId><version>1.0</version></project>""")
    val normalJar = buildJar(tempDir, "zzz-normal-sources.jar")
    cleanCache(Fingerprint.fromJarPath(pomJar))
    cleanCache(Fingerprint.fromJarPath(normalJar))

    val deps = new IndexedSymbolTable(workerCount = 0)
    deps.ensureIndexed(List(normalJar, pomJar))

    assertEquals(deps.queuedJobs.head, "foo-sources.jar",
      "a jar whose POM group is org.scala-lang must beat a normal jar")
  }

  test("background indexing reports jar-level progress (0..N)") {
    val tempDir = os.temp.dir()
    val jarA = buildJar(tempDir, "a-sources.jar")
    val jarB = buildJar(tempDir, "b-sources.jar")
    cleanCache(Fingerprint.fromJarPath(jarA))
    cleanCache(Fingerprint.fromJarPath(jarB))

    val listener = new RecordingProgressListener
    val deps = new IndexedSymbolTable(progressListener = listener)
    deps.ensureIndexed(List(jarA, jarB))

    assert(eventually(listener.ofPhase(IndexingPhase.Dependencies).lastOption.exists(e => e._2 == e._3)),
      "both jars must complete")
    val evs = listener.ofPhase(IndexingPhase.Dependencies) // snapshot AFTER the wait
    assertEquals(evs.head, (0L, 1L, "a-sources.jar"), "first event: 0/1 while the first jar is enqueued")
    val last = evs.last
    assertEquals(last._1, 2L, "done must reach 2")
    assertEquals(last._2, 2L, "total must reach 2")
    assert(last._3.startsWith("Indexed "), s"final message should say Indexed, got ${last._3}")
  }
}
```

Note: `RecordingProgressListener` was created in Task 1 — same package, no import needed. `deps.queuedJobs` and the `workerCount` constructor param don't exist yet — that's the failing part.

- [ ] **Step 2: Run tests to verify they fail**

Run: `deder exec -t test -m modules-navigation-test`
Expected: FAIL — `value queuedJobs is not a member of IndexedSymbolTable`, `workerCount` not a constructor param, `progressListener` not a constructor param.

- [ ] **Step 3: Rewrite `IndexedSymbolTable`**

Replace the ENTIRE contents of `modules/navigation/src/ba/sake/basamake/navigation/indexing/IndexedSymbolTable.scala` with:

```scala
package ba.sake.basamake.navigation.indexing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
  * Indexing is LAZY, target-scoped and PRIORITIZED: `registerTarget` only records
  * a target's dependency sources (and registers already-cached jars for routing) —
  * nothing is parsed. `ensureIndexed` / `ensureIndexedFor` / lookup misses enqueue
  * the UNCACHED jars on a shared priority queue (single-flight per fingerprint);
  * `workerCount` worker threads pick jobs by priority: the JDK first (0 — enqueued
  * during initialize, so it always starts before any dep jar), then scala-lang
  * jars (1 — most user code depends on them), then everything else (2). Bounded
  * concurrency: parsing ~90 source jars at once used to spike committed heap past
  * 1GB; index writes are streamed into LMDB, so no in-memory symbol table is built.
  */
class IndexedSymbolTable(
    progressListener: IndexingProgressListener = IndexingProgressListener.noop,
    workerCount: Int = 2
) extends SymbolTable with StrictLogging {

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
  // fingerprints currently queued or indexing (dedupe across targets/calls)
  private val indexing = ConcurrentHashMap.newKeySet[String]()

  // ── priority job queue ────────────────────────────────────────
  // JDK = 0 (always first), scala-lang = 1, everything else = 2.
  // `seq` breaks ties FIFO. The worker threads ARE the concurrency bound.
  private final case class IndexJob(priority: Int, seq: Long, fp: String, src: os.Path, phase: IndexingPhase)
  private val jobQueue = new java.util.concurrent.PriorityBlockingQueue[IndexJob](16,
    java.util.Comparator.comparingInt[IndexJob](_.priority).thenComparingLong(_.seq))
  private val seqCounter = new AtomicLong(0)
  private var workersStarted = false

  // Dependencies-phase progress: jar-level done/total (entries within a jar are
  // reported per-entry by SourceJarIndexer and forwarded as the message).
  private val depsTotal = new AtomicLong(0)
  private val depsDone = new AtomicLong(0)

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
          depsTotal.incrementAndGet()
          reportDeps(src.last)
          enqueue(fp, src, priorityOf(fp, src.last), IndexingPhase.Dependencies)
        }
      }
    }
  }

  /** Ensure the JDK src.zip (`<java.home>/lib/src.zip`) is cached and registered.
    * No-op when the runtime has no sources. Enqueued at priority 0 — the JDK is
    * always indexed before any dependency jar. */
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
      } else if indexing.add(fp) then {
        logger.info(s"Indexing JDK sources $srcZip in background")
        progressListener.onProgress(IndexingPhase.Jdk, 0, 1, "src.zip")
        enqueue(fp, srcZip, 0, IndexingPhase.Jdk)
      }
    }
  }

  /** Queue contents as file names, ordered by (priority, seq). Test seam — lets
    * tests assert the priority order deterministically (workerCount = 0 freezes
    * the queue). */
  private[navigation] def queuedJobs: List[String] =
    jobQueue.asScala.toList.sortBy(j => (j.priority, j.seq)).map(_.src.last)

  // ── SymbolTable impl ──────────────────────────────────────────

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

  /** Priority: 0 = JDK (enqueued by ensureJdkIndexed directly), 1 = scala-lang
    * jars (maven group org.scala-lang, or flat names when no POM exists),
    * 2 = everything else. */
  private def priorityOf(fp: String, name: String): Int =
    if fp.startsWith("org_scala-lang/") ||
       name.startsWith("scala-library") || name.startsWith("scala3-library") ||
       name.startsWith("scala-reflect") || name.startsWith("scala3-compiler") ||
       name.startsWith("scala3-interfaces")
    then 1 else 2

  private def enqueue(fp: String, src: os.Path, priority: Int, phase: IndexingPhase): Unit = {
    jobQueue.put(IndexJob(priority, seqCounter.incrementAndGet(), fp, src, phase))
    ensureWorkers()
  }

  private def ensureWorkers(): Unit = {
    if (!workersStarted) synchronized {
      if (!workersStarted) {
        workersStarted = true
        (1 to workerCount).foreach(_ => Thread.ofVirtual().start(() => workerLoop()))
      }
    }
  }

  private def workerLoop(): Unit = {
    while (true) {
      val job = jobQueue.take()
      try {
        SourceJarIndexer.index(job.src, job.fp, (done, total, name) => {
          job.phase match {
            case IndexingPhase.Jdk =>
              progressListener.onProgress(IndexingPhase.Jdk, done, total, name)
            case _ =>
              reportDeps(s"$name ${done * 100 / total}%")
          }
        })
        register(job.fp, job.src)
        registeredFps.add(job.fp)
      } catch {
        case NonFatal(e) => logger.warn(s"Failed to index ${job.src}: ${e.getMessage}")
      } finally {
        indexing.remove(job.fp)
        job.phase match {
          case IndexingPhase.Jdk =>
            progressListener.onProgress(IndexingPhase.Jdk, 1, 1, "JDK sources indexed")
          case _ =>
            depsDone.incrementAndGet()
            reportDeps(s"Indexed ${job.src.last}")
        }
      }
    }
  }

  private def reportDeps(msg: String): Unit =
    progressListener.onProgress(IndexingPhase.Dependencies, depsDone.get(), depsTotal.get(), msg)

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
        case Some(src) =>
          depsTotal.incrementAndGet()
          reportDeps(src.last)
          enqueue(fp, src, priorityOf(fp, src.last), IndexingPhase.Dependencies)
        case None => indexing.remove(fp)
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
                  result = Some(d)
                }
              } catch {
                case NonFatal(e) => handleCorrupt(fp, e)
              }
            case _ => ()
          }
        } else if indexing.add(fp) then {
          logger.info(s"Indexing dependency source ${src.last} in background (lookup miss)")
          depsTotal.incrementAndGet()
          reportDeps(src.last)
          enqueue(fp, src, priorityOf(fp, src.last), IndexingPhase.Dependencies)
        }
      }
    }
    result
  }

  /** Fallback lookup through the package-route map (built from the metadata.json of
    * every registered jar). First-wins by sorted fingerprint — can pick the wrong
    * jar when two jars share a package; the candidate path above is preferred. */
  private def getFromRoute(symbol: String): Option[SymbolDefinition] = {
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
```

- [ ] **Step 4: Run all navigation tests**

Run: `deder exec -t test -m modules-navigation-test`
Expected: PASS — the 3 new tests AND all existing `IndexedSymbolTableTest` / `DepsGotoDefTest` tests (queue replaces semaphore; `queuedJobs` + `workerCount` are new).

If `IndexedSymbolTableTest` "duplicate symbols across jars resolve deterministically" is flaky, that's pre-existing behavior — re-run once before investigating.

- [ ] **Step 5: Commit**

```bash
git add modules/navigation/src/ba/sake/basamake/navigation/indexing/IndexedSymbolTable.scala modules/navigation/test/src/ba/sake/basamake/navigation/indexing/IndexedSymbolTableProgressTest.scala
git commit -m "Prioritize JDK and scala-lang dependency indexing via job queue"
```

---

### Task 4: `IndexingProgressReporter` (LSP workDoneProgress) in the main module

**Files:**
- Create: `modules/main/src/ba/sake/basamake/lsp/IndexingProgressReporter.scala`
- Test: `modules/main/test/src/ba/sake/basamake/lsp/IndexingProgressReporterTest.scala` (new)

- [ ] **Step 1: Write the failing tests**

Create `modules/main/test/src/ba/sake/basamake/lsp/IndexingProgressReporterTest.scala`:

```scala
package ba.sake.basamake.lsp

import java.util.concurrent.{CompletableFuture, CopyOnWriteArrayList}
import munit.FunSuite
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import scala.jdk.CollectionConverters.*
import ba.sake.basamake.navigation.indexing.IndexingPhase

class IndexingProgressReporterTest extends FunSuite {

  /** Fake client that records createProgress + notifyProgress calls. */
  private final class FakeClient(created: java.util.List[WorkDoneProgressCreateParams],
                                 sent: java.util.List[ProgressParams]) extends LanguageClient {
    override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = ()
    override def telemetryEvent(x: Any): Unit = ()
    override def showMessage(p: MessageParams): Unit = ()
    override def showMessageRequest(p: ShowMessageRequestParams) =
      CompletableFuture.completedFuture(null.asInstanceOf[MessageActionItem])
    override def logMessage(p: MessageParams): Unit = ()
    override def applyEdit(p: ApplyWorkspaceEditParams) =
      CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))
    override def createProgress(p: WorkDoneProgressCreateParams): CompletableFuture[Void] = {
      created.add(p)
      CompletableFuture.completedFuture(null.asInstanceOf[Void])
    }
    override def notifyProgress(p: ProgressParams): Unit = sent.add(p)
  }

  /** (token, kind, message) of one ProgressParams — kind+message live on the
    * concrete Begin/Report/End classes, not on WorkDoneProgressNotification. */
  private def progressEvent(p: ProgressParams): (String, String, String) = {
    val n = p.getValue.getLeft
    val msg = n match {
      case b: WorkDoneProgressBegin  => b.getMessage
      case r: WorkDoneProgressReport => r.getMessage
      case e: WorkDoneProgressEnd    => e.getMessage
    }
    (p.getToken.getLeft, n.getKind, msg)
  }

  test("sends begin/report/end with counts and percentages") {
    val created = new CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = new IndexingProgressReporter
    rep.setClient(new FakeClient(created, sent))
    rep.setEnabled(true)
    rep.setThrottleMillis(0) // back-to-back events must all pass in this test

    rep.onProgress(IndexingPhase.Dependencies, 0, 130, "jarA")
    rep.onProgress(IndexingPhase.Dependencies, 12, 130, "jarB 45%")
    rep.onProgress(IndexingPhase.Dependencies, 130, 130, "Indexed jarB")

    assertEquals(created.asScala.map(_.getToken.getLeft).toList, List("basamake-deps"),
      "one token must be created for the phase")
    val events = sent.asScala.toList.map(progressEvent)
    assertEquals(events.head, ("basamake-deps", "begin", "0/130 jarA"))
    assertEquals(events(1), ("basamake-deps", "report", "12/130 jarB 45%"))
    assertEquals(events.last, ("basamake-deps", "end", "Indexed jarB"))
  }

  test("throttles reports but always sends begin and end") {
    val created = new CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = new IndexingProgressReporter
    rep.setClient(new FakeClient(created, sent))
    rep.setEnabled(true)

    rep.onProgress(IndexingPhase.Workspace, 0, 1000, "scanning")
    (1 to 1000).foreach { i => rep.onProgress(IndexingPhase.Workspace, i, 1000, s"file$i") }
    rep.onProgress(IndexingPhase.Workspace, 1000, 1000, "done")

    val kinds = sent.asScala.toList.map(progressEvent(_)._2)
    assertEquals(kinds.head, "begin")
    assertEquals(kinds.last, "end")
    assert(kinds.size < 100, s"1000 events must be throttled well below 100 notifications, got ${kinds.size}")
  }

  test("emits nothing when disabled (no window.workDoneProgress capability)") {
    val created = new CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
    val sent = new CopyOnWriteArrayList[ProgressParams]()
    val rep = new IndexingProgressReporter
    rep.setClient(new FakeClient(created, sent)) // never setEnabled

    rep.onProgress(IndexingPhase.Jdk, 0, 1, "src.zip")
    rep.onProgress(IndexingPhase.Jdk, 1, 1, "src.zip")

    assertEquals(created.size(), 0)
    assertEquals(sent.size(), 0)
  }

  test("disables permanently when createProgress fails — indexing must not throw") {
    val failing = new LanguageClient {
      override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = ()
      override def telemetryEvent(x: Any): Unit = ()
      override def showMessage(p: MessageParams): Unit = ()
      override def showMessageRequest(p: ShowMessageRequestParams) =
        CompletableFuture.completedFuture(null.asInstanceOf[MessageActionItem])
      override def logMessage(p: MessageParams): Unit = ()
      override def applyEdit(p: ApplyWorkspaceEditParams) =
        CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))
      override def createProgress(p: WorkDoneProgressCreateParams): CompletableFuture[Void] =
        throw new RuntimeException("client does not support progress")
    }
    val rep = new IndexingProgressReporter
    rep.setClient(failing)
    rep.setEnabled(true)

    rep.onProgress(IndexingPhase.Jdk, 0, 1, "src.zip") // must NOT throw
    rep.onProgress(IndexingPhase.Jdk, 1, 1, "src.zip") // must NOT throw
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `deder exec -t test -m modules-main-test`
Expected: FAIL — `object IndexingProgressReporter is not a member of package ba.sake.basamake.lsp`.

- [ ] **Step 3: Create the reporter**

Create `modules/main/src/ba/sake/basamake/lsp/IndexingProgressReporter.scala`:

```scala
package ba.sake.basamake.lsp

import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.jsonrpc.messages.Either
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.indexing.{IndexingPhase, IndexingProgressListener}

/** Forwards navigation indexing events to the LSP client as `window/workDoneProgress`
  * items — one per phase (workspace / dependencies / JDK). VS Code renders these in
  * the status bar natively (no extension changes needed).
  *
  * Defensive by design: lsp4j's LanguageClient default methods THROW
  * UnsupportedOperationException, and some clients never register a progress token.
  * Any failure disables this reporter permanently (logged once) — indexing must
  * never be held hostage by progress UI.
  *
  * Lifecycle: constructed before the client proxy exists; `setClient` is called
  * from connect(), `setEnabled` from initialize() with the client's
  * `window.workDoneProgress` capability. Events before enable are dropped —
  * nothing indexes before initialize anyway. */
class IndexingProgressReporter extends IndexingProgressListener with StrictLogging {

  @volatile private var client: LanguageClient = _
  @volatile private var enabled: Boolean = false

  private var throttleNanos = 100L * 1000000L // 100ms

  private final class PhaseState(val token: String, val title: String) {
    var active: Boolean = false
    var lastSendNanos: Long = 0L
  }

  private val workspace = new PhaseState("basamake-workspace", "Indexing workspace")
  private val deps = new PhaseState("basamake-deps", "Indexing dependencies")
  private val jdk = new PhaseState("basamake-jdk", "Indexing JDK sources")

  private def stateOf(phase: IndexingPhase): PhaseState = phase match {
    case IndexingPhase.Workspace    => workspace
    case IndexingPhase.Dependencies => deps
    case IndexingPhase.Jdk          => jdk
  }

  /** Called from connect() — the client proxy arrives before initialize. */
  def setClient(c: LanguageClient): Unit = client = c

  /** Called from initialize() with the client's window.workDoneProgress capability. */
  def setEnabled(flag: Boolean): Unit = enabled = flag

  /** Test seam — 0 disables throttling (back-to-back events all pass). */
  private[lsp] def setThrottleMillis(ms: Long): Unit = throttleNanos = ms * 1000000L

  override def onProgress(phase: IndexingPhase, done: Long, total: Long, message: String): Unit = {
    if (!enabled || client == null) return
    if (total <= 0) return
    val st = stateOf(phase)
    val now = System.nanoTime()
    st.synchronized {
      if (!st.active) {
        if (done >= total) return // stray completion for a never-begun phase
        begin(st, total, message)
      } else if (done >= total) {
        end(st, message)
      } else if (now - st.lastSendNanos >= throttleNanos) {
        report(st, done, total, message)
      }
    }
  }

  private def begin(st: PhaseState, total: Long, message: String): Unit = {
    try {
      client.createProgress(new WorkDoneProgressCreateParams(Either.forLeft(st.token)))
        .get(5, TimeUnit.SECONDS)
    } catch {
      case e: Exception =>
        logger.warn(s"Client rejected progress token ${st.token} — disabling progress: ${e.getMessage}")
        enabled = false
        return
    }
    val b = new WorkDoneProgressBegin()
    b.setTitle(st.title)
    b.setCancellable(false)
    b.setPercentage(0)
    b.setMessage(s"0/$total $message")
    notify(st, b)
    st.active = true
    st.lastSendNanos = System.nanoTime()
  }

  private def report(st: PhaseState, done: Long, total: Long, message: String): Unit = {
    val r = new WorkDoneProgressReport()
    r.setPercentage((done * 100 / total).toInt)
    r.setMessage(s"$done/$total $message")
    notify(st, r)
    st.lastSendNanos = System.nanoTime()
  }

  private def end(st: PhaseState, message: String): Unit = {
    val e = new WorkDoneProgressEnd()
    e.setMessage(message)
    notify(st, e)
    st.active = false
  }

  private def notify(st: PhaseState, value: WorkDoneProgressNotification): Unit = {
    try {
      client.notifyProgress(new ProgressParams(Either.forLeft(st.token), Either.forLeft(value)))
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to send progress for ${st.token} — disabling progress: ${e.getMessage}")
        enabled = false
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `deder exec -t test -m modules-main-test`
Expected: PASS — all 4 reporter tests (existing main tests are unaffected — the reporter is not wired into the server yet).

- [ ] **Step 5: Commit**

```bash
git add modules/main/src/ba/sake/basamake/lsp/IndexingProgressReporter.scala modules/main/test/src/ba/sake/basamake/lsp/IndexingProgressReporterTest.scala
git commit -m "Add IndexingProgressReporter (LSP workDoneProgress, throttled, fail-safe)"
```

---

### Task 5: Wire the reporter + async `initialize` in `BasamakeLanguageServer`

**Files:**
- Modify: `modules/main/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala`
- Test: `modules/main/test/src/ba/sake/basamake/lsp/BasamakeLanguageServerTest.scala`

- [ ] **Step 1: Write the failing test**

In `modules/main/test/src/ba/sake/basamake/lsp/BasamakeLanguageServerTest.scala`:

1. Add an `eventually` helper next to `posAt` (around line 60):

```scala
  private def eventually(cond: => Boolean, timeoutMs: Long = 20000): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(50)
    cond
  }
```

2. Add a progress-capturing fake client + a progress-event helper next to `fakeClient` (around line 37):

```scala
  /** LanguageClient fake that captures workDoneProgress create + notify calls. */
  private def progressClient(created: java.util.List[WorkDoneProgressCreateParams],
                             sent: java.util.List[ProgressParams]): LanguageClient =
    new LanguageClient {
      override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = ()
      override def telemetryEvent(x: Any): Unit = ()
      override def showMessage(p: MessageParams): Unit = ()
      override def showMessageRequest(p: ShowMessageRequestParams) =
        CompletableFuture.completedFuture(null.asInstanceOf[MessageActionItem])
      override def logMessage(p: MessageParams): Unit = ()
      override def applyEdit(p: ApplyWorkspaceEditParams) =
        CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))
      override def createProgress(p: WorkDoneProgressCreateParams): CompletableFuture[Void] = {
        created.add(p)
        CompletableFuture.completedFuture(null.asInstanceOf[Void])
      }
      override def notifyProgress(p: ProgressParams): Unit = sent.add(p)
    }

  /** (token, kind, message) of one ProgressParams — kind+message live on the
    * concrete Begin/Report/End classes, not on WorkDoneProgressNotification. */
  private def progressEvent(p: ProgressParams): (String, String, String) = {
    val n = p.getValue.getLeft
    val msg = n match {
      case b: WorkDoneProgressBegin  => b.getMessage
      case r: WorkDoneProgressReport => r.getMessage
      case e: WorkDoneProgressEnd    => e.getMessage
    }
    (p.getToken.getLeft, n.getKind, msg)
  }
```

3. Append the new test at the end of the class (before the final `}`):

```scala
  // ═══════════════════════════════════════════════════════════════
  // indexing progress via workDoneProgress
  // ═══════════════════════════════════════════════════════════════

  test("initialize: reports workspace indexing progress via workDoneProgress") {
    val root = copyFixture("nopackages", "lsp-progress")
    try {
      val created = new java.util.concurrent.CopyOnWriteArrayList[WorkDoneProgressCreateParams]()
      val sent = new java.util.concurrent.CopyOnWriteArrayList[ProgressParams]()
      val server = new BasamakeLanguageServer(root)
      server.connect(progressClient(created, sent))

      val params = new InitializeParams()
      val caps = new ClientCapabilities()
      val win = new WindowClientCapabilities()
      win.setWorkDoneProgress(true)
      caps.setWindow(win)
      params.setCapabilities(caps)
      server.initialize(params).get(10, TimeUnit.SECONDS)

      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val tokens = created.asScala.map(_.getToken.getLeft).toSet
      assert(tokens.contains("basamake-workspace"), s"workspace progress token must be created, got $tokens")

      val wsEvents = sent.asScala.toList
        .filter(_.getToken.getLeft == "basamake-workspace")
        .map(progressEvent)
      val kinds = wsEvents.map(_._2)
      assertEquals(kinds.head, "begin")
      assertEquals(kinds.last, "end")

      val expectedTotal = os.walk(root).count(p => p.ext == "scala" || p.ext == "java")
      val beginMsg = wsEvents.head._3
      assert(beginMsg.startsWith(s"0/$expectedTotal"),
        s"begin message should carry the total, got: $beginMsg")
    } finally os.remove.all(root)
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `deder exec -t test -m modules-main-test`
Expected: FAIL — `value isWorkspaceIndexingDone is not a member of BasamakeLanguageServer` (also the existing definition tests may already be flaky — that is fixed in Step 4).

- [ ] **Step 3: Wire the server**

Modify `modules/main/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala`:

1. Add the reporter + indexing-done flag fields (after line 30, `hoverProvider`):

```scala
  private val progressReporter = new IndexingProgressReporter
  private val workspaceIndexingDone = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** True once the background workspace indexing (launched by initialize) has
    * finished — used by tests to await index readiness. */
  private[lsp] def isWorkspaceIndexingDone: Boolean = workspaceIndexingDone.get()
```

2. Pass the reporter into the two indexes (lines 21-28 become):

```scala
  private val workspaceSymbolTable = new InMemorySymbolTable
  private val depsSymbolTable = new IndexedSymbolTable(progressReporter)
  private val symbolTable = new CompositeSymbolTable(workspaceSymbolTable, depsSymbolTable)
  private val workspaceIndex = new WorkspaceIndex(
    workspacePath,
    symbolTable,
    BasamakeConfig.load(workspacePath).ignorePatterns.toVector,
    progressReporter
  )
```

3. In `connect` (line 33-36), forward the client:

```scala
  override def connect(client: LanguageClient): Unit = {
    logger.debug(s"Client connected: ${client}")
    this.client = client
    progressReporter.setClient(client)
  }
```

4. Replace the body of `initialize` (lines 60-79 — from `val (roots, warmDeps) = ...` through `CompletableFuture.completedFuture(...)`) with:

```scala
    // Progress needs the client's window/workDoneProgress/create handler, which
    // vscode-languageclient registers only AFTER the initialize handshake completes
    // — so indexing moves to a background thread and initialize returns early.
    val workDoneProgress = Option(params.getCapabilities)
      .flatMap(c => Option(c.getWindow))
      .flatMap(w => Option(w.getWorkDoneProgress))
      .getOrElse(false)
    progressReporter.setEnabled(workDoneProgress)

    val (roots, warmDeps) = loadBspDataFromDataJson()
    Thread.ofVirtual().start(() => {
      try {
        workspaceIndex.initialize(roots)
      } catch {
        case e: Exception => logger.error(s"Failed to initialize workspace index: ${e.getMessage}")
      } finally {
        workspaceIndexingDone.set(true)
      }
    })
    // Dependency sources are NOT indexed eagerly: BspManager registers the warm-start
    // targets (cached jars only) and indexes a target's jars lazily when one of its
    // files is opened / poked. The JDK index still runs in the background — cached
    // index loads lazily on first lookup; a cold JDK indexes once in the background,
    // prioritized ahead of all dependency jars.
    try {
      depsSymbolTable.ensureJdkIndexed()
    } catch {
      case e: Exception =>
        logger.error(s"Failed to start JDK indexing: ${e.getMessage}")
    }
    // Wire BSP manager (discovers .bsp configs, lazy spawn on first poke)
    bspManager.initialize(workspacePath, client, warmDeps)
    CompletableFuture.completedFuture(new InitializeResult(capabilities))
```

- [ ] **Step 4: Make the existing definition/reference tests await async indexing**

The tests that call `server.initialize(...)` then immediately `server.definition(...)` / `server.references(...)` are now racy. In `BasamakeLanguageServerTest.scala`, after EVERY `server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)` line, insert:

```scala
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")
```

(9 call sites — lines ~77, 95, 115, 141, 179, 219, 258, 283, 311. Harmless in the capability-only tests; required in the definition/references/hover tests.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `deder exec -t test -m modules-main-test`
Expected: PASS — the new progress test + all existing tests (definition/references/hover tests now await indexing). `LspTransportTest` needs no change: it sends no `window.workDoneProgress` capability, so the reporter stays disabled and the transport fake's default-throwing `notifyProgress` is never called.

- [ ] **Step 6: Commit**

```bash
git add modules/main/src/ba/sake/basamake/lsp/BasamakeLanguageServer.scala modules/main/test/src/ba/sake/basamake/lsp/BasamakeLanguageServerTest.scala
git commit -m "Report indexing progress from server; move workspace indexing off initialize thread"
```

---

### Task 6: Update architecture docs

**Files:**
- Modify: `.agents/AGENTS.md`

- [ ] **Step 1: Update the indexing bullets**

In `.agents/AGENTS.md`:

1. In the "Register targets, index lazily" bullet (line 39), change:

> A target's UNCACHED jars are indexed in the background (virtual threads, single-flight per fingerprint)

to:

> A target's UNCACHED jars are indexed in the background (single-flight per fingerprint) via a **priority job queue** — the JDK always first (enqueued at priority 0 during initialize), then `org.scala-lang` jars (scala-library, scala3-library, scala3-compiler, ...), then everything else

2. Replace the "Background indexing is bounded" bullet (line 43):

> - **Background indexing is bounded** (`indexLimiter`, 2 permits) — parsing ~90 source jars concurrently used to spike committed heap past 1GB. Index writes are streamed into LMDB (see below), so cold indexing peaks around 1GB committed on a cold cache and stays low afterwards; idle memory is left to G1's own ergonomics

with:

> - **Background indexing is bounded + prioritized** — 2 worker threads pull from a `PriorityBlockingQueue` (JDK = 0, scala-lang = 1, rest = 2); parsing ~90 source jars concurrently used to spike committed heap past 1GB. Index writes are streamed into LMDB (see below), so cold indexing peaks around 1GB committed on a cold cache and stays low afterwards; idle memory is left to G1's own ergonomics

3. Add a new bullet after the "Background indexing is bounded" bullet:

> - **Indexing progress** — the navigation module emits `IndexingProgressListener` events (per-phase `Workspace`/`Dependencies`/`Jdk`, done/total counts); `IndexingProgressReporter` (main) forwards them to the LSP client as `window/workDoneProgress` items (throttled to 100ms per phase, gated on the client's `window.workDoneProgress` capability, fail-safe: any client error disables the reporter). Workspace indexing runs on a background thread launched by `initialize()` (the client's workDoneProgress handler only exists after the initialize handshake); `isWorkspaceIndexingDone` exposes readiness for tests

- [ ] **Step 2: Full test run**

Run: `deder exec -t test`
Expected: PASS — navigation + main suites.

- [ ] **Step 3: Commit**

```bash
git add .agents/AGENTS.md
git commit -m "Docs: priority indexing queue + progress reporting"
```

---

### Task 7: End-to-end verification + hygiene

- [ ] **Step 1: Manual smoke test with the example project**

Build the fat JAR: `deder exec -t assembly -m modules-main`

Run the server against `examples/hello` and inspect the log:

```bash
cd examples/hello && java -jar ../../.deder/out/modules-main/assembly/out.jar --workspace . > /tmp/opencode/basamake-lsp-out.txt 2>&1 &
```

Expected: `.basamake/logs/basamake.log` shows the usual startup lines; the LSP stdout contains only JSON-RPC (no text). Kill the server afterwards.

(Full VS Code verification: rebuild the fat JAR, copy into `../basamake-vscode/`, Reload Window, cold-clear `~/.cache/basamake/deps` — the status bar should show "Indexing workspace 2/2", "Indexing dependencies N/M", "Indexing JDK sources" items with live counts.)

- [ ] **Step 2: `deder shutdown`**

Run: `deder shutdown` (AGENTS.md hygiene — stale server processes block new connections).
