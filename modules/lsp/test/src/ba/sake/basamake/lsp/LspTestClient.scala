package ba.sake.basamake.lsp

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer

/** Drives a real BasamakeLanguageServer through the REAL JSON-RPC transport
  * (OS pipes + LSPLauncher — the same wiring Main.run uses). Hides all protocol
  * plumbing: tests read like user workflows. Never calls server methods directly.
  *
  * close() sends shutdown + stdin EOF — deliberately NOT exit() (System.exit
  * would kill the test JVM). */
final class LspTestClient private (
    val projectRoot: os.Path,
    server: BasamakeLanguageServer,
    proxy: LanguageServer,
    private val client: TestLanguageClient,
    private val serverListening: java.util.concurrent.Future[Void],
    private val serverWrite: java.io.OutputStream,
    private val clientWrite: java.io.OutputStream
) extends AutoCloseable {

  private def uriOf(relPath: String): String = (projectRoot / os.RelPath(relPath)).toNIO.toUri.toString

  // ---- timings: printed, never asserted ----
  private val timings = scala.collection.mutable.ListBuffer.empty[(String, Long)]
  private def measure[A](name: String)(block: => A): A = {
    val t0 = System.nanoTime()
    val res = block
    val ms = (System.nanoTime() - t0) / 1_000_000
    timings += ((name, ms))
    res
  }
  def printTimings(): Unit = {
    println("  -- Basamake E2E timings --")
    timings.foreach { case (n, ms) => println(f"  $n%-24s $ms%5d ms") }
  }

  def initialize(): Unit = {
    measure("initialize") {
      val caps = proxy.initialize(new InitializeParams()).get(30, TimeUnit.SECONDS).getCapabilities
      // Full capability contract — subsumes the deleted LspTransportTest assertions.
      require(caps.getTextDocumentSync.getLeft == TextDocumentSyncKind.Full, "Full sync expected")
      require(caps.getDefinitionProvider != null && caps.getDefinitionProvider.getLeft.booleanValue(), "definition capability expected")
      require(caps.getReferencesProvider != null && caps.getReferencesProvider.getLeft.booleanValue(), "references capability expected")
      require(caps.getHoverProvider != null && caps.getHoverProvider.getLeft.booleanValue(), "hover capability expected")
      val didRename = caps.getWorkspace.getFileOperations.getDidRename
      require(didRename != null && didRename.getFilters != null && !didRename.getFilters.isEmpty,
        "didRename filters must be advertised (vscode-languageclient ignores filter-less registrations)")
    }
    proxy.initialized(new InitializedParams())
  }

  /** Open a workspace file (path relative to the project root). */
  def open(relPath: String): Unit = {
    val p = projectRoot / os.RelPath(relPath)
    proxy.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(
      new TextDocumentItem(p.toNIO.toUri.toString, "scala", 1, os.read(p))))
  }

  /** Write new content to disk AND push it through the LSP (Full sync). */
  def replaceAndSave(relPath: String, newContent: String): Unit = {
    val p = projectRoot / os.RelPath(relPath)
    os.write.over(p, newContent)
    proxy.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(p.toNIO.toUri.toString, 2),
      java.util.List.of(new TextDocumentContentChangeEvent(newContent))))
    proxy.getTextDocumentService.didSave(new DidSaveTextDocumentParams(
      new TextDocumentIdentifier(p.toNIO.toUri.toString)))
  }

  /** Wait (deadline-bounded, event-driven) until diagnostics for `relPath`
    * satisfy `predicate` AND at least `minPublishCount` publish batches were
    * received. Returns the latest diagnostic list. */
  def awaitDiagnostics(relPath: String, predicate: List[Diagnostic] => Boolean, timeoutSec: Long,
                       minPublishCount: Int = 0): List[Diagnostic] =
    client.awaitDiagnostics(uriOf(relPath), predicate, timeoutSec, minPublishCount)

  /** Wait until a successful compile completed (Info log "Compiled …" from the
    * BSP taskFinish). Use before further edits that must trigger a FRESH
    * compile — scala-cli's BSP retry-after-failure can otherwise coalesce the
    * next edit into the still-running retry and never recompile it. */
  def awaitCompileSucceeded(timeoutSec: Long = 120): Unit =
    client.awaitCompileSucceeded(timeoutSec)

  /** Go-to-definition at (line, character) — both 0-based. */
  def goToDefinition(relPath: String, line: Int, char: Int): List[Location] =
    measure("goto definition") {
      val params = new DefinitionParams()
      params.setTextDocument(new TextDocumentIdentifier(uriOf(relPath)))
      params.setPosition(new Position(line, char))
      val either = proxy.getTextDocumentService.definition(params).get(60, TimeUnit.SECONDS)
      // capture-convert the wildcard List[? <: Location] at a val boundary
      val locs: List[Location] = if (either.isLeft) either.getLeft.asScala.toList else Nil
      locs
    }

  /** Find references at (line, character) — both 0-based. */
  def findReferences(relPath: String, line: Int, char: Int, includeDeclaration: Boolean): List[Location] =
    measure("references") {
      val params = new ReferenceParams()
      params.setTextDocument(new TextDocumentIdentifier(uriOf(relPath)))
      params.setPosition(new Position(line, char))
      val ctx = new ReferenceContext()
      ctx.setIncludeDeclaration(includeDeclaration)
      params.setContext(ctx)
      proxy.getTextDocumentService.references(params).get(60, TimeUnit.SECONDS).asScala.toList
    }

  /** Hover at (line, character) — both 0-based. Returns None when the server returns null. */
  def hover(relPath: String, line: Int, char: Int): Option[Hover] =
    measure("hover") {
      val params = new HoverParams()
      params.setTextDocument(new TextDocumentIdentifier(uriOf(relPath)))
      params.setPosition(new Position(line, char))
      Option(proxy.getTextDocumentService.hover(params).get(60, TimeUnit.SECONDS))
    }

  /** Write a file directly on disk — NOT through the LSP. Simulates external
    * tooling (terminal vim / git checkout / echo >> file); the file watcher
    * must pick it up. */
  def writeOnDisk(relPath: String, content: String): Unit =
    os.write.over(projectRoot / os.RelPath(relPath), content)

  /** Delete a file directly on disk (external tooling again). */
  def deleteOnDisk(relPath: String): Unit =
    os.remove(projectRoot / os.RelPath(relPath))

  /** Poll `f` every `pollMs` until `pred` holds or `timeoutSec` elapses.
    * For eventually-consistent paths (watcher → index update). */
  def awaitUntil[A](timeoutSec: Long, pollMs: Long = 2000)(f: => A)(pred: A => Boolean): A = {
    val deadline = System.currentTimeMillis() + timeoutSec * 1000
    var last: A = f
    while (!pred(last) && System.currentTimeMillis() < deadline) {
      Thread.sleep(pollMs)
      last = f
    }
    if (!pred(last)) throw new AssertionError(s"awaitUntil: condition not met within ${timeoutSec}s")
    last
  }

  def close(): Unit = {
    proxy.shutdown().get(10, TimeUnit.SECONDS)
    clientWrite.close() // stdin EOF -> server launcher future completes
    serverWrite.close()
    serverListening.get(10, TimeUnit.SECONDS)
    server.cleanup() // idempotent — no-op after shutdown, safe on failure
    assertNoScalaCliDescendants()
  }

  /** BspManager.shutdown kills the whole process tree of this JVM — after
    * close(), no scala-cli BSP descendant may remain. Poll briefly: destroy +
    * OS reaping is asynchronous. Subsumes BspManagerShutdownTest's
    * "no lingering descendant processes" test. */
  private def assertNoScalaCliDescendants(): Unit = {
    def scalaCliDescendants(): Int =
      java.lang.ProcessHandle.current().descendants().iterator().asScala.count { p =>
        p.info().commandLine().orElse("").contains("scala-cli")
      }
    val deadline = System.currentTimeMillis() + 10_000
    var remaining = scalaCliDescendants()
    while (remaining > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(200)
      remaining = scalaCliDescendants()
    }
    require(remaining == 0, s"$remaining scala-cli descendant process(es) survived shutdown")
  }
}

