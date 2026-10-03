package ba.sake.basamake.bsp

import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import ch.epfl.scala.bsp4j.*
import munit.FunSuite
import ba.sake.basamake.index.InMemorySymbolTable
import ba.sake.basamake.index.indexing.{SemanticdbDirs, WorkspaceIndex}
import ba.sake.tupson.{given, *}
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, SymbolOccurrence, Range => SdbRange}

class BspAutoCompileTest extends FunSuite {

  private def awaitCondition(clue: String)(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition && System.nanoTime() < deadline) Thread.sleep(20)
    assert(condition, clue)
  }

  test("disabled cold connection imports targets; live toggle discards queued builds and leaves other connections compiling") {
    val root = os.temp.dir(prefix = "bsp-auto-compile-")
    val compiled = new AtomicInteger()
    val spawned = new AtomicInteger()
    val tid = new BuildTargetIdentifier("//test")
    val sources = new SourcesResult(java.util.List.of(new SourcesItem(tid,
      java.util.List.of(new SourceItem(root.toNIO.toUri.toString, SourceItemKind.DIRECTORY, false)))))
    val options = new ScalacOptionsResult(java.util.List.of(new ScalacOptionsItem(tid,
      java.util.Collections.emptyList(), java.util.Collections.emptyList(), (root / "target").toNIO.toUri.toString)))
    def connection(name: String, enabled: Boolean) = new BspConnection(
      BspConnectionSpec(BspDiscoveryFile(name, List("true")), root / ".bsp" / s"$name.json", workspaceRoot = root, autoCompile = enabled),
      () => {
        spawned.incrementAndGet()
        val server = new MockBuildServer {
          override def buildTargetCompile(p: CompileParams) = {
            compiled.incrementAndGet()
            CompletableFuture.completedFuture(new CompileResult(StatusCode.OK))
          }
        }
        val process = new FakeProcess {
          override def isAlive = true
          override def onExit() = new CompletableFuture[java.lang.Process]()
        }
        HandshakeResult(process, server, sources, new DependencySourcesResult(java.util.Collections.emptyList()), options)
      },
      _ => (),
      new BspEvents { def onDiagnostics(p: PublishDiagnosticsParams, id: BspConnectionId): Unit = () },
      debounceMs = 300
    )
    val disabled = connection("disabled", false)
    val enabled = connection("enabled", true)
    val uri = (root / "Main.scala").toNIO.toUri.toString
    try {
      disabled.requestCompile(uri)
      assertEquals(spawned.get(), 1, "disabled compilation must still connect")
      assertEquals(disabled.semanticdbRoots, List(SemanticdbDirs(root, root / "target")))
      val persisted = os.read(root / ".basamake" / "bsp" / BspConnectionSpec.dirName(disabled.spec) / "data.json").parseJson[BspTargetData]
      assertEquals(persisted.targets.map(_.id), List(tid.getUri))
      disabled.setAutoCompile(true)
      disabled.requestCompile(uri) // scheduled but not started
      disabled.setAutoCompile(false)
      Thread.sleep(600)
      assertEquals(compiled.get(), 0, "disabled requests and queued builds must not compile")
      enabled.requestCompile(uri)
      awaitCondition("other BSP connection keeps compiling")(compiled.get() == 1)
      disabled.setAutoCompile(true)
      disabled.requestCompile(uri)
      awaitCondition("re-enabled connection compiles on next request")(compiled.get() == 2)
      assertEquals(spawned.get(), 2, "toggle must not restart either connection")
    } finally {
      disabled.shutdown()
      enabled.shutdown()
      os.remove.all(root)
    }
  }

  test("existing os watcher follows registered ignored and external outputs, handles clean/rebuild, and filters unrelated files") {
    val root = os.temp.dir(prefix = "bsp-semanticdb-watch-")
    val outside = os.temp.dir(prefix = "bsp-semanticdb-external-")
    val source = root / "Main.scala"
    os.write(source, "object Main\n")
    val table = new InMemorySymbolTable
    val index = new WorkspaceIndex(root, table)
    index.initialize(Nil)
    index.onDidOpen(source)
    val filter = new WatchFilter(root, ba.sake.basamake.config.BasamakeConfig())
    val refreshes = new AtomicInteger()
    val configChanged = new CountDownLatch(1)
    val watcher = new BspWatcher(root, filter.isIgnored, () => (), _ => (),
      () => configChanged.countDown(), roots => { index.invalidate(roots); refreshes.incrementAndGet() })
    val semRoot = SemanticdbDirs(root, root / "target" / "classes")
    val externalRoot = SemanticdbDirs(root, outside / "output" / "classes")
    def writeOutput(dir: os.Path, symbol: String): Unit = {
      val doc = TextDocument(schema = Schema.SEMANTICDB4, uri = "Main.scala", text = "object Main\n",
        language = Language.SCALA, occurrences = List(SymbolOccurrence(symbol = symbol,
          range = Some(SdbRange(0, 7, 0, 11)), role = SymbolOccurrence.Role.DEFINITION)))
      os.write.over(dir / "META-INF" / "semanticdb" / "Main.scala.semanticdb",
        TextDocuments(List(doc)).toByteArray, createFolders = true)
    }
    try {
      os.makeDir.all(outside / "output")
      writeOutput(semRoot.semanticdbDir, "_empty_/BeforeRegistration.")
      watcher.start() // existing target/ is pruned before roots are registered
      watcher.setSemanticdbRoots(List(semRoot, externalRoot))
      awaitCondition("registration catches existing ignored output")(table.get("_empty_/BeforeRegistration.").isDefined)
      writeOutput(semRoot.semanticdbDir, "_empty_/FromCompiler.")
      awaitCondition("new ignored output is indexed")(table.get("_empty_/FromCompiler.").isDefined)
      writeOutput(semRoot.semanticdbDir, "_empty_/UpdatedCompilerSymbol.")
      awaitCondition("rewritten output replaces old definitions") {
        table.get("_empty_/UpdatedCompilerSymbol.").isDefined && table.get("_empty_/FromCompiler.").isEmpty
      }
      os.remove.all(root / "target") // directory deletion does not emit every child path
      awaitCondition("clean restores source definitions") {
        table.get("_empty_/UpdatedCompilerSymbol.").isEmpty && table.get("_empty_/Main.").isDefined
      }
      writeOutput(semRoot.semanticdbDir, "_empty_/AfterClean.")
      awaitCondition("recreated output is watched")(table.get("_empty_/AfterClean.").isDefined)
      os.remove.all(root / "target")
      awaitCondition("second clean completed")(table.get("_empty_/AfterClean.").isEmpty)
      writeOutput(externalRoot.semanticdbDir, "_empty_/ExternalCompiler.")
      awaitCondition("external output created after registration is watched")(table.get("_empty_/ExternalCompiler.").isDefined)
      os.remove.all(outside / "output")
      awaitCondition("external clean restores source parsing")(table.get("_empty_/ExternalCompiler.").isEmpty)
      writeOutput(externalRoot.semanticdbDir, "_empty_/ExternalAfterClean.")
      awaitCondition("rebuilding a deleted external watch ancestor is detected")(table.get("_empty_/ExternalAfterClean.").isDefined)
      os.remove(externalRoot.semanticdbDir / "META-INF" / "semanticdb" / "Main.scala.semanticdb")
      awaitCondition("single-file deletion falls back too")(table.get("_empty_/ExternalAfterClean.").isEmpty)
      Thread.sleep(700) // finish any directory events from the last refresh
      os.makeDir.all(root / "target" / "unrelated")
      Thread.sleep(1000) // registering a new ancestor may legitimately refresh roots
      val before = refreshes.get()
      os.write(root / "target" / "unrelated" / "Unrelated.scala.semanticdb", Array[Byte](1), createFolders = true)
      os.write(externalRoot.semanticdbDir / "Main.class", Array[Byte](1))
      os.write(root / ".basamake" / "config.json", "{}", createFolders = true)
      assert(configChanged.await(10, TimeUnit.SECONDS), "config watching remains active after root changes")
      Thread.sleep(700)
      assertEquals(refreshes.get(), before, "unregistered SemanticDB and class-file writes do not refresh")
      watcher.setSemanticdbRoots(Nil)
      Thread.sleep(700)
      val detached = refreshes.get()
      writeOutput(externalRoot.semanticdbDir, "_empty_/Detached.")
      Thread.sleep(700)
      assertEquals(refreshes.get(), detached, "unregistered roots stop refreshing")
    } finally {
      watcher.stop()
      watcher.stop()
      os.remove.all(root)
      os.remove.all(outside)
    }
  }
}
