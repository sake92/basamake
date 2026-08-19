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

  private def uriOf(relPath: String): String = (projectRoot / relPath).toNIO.toUri.toString

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
      require(caps.getDefinitionProvider != null && caps.getDefinitionProvider.getLeft.booleanValue(), "definition capability expected")
    }
    proxy.initialized(new InitializedParams())
  }

  /** Open a workspace file (path relative to the project root). */
  def open(relPath: String): Unit = {
    val p = projectRoot / relPath
    proxy.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(
      new TextDocumentItem(p.toNIO.toUri.toString, "scala", 1, os.read(p))))
  }

  /** Write new content to disk AND push it through the LSP (Full sync). */
  def replaceAndSave(relPath: String, newContent: String): Unit = {
    val p = projectRoot / relPath
    os.write.over(p, newContent)
    proxy.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
      new VersionedTextDocumentIdentifier(p.toNIO.toUri.toString, 2),
      java.util.List.of(new TextDocumentContentChangeEvent(newContent))))
    proxy.getTextDocumentService.didSave(new DidSaveTextDocumentParams(
      new TextDocumentIdentifier(p.toNIO.toUri.toString)))
  }

  /** Wait (deadline-bounded, event-driven) until diagnostics for `relPath`
    * satisfy `predicate`. Returns the latest diagnostic list. */
  def awaitDiagnostics(relPath: String, predicate: List[Diagnostic] => Boolean, timeoutSec: Long): List[Diagnostic] =
    client.awaitDiagnostics(uriOf(relPath), predicate, timeoutSec)

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

  def close(): Unit = {
    proxy.shutdown().get(10, TimeUnit.SECONDS)
    clientWrite.close() // stdin EOF -> server launcher future completes
    serverWrite.close()
    serverListening.get(10, TimeUnit.SECONDS)
    server.cleanup() // idempotent — no-op after shutdown, safe on failure
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
