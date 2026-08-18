package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.{Diagnostic, PublishDiagnosticsParams => LspPublishDiagnosticsParams, Range, Position}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

class BspManagerDiagnosticsTest extends FunSuite {

  /** Fresh manager + capturing client on a temp root. The caller's finally
    * must shutdown the manager and delete the root. */
  private def managerWith(client: CapturingLanguageClient, workDoneProgress: Boolean = false): (BspManager, os.Path) = {
    val root = os.temp.dir(prefix = "bsp-diag-test-")
    (BspManagerTestSupport.managerFor(root, client, workDoneProgress), root)
  }

  test("two targets emit diagnostics for same uri → union published") {
    val client = new CapturingLanguageClient
    val (mgr, root) = managerWith(client)
    try {
      val uri = "file:///x/A.scala"
      val targetA = new BuildTargetIdentifier("bsp://A")
      val targetB = new BuildTargetIdentifier("bsp://B")

      mgr.onDiagnostics(makeParams(uri, targetA, "err-A1", reset = true), BspConnectionId("cA"))
      mgr.onDiagnostics(makeParams(uri, targetB, "err-B1", reset = true), BspConnectionId("cB"))

      assert(client.published.size >= 2)
      val last = client.published.get(client.published.size - 1)
      assertEquals(last.getUri, uri)
      assertEquals(last.getDiagnostics.size(), 2)
      var msgs = scala.collection.mutable.Set.empty[String]
      last.getDiagnostics.forEach(d => msgs += d.getMessage.getLeft)
      assert(msgs.contains("err-A1"), s"msgs=$msgs should contain err-A1")
      assert(msgs.contains("err-B1"))
    } finally {
      mgr.shutdown()
      os.remove.all(root)
    }
  }

  test("reset=true clears only that target's slice") {
    val client = new CapturingLanguageClient
    val (mgr, root) = managerWith(client)
    try {
      val uri = "file:///x/A.scala"
      val targetA = new BuildTargetIdentifier("bsp://A")
      val targetB = new BuildTargetIdentifier("bsp://B")

      mgr.onDiagnostics(makeParams(uri, targetA, "err-A1", reset = true), BspConnectionId("cA"))
      mgr.onDiagnostics(makeParams(uri, targetB, "err-B1", reset = true), BspConnectionId("cB"))
      // Reset A with an empty diagnostic list → A's slice becomes empty, union is just B.
      val emptyParams = makeParams(uri, targetA, "<none>", reset = true)
      emptyParams.setDiagnostics(java.util.Collections.emptyList())
      mgr.onDiagnostics(emptyParams, BspConnectionId("cA"))

      val last = client.published.get(client.published.size - 1)
      assertEquals(last.getUri, uri)
      val msgs = scala.collection.mutable.Set.empty[String]
      last.getDiagnostics.forEach(d => msgs += d.getMessage.getLeft)
      assert(msgs == Set("err-B1"), s"msgs should be Set(err-B1), got $msgs")
    } finally {
      mgr.shutdown()
      os.remove.all(root)
    }
  }

  test("clearDiagnostics publishes empty even when no entry existed") {
    val client = new CapturingLanguageClient
    val (mgr, root) = managerWith(client)
    try {
      val uri = "file:///x/A.scala"
      // No diagnostics were ever published for this uri — clearing must still
      // publish an empty list (VS Code keeps stale diagnostics otherwise).
      mgr.clearDiagnostics(uri)

      assert(client.published.size >= 1)
      val last = client.published.get(client.published.size - 1)
      assertEquals(last.getUri, uri)
      assertEquals(last.getDiagnostics.size(), 0)
    } finally {
      mgr.shutdown()
      os.remove.all(root)
    }
  }

  test("onWatchedFilesChanged: deleted file cleared, created file safe no-op") {
    val client = new CapturingLanguageClient
    val (mgr, root) = managerWith(client)
    try {
      val deletedUri = "file:///x/Deleted.scala"
      val createdUri = "file:///x/Created.scala"
      // Seed a diagnostic for the deleted file, then deliver watcher events.
      val target = new BuildTargetIdentifier("bsp://A")
      mgr.onDiagnostics(makeParams(deletedUri, target, "old-err", reset = true), BspConnectionId("cA"))
      client.published.clear()
      mgr.onWatchedFilesChanged(created = List(createdUri), deleted = List(deletedUri))

      // Deleted file's diagnostics were cleared; non-source files are ignored.
      val cleared = client.published.asScala.filter(_.getUri == deletedUri)
      assert(cleared.nonEmpty, s"expected empty publish for deleted file, got ${client.published.asScala.map(_.getUri)}")
      assertEquals(cleared.last.getDiagnostics.size(), 0)
    } finally {
      mgr.shutdown()
      os.remove.all(root)
    }
  }

  test("onWatchedFilesChanged: non-source files ignored") {
    val client = new CapturingLanguageClient
    val (mgr, root) = managerWith(client)
    try {
      mgr.onWatchedFilesChanged(created = List("file:///x/readme.txt"), deleted = Nil)
      // No source files involved → no diagnostics published at all.
      assertEquals(client.published.size(), 0)
    } finally {
      mgr.shutdown()
      os.remove.all(root)
    }
  }

