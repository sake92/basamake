package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, Range => SdbRange, SymbolOccurrence}
import ba.sake.basamake.navigation.InMemorySymbolTable

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
}
