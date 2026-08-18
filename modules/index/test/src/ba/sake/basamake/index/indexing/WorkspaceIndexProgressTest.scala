package ba.sake.basamake.index.indexing

import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, Range => SdbRange, SymbolOccurrence}
import ba.sake.basamake.index.InMemorySymbolTable
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

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
      assertEquals(evs.map(_._2).toSet, Set(3L), "every event must carry total=3")
      assertEquals(evs.map(_._1).distinct, List(0L, 1L, 2L, 3L), "done must be monotonic 0..3")
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
      assertEquals(evs.head, (0L, 2L, "scanning workspace"))
      assertEquals(evs.map(_._1).distinct, List(0L, 2L))
      assertEquals(evs.last, (2L, 2L, "Indexed 2 files"))
    } finally os.remove.all(root)
  }

  /** Fixture: src/main/scala/{Main,utils}.scala with hand-written semanticdb at
    * the conventional target layout, plus `fallbackCount` unpaired Fallback*.scala
    * files. Main.scala references `ext.getMsg()` — unresolvable by the source
    * parser, so `_empty_/utils.getMsg().` can ONLY come from semanticdb.
    * @return the SemanticDB output dir (parent of META-INF) */
  private def buildPairingFixture(root: os.Path, fallbackCount: Int): os.Path = {
    val srcDir = root / "src" / "main" / "scala"
    os.makeDir.all(srcDir)
    val semDir = root / "target" / "scala-3.8.4" / "meta"
    os.makeDir.all(semDir / "META-INF" / "semanticdb" / "src" / "main" / "scala")

    // Main.scala + utils.scala get semanticdb pairing; extra files are unpaired
    // → they must be extracted by the BOUNDED Pass B workers
    val utilsContent = "object utils:\n  def getMsg() = \"bla\"\n"
    val mainContent = "object Main:\n  def main(args: Array[String]): Unit =\n    println(ext.getMsg())\n"
    os.write(srcDir / "utils.scala", utilsContent)
    os.write(srcDir / "Main.scala", mainContent)
    (1 to fallbackCount).foreach(i => os.write(srcDir / s"Fallback$i.scala", s"object Fallback$i:\n  val x = $i\n"))

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
    os.write(semDir / "META-INF" / "semanticdb" / "src" / "main" / "scala" / "utils.scala.semanticdb", TextDocuments(List(utilsDoc)).toByteArray)
    os.write(semDir / "META-INF" / "semanticdb" / "src" / "main" / "scala" / "Main.scala.semanticdb", TextDocuments(List(mainDoc)).toByteArray)
    semDir
  }

  test("Pass B fallback extraction is bounded to the configured worker count") {
    val root = freshRoot("ws-bounded")
    try {
      val semDir = buildPairingFixture(root, fallbackCount = 6)

      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)
      idx.fallbackWorkerCount = 2

      // hold broad Pass A until the direct-paired open file is verified
      val rootsPublished = new CountDownLatch(1)
      val releaseBroadInit = new CountDownLatch(1)
      idx.afterRootsPublishedHook = () => { rootsPublished.countDown(); releaseBroadInit.await() }

      // observe + block Pass B jobs: at most `fallbackWorkerCount` may run at once
      val maxObserved = new AtomicInteger(0)
      val active = new AtomicInteger(0)
      val jobsReachedCap = new CountDownLatch(1)
      val releaseJobs = new CountDownLatch(1)
      idx.fallbackJobHook = { _ =>
        val cur = active.incrementAndGet()
        var done = false
        while (!done) {
          val prev = maxObserved.get()
          done = maxObserved.compareAndSet(prev, math.max(prev, cur))
        }
        if (cur >= 2) jobsReachedCap.countDown()
        try releaseJobs.await()
        finally active.decrementAndGet()
      }

      val initThread = Thread.ofVirtual().start(() =>
        idx.initialize(List(SemanticdbDirs(root, semDir))))
      assert(rootsPublished.await(10, TimeUnit.SECONDS), "startup roots snapshot must be published")

      // the opened file must be navigable via its semanticdb BEFORE any fallback
      // job completes (direct pairing, not the post-Pass-B catch-up)
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      idx.onDidOpen(mainFile)
      assert(idx.findSymbolsAt(mainFile, 2, 18).contains("_empty_/utils.getMsg()."),
        s"direct-paired open file must be navigable while bulk init is blocked")

      releaseBroadInit.countDown() // let Pass A + Pass B run; jobs block at the cap
      assert(jobsReachedCap.await(10, TimeUnit.SECONDS), "fallback jobs must reach the worker cap")

      // still navigable while ALL fallback jobs are blocked
      assert(idx.findSymbolsAt(mainFile, 2, 18).contains("_empty_/utils.getMsg()."),
        s"open file must stay navigable while fallback jobs are blocked")

      releaseJobs.countDown()
      initThread.join(10_000)
      assert(!initThread.isAlive, "initialize must finish after jobs are released")
      assertEquals(maxObserved.get(), 2, "concurrent fallback jobs must never exceed the worker count")
    } finally os.remove.all(root)
  }

  test("phase events: direct pairing precedes bulk phases; Pass B reports worker count") {
    val root = freshRoot("ws-phase-events")
    try {
      val semDir = buildPairingFixture(root, fallbackCount = 3)

      val st = new InMemorySymbolTable
      val idx = new WorkspaceIndex(root, st)
      idx.fallbackWorkerCount = 2

      // hold broad Pass A so the direct pairing event is emitted before it
      val rootsPublished = new CountDownLatch(1)
      val releaseBroadInit = new CountDownLatch(1)
      idx.afterRootsPublishedHook = () => { rootsPublished.countDown(); releaseBroadInit.await() }

      val initThread = Thread.ofVirtual().start(() =>
        idx.initialize(List(SemanticdbDirs(root, semDir))))
      assert(rootsPublished.await(10, TimeUnit.SECONDS), "roots snapshot must be published")
      idx.onDidOpen(root / "src" / "main" / "scala" / "Main.scala")
      releaseBroadInit.countDown()
      initThread.join(10_000)
      assert(!initThread.isAlive, "initialize must complete")

      val events = idx.phaseEventLog
      val directIdx = events.indexWhere(_.startsWith("direct-pair:"))
      val passAIdx = events.indexOf("pass-a-done")
      val passBIdx = events.indexWhere(_.startsWith("pass-b-done:"))
      val catchUpIdx = events.indexOf("catch-up-done")
      val initIdx = events.indexOf("init-done")
      assert(directIdx >= 0, s"expected a direct-pair event, got: $events")
      assert(directIdx < passAIdx && directIdx < passBIdx && directIdx < initIdx,
        s"direct pairing must complete before bulk Pass A / Pass B / init, got: $events")
      assert(passBIdx < catchUpIdx && catchUpIdx < initIdx,
        s"phase order must be pass-b → catch-up → init, got: $events")
      assert(events.exists(e => e.startsWith("pass-b-done:files=3:workers=2:ok=3:failed=0")),
        s"Pass B event must report files/worker count/outcome, got: $events")
    } finally os.remove.all(root)
  }
}