  test("onWatchedFilesChanged: changed source files flow through the batch (deleted still clears)") {
    val client = new CapturingLanguageClient
    val (mgr, root) = managerWith(client)
    try {
      val deletedUri = "file:///x/Deleted.scala"
      val changedUri = "file:///x/Changed.scala"
      // Seed a diagnostic for the deleted file, then deliver a mixed batch
      // (created=empty, deleted + changed source events — e.g. git checkout
      // rewriting a file produces a Changed event).
      val target = new BuildTargetIdentifier("bsp://A")
      mgr.onDiagnostics(makeParams(deletedUri, target, "old-err", reset = true), BspConnectionId("cA"))
      client.published.clear()
      mgr.onWatchedFilesChanged(created = Nil, deleted = List(deletedUri), changed = List(changedUri))

      val cleared = client.published.asScala.filter(_.getUri == deletedUri)
      assert(cleared.nonEmpty, s"expected empty publish for deleted file, got ${client.published.asScala.map(_.getUri)}")
      assertEquals(cleared.last.getDiagnostics.size(), 0)
    } finally {
      mgr.shutdown()
      os.remove.all(root)
    }
  }

  test("detachConnection clears only the detached connection's diagnostics") {
    val root = os.temp.dir(prefix = "bsp-diag-detach-")
    try {
      val bspA = root / "projA" / ".bsp" / "sbt.json"
      val bspB = root / "projB" / ".bsp" / "mill.json"
      os.makeDir.all(bspA / os.up)
      os.makeDir.all(bspB / os.up)
      os.write.over(bspA,
        """{"name":"sbt","version":"1","bspVersion":"2.1.0","languages":["scala"],"argv":["true"]}""")
      os.write.over(bspB,
        """{"name":"mill","version":"1","bspVersion":"2.1.0","languages":["scala"],"argv":["true"]}""")

      val client = new CapturingLanguageClient
      val mgr = BspManagerTestSupport.managerFor(root, client)
      try {
        val idA = BspConnectionId(bspA.toString)
        val idB = BspConnectionId(bspB.toString)
        val target = new BuildTargetIdentifier("bsp://t")
        val uriA = "file:///x/projA/src/A.scala"
        val uriB = "file:///x/projB/src/B.scala"

        // connection A publishes for uriA, connection B publishes for uriB
        mgr.onDiagnostics(makeParams(uriA, target, "err-A", reset = true), idA)
        mgr.onDiagnostics(makeParams(uriB, target, "err-B", reset = true), idB)

        client.published.clear()
        mgr.detachConnection(idA)

        // A's uri got an explicit empty publish (VS Code must drop stale entries)
        val clearedA = client.published.asScala.filter(_.getUri == uriA)
        assert(clearedA.nonEmpty, s"expected empty publish for detached conn A's uri, got ${client.published.asScala.map(_.getUri)}")
        assertEquals(clearedA.last.getDiagnostics.size(), 0)
        // B's diagnostics must NOT be cleared — nothing published for uriB
        val publishedB = client.published.asScala.filter(_.getUri == uriB)
        assert(publishedB.isEmpty,
          s"detach of A must not touch connection B's diagnostics, got ${client.published.asScala.map(_.getUri)}")
      } finally mgr.shutdown()
    } finally os.remove.all(root)
  }

  test("task start/progress/finish → workDoneProgress spinner + logMessage fallback") {
    val client = new CapturingLanguageClient
    val (mgr, root) = managerWith(client, workDoneProgress = true)
    try {
      val connId = BspConnectionId("bsp://fake")

      val taskId = new ch.epfl.scala.bsp4j.TaskId("task-42")
      val start = new ch.epfl.scala.bsp4j.TaskStartParams(taskId)
      start.setMessage("Compiling fake project")
      mgr.onTaskStart(start, connId)
      val prog = new ch.epfl.scala.bsp4j.TaskProgressParams(taskId)
      prog.setProgress(50L)
      mgr.onTaskProgress(prog, connId)
      val finish = new ch.epfl.scala.bsp4j.TaskFinishParams(taskId, ch.epfl.scala.bsp4j.StatusCode.OK)
      finish.setMessage("Compiled")
      mgr.onTaskFinish(finish, connId)

      // spinner: begin → report → end on one token (the end proves no stuck token)
      val kinds = client.progressed.asScala.map { p =>
        val n = p.getValue.getLeft
        n.getKind.toString.toLowerCase
      }.toList
      assertEquals(kinds, List("begin", "report", "end"))
      assertEquals(client.progressed.get(0).getToken.getLeft, s"basamake-compile-${connId.value}-task-42")
      // logMessage fallback still fires (works even without the progress capability)
      val logTexts = client.logged.asScala.map(_.getMessage).toList
      assert(logTexts.exists(_.contains("Compiling fake project")), s"expected start log, got $logTexts")
      assert(logTexts.exists(_.contains("Compiled")), s"expected finish log, got $logTexts")
    } finally {
      mgr.shutdown()
      os.remove.all(root)
    }
  }

  private def makeParams(uri: String, target: BuildTargetIdentifier, msg: String,
                         reset: Boolean): PublishDiagnosticsParams = {
    val list = new java.util.ArrayList[ch.epfl.scala.bsp4j.Diagnostic]()
    if (msg != "<none>") {
      val r = new ch.epfl.scala.bsp4j.Range(
        new ch.epfl.scala.bsp4j.Position(0, 0), new ch.epfl.scala.bsp4j.Position(0, 1))
      list.add(new ch.epfl.scala.bsp4j.Diagnostic(r, msg))
    }
    new PublishDiagnosticsParams(new TextDocumentIdentifier(uri), target, list, reset)
  }
}
