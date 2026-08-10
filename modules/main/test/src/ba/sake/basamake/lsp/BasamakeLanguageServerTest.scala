package ba.sake.basamake.lsp

import java.net.URI
import java.util.concurrent.{CompletableFuture, TimeUnit}
import munit.FunSuite
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import scala.jdk.CollectionConverters.*

class BasamakeLanguageServerTest extends FunSuite {

  private def copyFixture(name: String, testName: String): os.Path = {
    val src = os.pwd / "modules" / "main" / "test" / "resources" / "examples" / name
    require(os.isDir(src), s"Test fixture not found: $src")
    val dst = os.pwd / "tmp" / s"lsp-${sanitize(testName)}-${System.currentTimeMillis()}"
    os.makeDir.all(dst)
    os.copy(src, dst, mergeFolders = true)
    dst
  }

  private def sanitize(name: String): String =
    name.replaceAll("[^a-zA-Z0-9_-]", "-").take(60)

  /** LanguageClient fake — accepts all methods as no-ops. */
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

  /** LanguageClient fake that captures publishDiagnostics calls. */
  private def capturingClient(captured: java.util.List[PublishDiagnosticsParams]): LanguageClient =
    new LanguageClient {
      override def publishDiagnostics(p: PublishDiagnosticsParams): Unit = captured.add(p)
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

  /** Returns (0-indexed line, 0-indexed startCharacter) for the first match of
    * `regex` in `content`. If a named group `p` exists, its start is used. */
  private def posAt(content: String, regex: String): (Int, Int) = {
    val m = java.util.regex.Pattern.compile(regex).matcher(content)
    require(m.find(), s"posAt: regex not found: /$regex/")
    val start =
      try m.start("p")
      catch { case _: IllegalArgumentException => m.start() }
    val before = content.substring(0, start)
    val line = before.count(_ == '\n')
    val lastNl = before.lastIndexOf('\n')
    val char = if lastNl < 0 then before.length else before.length - lastNl - 1
    (line, char)
  }

  private def eventually(cond: => Boolean, timeoutMs: Long = 20000): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(50)
    cond
  }

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
    (p.getToken.getLeft, n.getKind.toString.toLowerCase, msg)
  }

  // ═══════════════════════════════════════════════════════════════
  // initialize capabilities: rename handling
  // ═══════════════════════════════════════════════════════════════

  test("initialize: advertises didRename file operations with filters") {
    val root = copyFixture("sbt", "lsp-rename-caps")
    try {
      val server = new BasamakeLanguageServer(root)
      server.connect(fakeClient)
      val result = server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")
      val didRename = result.getCapabilities.getWorkspace.getFileOperations.getDidRename
      assert(didRename != null, "server must advertise didRename")
      assert(didRename.getFilters != null && !didRename.getFilters.isEmpty,
        "didRename must declare filters — vscode-languageclient ignores filter-less registrations")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // rename + watched files handling
  // ═══════════════════════════════════════════════════════════════

  test("didRenameFiles: publishes empty diagnostics for the old uri") {
    val root = copyFixture("sbt", "lsp-rename-handler")
    try {
      val captured = new java.util.concurrent.CopyOnWriteArrayList[PublishDiagnosticsParams]()
      val server = new BasamakeLanguageServer(root)
      server.connect(capturingClient(captured))
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val oldUri = "file:///x/old.scala"
      val newUri = "file:///x/new.scala"
      server.didRenameFiles(new RenameFilesParams(
        java.util.List.of(new FileRename(oldUri, newUri))))

      val cleared = captured.asScala.filter(_.getUri == oldUri)
      assert(cleared.nonEmpty,
        s"expected empty publish for old uri, got ${captured.asScala.map(_.getUri)}")
      assertEquals(cleared.last.getDiagnostics.size(), 0)
    } finally os.remove.all(root)
  }

  test("didChangeWatchedFiles: deleted file → empty diagnostics published") {
    val root = copyFixture("sbt", "lsp-watched")
    try {
      val captured = new java.util.concurrent.CopyOnWriteArrayList[PublishDiagnosticsParams]()
      val server = new BasamakeLanguageServer(root)
      server.connect(capturingClient(captured))
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val deletedUri = "file:///x/Deleted.scala"
      val createdUri = "file:///x/Created.scala"
      server.didChangeWatchedFiles(new DidChangeWatchedFilesParams(
        java.util.List.of(
          new FileEvent(createdUri, FileChangeType.Created),
          new FileEvent(deletedUri, FileChangeType.Deleted))))

      val cleared = captured.asScala.filter(_.getUri == deletedUri)
      assert(cleared.nonEmpty,
        s"expected empty publish for deleted file, got ${captured.asScala.map(_.getUri)}")
      assertEquals(cleared.last.getDiagnostics.size(), 0)
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // goto-definition via LSP handler
  // ═══════════════════════════════════════════════════════════════

  test("LSP definition: goto getMsg from Main.scala returns utils.scala location") {
    val root = copyFixture("sbt", "lsp-goto")
    try {
      os.remove.all(root / "target") // force source-only
      val server = new BasamakeLanguageServer(root)
      server.connect(fakeClient)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val utilsFile = root / "src" / "main" / "scala" / "utils.scala"
      val mainText = os.read(mainFile)

      // Open Main.scala
      server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(mainFile.toNIO.toUri.toString, "scala", 1, mainText)))

      // Open utils.scala (so cross-file resolution works)
      server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(utilsFile.toNIO.toUri.toString, "scala", 1, os.read(utilsFile))))

      val (l, c) = posAt(mainText, """utils\.(?<p>getMsg)\(\)""")
      val params = new DefinitionParams()
      params.setTextDocument(new TextDocumentIdentifier(mainFile.toNIO.toUri.toString))
      params.setPosition(new Position(l, c))

      val result = server.definition(params).get(10, TimeUnit.SECONDS)
      val locations = result.getLeft
      assert(locations.size() > 0, s"expected at least one location, got ${locations.size()}")
      val loc = locations.get(0)
      val locPath = os.Path(URI.create(loc.getUri))
      assertEquals(locPath.last, "utils.scala")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // references via LSP handler
  // ═══════════════════════════════════════════════════════════════

  test("LSP references: refs of getMsg finds usage across open files") {
    val root = copyFixture("sbt", "lsp-refs")
    try {
      os.remove.all(root / "target") // force source-only
      val server = new BasamakeLanguageServer(root)
      server.connect(fakeClient)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val utilsFile = root / "src" / "main" / "scala" / "utils.scala"
      val mainText = os.read(mainFile)
      val utilsText = os.read(utilsFile)

      // Open both files
      server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(mainFile.toNIO.toUri.toString, "scala", 1, mainText)))
      server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(utilsFile.toNIO.toUri.toString, "scala", 1, utilsText)))

      // Cursor on def site of getMsg in utils.scala
      val (l, c) = posAt(utilsText, """def (?<p>getMsg)""")
      val params = new ReferenceParams()
      params.setTextDocument(new TextDocumentIdentifier(utilsFile.toNIO.toUri.toString))
      params.setPosition(new Position(l, c))
      params.setContext(new ReferenceContext(true)) // includeDeclaration = true

      val locations = server.references(params).get(10, TimeUnit.SECONDS)
      assert(locations.size() >= 1, s"expected at least one reference, got ${locations.size()}")
      val uris = locations.asScala.map(_.getUri).toList
      assert(uris.exists(_.endsWith("Main.scala")),
        s"expected reference in Main.scala, got: $uris")
      assert(uris.exists(_.endsWith("utils.scala")),
        s"expected declaration in utils.scala, got: $uris")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // references from the call site (cursor on the usage, not def)
  // ═══════════════════════════════════════════════════════════════

  test("LSP references: refs of getMsg from call site finds usage + declaration") {
    val root = copyFixture("sbt", "lsp-refs-call")
    try {
      os.remove.all(root / "target")
      val server = new BasamakeLanguageServer(root)
      server.connect(fakeClient)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val utilsFile = root / "src" / "main" / "scala" / "utils.scala"
      val mainText = os.read(mainFile)

      // Open both files
      server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(mainFile.toNIO.toUri.toString, "scala", 1, mainText)))
      server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(utilsFile.toNIO.toUri.toString, "scala", 1, os.read(utilsFile))))

      // Cursor on the call site: utils.getMsg() in Main.scala
      val (l, c) = posAt(mainText, """utils\.(?<p>getMsg)\(\)""")
      val params = new ReferenceParams()
      params.setTextDocument(new TextDocumentIdentifier(mainFile.toNIO.toUri.toString))
      params.setPosition(new Position(l, c))
      params.setContext(new ReferenceContext(true))

      val locations = server.references(params).get(10, TimeUnit.SECONDS)
      assert(locations.size() >= 1, s"expected at least one reference from call site, got ${locations.size()}")
      val uris = locations.asScala.map(_.getUri).toList
      assert(uris.exists(_.endsWith("Main.scala")),
        s"expected call-site ref in Main.scala, got: $uris")
      assert(uris.exists(_.endsWith("utils.scala")),
        s"expected declaration in utils.scala, got: $uris")
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // hover via LSP handler
  // ═══════════════════════════════════════════════════════════════

  test("LSP hover: over getMsg call returns signature + doc + location") {
    val root = copyFixture("hover", "lsp-hover")
    try {
      os.remove.all(root / "target") // force source-only
      val server = new BasamakeLanguageServer(root)
      server.connect(fakeClient)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val mainText = os.read(mainFile)
      server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(mainFile.toNIO.toUri.toString, "scala", 1, mainText)))

      val (l, c) = posAt(mainText, """utils\.(?<p>getMsg)\(\)""")
      val params = new HoverParams(new TextDocumentIdentifier(mainFile.toNIO.toUri.toString), new Position(l, c))
      val hover = server.hover(params).get(10, TimeUnit.SECONDS)

      assert(hover != null, "expected hover for getMsg")
      val md = hover.getContents.getRight.getValue
      assert(md.contains("**def getMsg(): String**"), s"hover missing signature: $md")
      assert(md.contains("Returns a greeting message."), s"hover missing doc: $md")
      assert(md.contains("utils.scala:3"), s"hover missing location footer: $md")
    } finally os.remove.all(root)
  }

  test("LSP hover: over unresolved position returns null") {
    val root = copyFixture("hover", "lsp-hover-none")
    try {
      os.remove.all(root / "target")
      val server = new BasamakeLanguageServer(root)
      server.connect(fakeClient)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val mainText = os.read(mainFile)
      server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(mainFile.toNIO.toUri.toString, "scala", 1, mainText)))

      // blank line — nothing under the cursor
      val params = new HoverParams(new TextDocumentIdentifier(mainFile.toNIO.toUri.toString), new Position(2, 0))
      val hover = server.hover(params).get(10, TimeUnit.SECONDS)
      assertEquals(hover, null)
    } finally os.remove.all(root)
  }

  // ═══════════════════════════════════════════════════════════════
  // Stale semanticdb: source edited after compile, goto still works
  // ═══════════════════════════════════════════════════════════════

  test("LSP stale semanticdb: goto getMsg works despite newer source file") {
    val root = copyFixture("sbt", "lsp-stale-sem")
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val original = os.read(mainFile)
      // Edit the source (index here is source-only — no semanticdb roots are passed)
      os.write.over(mainFile, "// edited after compile\n" + original)

      val server = new BasamakeLanguageServer(root)
      server.connect(fakeClient)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val mainText = os.read(mainFile)
      server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(mainFile.toNIO.toUri.toString, "scala", 1, mainText)))

      val (l, c) = posAt(mainText, """utils\.(?<p>getMsg)\(\)""")
      val params = new DefinitionParams()
      params.setTextDocument(new TextDocumentIdentifier(mainFile.toNIO.toUri.toString))
      params.setPosition(new Position(l, c))

      val result = server.definition(params).get(10, TimeUnit.SECONDS)
      val locations = result.getLeft
      assert(locations.size() > 0,
        s"stale semanticdb should fall back to source parsing, got ${locations.size()}")
      assertEquals(os.Path(URI.create(locations.get(0).getUri)).last, "utils.scala")
    } finally os.remove.all(root)
  }

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
}
