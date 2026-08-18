package ba.sake.basamake.lsp

import java.net.URI
import java.nio.channels.Channels
import java.util.concurrent.{CompletableFuture, TimeUnit}
import munit.FunSuite
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient

/** End-to-end JSON-RPC transport test: drives the real BasamakeLanguageServer
  * through the same LSPLauncher wiring Main.run() uses, against a fixture copy
  * under ./tmp/. Replaces the deleted python smoke tests.
  *
  * `exit()` is deliberately NOT sent — the server calls System.exit(0) there,
  * which would kill the test JVM. shutdown() + stdin EOF is the clean path.
  */
class LspTransportTest extends FunSuite {

  private def copyFixture(name: String, testName: String): os.Path = {
    val src = os.pwd / "test" / "resources" / "examples" / name
    require(os.isDir(src), s"Test fixture not found: $src")
    val dst = os.pwd / "tmp" / s"${testName}-${System.currentTimeMillis()}"
    os.makeDir.all(dst)
    os.copy(src, dst, mergeFolders = true)
    dst
  }

  private def fakeClient: LanguageClient = new LanguageClient {
    override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = ()
    override def telemetryEvent(x: Any): Unit = ()
    override def showMessage(p: MessageParams): Unit = ()
    override def showMessageRequest(p: ShowMessageRequestParams) =
      CompletableFuture.completedFuture(null.asInstanceOf[MessageActionItem])
    override def logMessage(p: MessageParams): Unit = ()
    override def createProgress(p: WorkDoneProgressCreateParams) =
      CompletableFuture.completedFuture(null.asInstanceOf[Void])
    override def applyEdit(p: ApplyWorkspaceEditParams) =
      CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))
  }

  /** (0-indexed line, 0-indexed startCharacter) of the first match; honors a named group `p`. */
  private def posAt(content: String, regex: String): (Int, Int) = {
    val m = java.util.regex.Pattern.compile(regex).matcher(content)
    require(m.find(), s"posAt: regex not found: /$regex/")
    val start = try m.start("p") catch { case _: IllegalArgumentException => m.start() }
    val before = content.substring(0, start)
    val line = before.count(_ == '\n')
    val lastNl = before.lastIndexOf('\n')
    val char = if lastNl < 0 then before.length else before.length - lastNl - 1
    (line, char)
  }

  test("JSON-RPC transport: initialize → didOpen → definition → shutdown") {
    val root = copyFixture("nopackages", "lsp-transport")
    try {
      val mainFile = root / "Main.scala"
      val sibFile = root / "Siblings.scala"
      val mainText = os.read(mainFile)

      // Real OS pipes (NIO channels), not PipedInputStream/PipedOutputStream:
      // the in-memory pipes track the FIRST writer thread and throw "Write end
      // dead" when it terminates — and LSP responses are written from virtual
      // threads (completing and dying right after the write), which breaks the
      // old transport. OS pipes have no thread tracking — same semantics as
      // Main's real stdin/stdout wiring.
      // pipes: clientToServer carries requests, serverToClient carries responses
      val clientToServer = java.nio.channels.Pipe.open()
      val serverToClient = java.nio.channels.Pipe.open()
      val serverRead = Channels.newInputStream(clientToServer.source())
      val clientWrite = Channels.newOutputStream(clientToServer.sink())
      val clientRead = Channels.newInputStream(serverToClient.source())
      val serverWrite = Channels.newOutputStream(serverToClient.sink())

      val server = new BasamakeLanguageServer(root)
      val serverLauncher = LSPLauncher.createServerLauncher(server, serverRead, serverWrite)
      server.connect(serverLauncher.getRemoteProxy)

      val clientLauncher = LSPLauncher.createClientLauncher(fakeClient, clientRead, clientWrite)
      val serverProxy = clientLauncher.getRemoteProxy

      val serverListening = serverLauncher.startListening()
      clientLauncher.startListening()

      try {
        val initResult = serverProxy.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
        val caps = initResult.getCapabilities
        assertEquals(caps.getTextDocumentSync.getLeft, TextDocumentSyncKind.Full)
        assertEquals(caps.getDefinitionProvider.getLeft.booleanValue(), true)
        assertEquals(caps.getReferencesProvider.getLeft.booleanValue(), true)
        assertEquals(caps.getHoverProvider.getLeft.booleanValue(), true)
        val didRename = caps.getWorkspace.getFileOperations.getDidRename
        assert(didRename != null && !didRename.getFilters.isEmpty, "didRename filters must be advertised")
        serverProxy.initialized(new InitializedParams())

        // workspace indexing now runs on a background thread (launched by
        // initialize) — the definition query below needs the symbol table ready
        val deadline = System.currentTimeMillis() + 20000
        while (!server.isWorkspaceIndexingDone && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assert(server.isWorkspaceIndexingDone, "workspace index should finish")

        serverProxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
          new TextDocumentItem(mainFile.toNIO.toUri.toString, "scala", 1, mainText)))
        serverProxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
          new TextDocumentItem(sibFile.toNIO.toUri.toString, "scala", 1, os.read(sibFile))))

        val (l, c) = posAt(mainText, """(?<p>add)\(2, 3\)""")
        val params = new DefinitionParams()
        params.setTextDocument(new TextDocumentIdentifier(mainFile.toNIO.toUri.toString))
        params.setPosition(new Position(l, c))
        val locs = serverProxy.getTextDocumentService().definition(params).get(10, TimeUnit.SECONDS).getLeft
        assert(locs.size() > 0, "expected definition location over transport")
        val loc = locs.get(0)
        assertEquals(os.Path(URI.create(loc.getUri)).last, "Siblings.scala")
        // position mapping survives the JSON-RPC round-trip: def site of add()
        val (dl, dc) = posAt(os.read(sibFile), """def (?<p>add)\(a""")
        assertEquals(loc.getRange.getStart.getLine, dl)
        assertEquals(loc.getRange.getStart.getCharacter, dc)

        serverProxy.shutdown().get(10, TimeUnit.SECONDS)
      } finally {
        clientWrite.close() // stdin EOF -> server launcher future completes
        serverWrite.close()
        serverRead.close()
        clientRead.close()
        server.cleanup() // idempotent — no-op after shutdown, safe on failure
      }
      serverListening.get(10, TimeUnit.SECONDS)
    } finally os.remove.all(root)
  }
}