object LspTestClient {
  /** Start a BasamakeLanguageServer on `root` and return a live client. */
  def start(root: os.Path): LspTestClient = {
    val client = new TestLanguageClient

    // Real OS pipes (NIO), not PipedInput/OutputStreams: the in-memory pipes
    // track the FIRST writer thread and break when LSP responses are written
    // from virtual threads (see LspTransportTest comment). OS pipes have no
    // thread tracking — same semantics as Main's real stdin/stdout wiring.
    val clientToServer = java.nio.channels.Pipe.open()
    val serverToClient = java.nio.channels.Pipe.open()
    val serverRead = java.nio.channels.Channels.newInputStream(clientToServer.source())
    val clientWrite = java.nio.channels.Channels.newOutputStream(clientToServer.sink())
    val clientRead = java.nio.channels.Channels.newInputStream(serverToClient.source())
    val serverWrite = java.nio.channels.Channels.newOutputStream(serverToClient.sink())

    val server = new BasamakeLanguageServer(root)
    val serverLauncher = LSPLauncher.createServerLauncher(server, serverRead, serverWrite)
    server.connect(serverLauncher.getRemoteProxy)

    val clientLauncher = LSPLauncher.createClientLauncher(client, clientRead, clientWrite)
    val proxy = clientLauncher.getRemoteProxy

    val serverListening = serverLauncher.startListening()
    clientLauncher.startListening()

    new LspTestClient(root, server, proxy, client, serverListening, serverWrite, clientWrite)
  }
}
