package ba.sake.basamake.lsp

import java.net.URI
import java.util.concurrent.TimeUnit
import munit.FunSuite
import org.eclipse.lsp4j.*

class BasamakeLanguageServerTest extends FunSuite {

  private def copyFixture(name: String, testName: String): os.Path = {
    val src = os.pwd / "test" / "resources" / "examples" / name
    require(os.isDir(src), s"Test fixture not found: $src")
    val dst = os.pwd / "tmp" / s"lsp-${sanitize(testName)}-${System.currentTimeMillis()}"
    os.makeDir.all(dst)
    os.copy(src, dst, mergeFolders = true)
    // no JDK eager indexing in tests — it would write a minutes-long index into
    // the REAL XDG cache; config-driven, same mechanism a user would use
    os.write.over(dst / ".basamake" / "config.json",
      """{"enableJdkIndexing": false}""", createFolders = true)
    dst
  }

  private def sanitize(name: String): String =
    name.replaceAll("[^a-zA-Z0-9_-]", "-").take(60)

  private def eventually(cond: => Boolean, timeoutMs: Long = 20000): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(50)
    cond
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
  // rename + watched files handling
  // ═══════════════════════════════════════════════════════════════

  test("didRenameFiles: publishes empty diagnostics for the old uri") {
    val root = copyFixture("sbt", "lsp-rename-handler")
    try {
      val client = new TestLanguageClient
      val server = new BasamakeLanguageServer(root)
      server.connect(client)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val oldUri = "file:///x/old.scala"
      val newUri = "file:///x/new.scala"
      server.didRenameFiles(new RenameFilesParams(
        java.util.List.of(new FileRename(oldUri, newUri))))

      val cleared = client.diagnosticsFor(oldUri)
      assert(cleared.isEmpty,
        s"expected empty publish for old uri, got: $cleared")
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
      server.connect(new TestLanguageClient)
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
      server.connect(new TestLanguageClient)
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
      server.connect(new TestLanguageClient)
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
      val client = new TestLanguageClient
      val server = new BasamakeLanguageServer(root)
      server.connect(client)

      val params = new InitializeParams()
      val caps = new ClientCapabilities()
      val win = new WindowClientCapabilities()
      win.setWorkDoneProgress(true)
      caps.setWindow(win)
      params.setCapabilities(caps)
      server.initialize(params).get(10, TimeUnit.SECONDS)

      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val tokens = client.progressNotifications.map(_.getToken.getLeft).toSet
      assert(tokens.contains("basamake-workspace"), s"workspace progress token must be created, got $tokens")

      val wsEvents = client.progressNotifications.toList
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

  // ═══════════════════════════════════════════════════════════════
  // .sbt build-definition navigation via LSP
  // ═══════════════════════════════════════════════════════════════

  test("LSP definition: goto core from build.sbt returns build.sbt location") {
    val root = copyFixture("sbtbuild", "lsp-sbt-goto")
    try {
      val server = new BasamakeLanguageServer(root)
      server.connect(new TestLanguageClient)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      val buildFile = root / "build.sbt"
      val buildText = os.read(buildFile)
      server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(buildFile.toNIO.toUri.toString, "scala", 1, buildText)))

      val (l, c) = posAt(buildText, """dependsOn\((?<p>core)\)""")
      val params = new DefinitionParams()
      params.setTextDocument(new TextDocumentIdentifier(buildFile.toNIO.toUri.toString))
      params.setPosition(new Position(l, c))

      val result = server.definition(params).get(10, TimeUnit.SECONDS)
      val locations = result.getLeft
      assert(locations.size() > 0, s"expected at least one location, got ${locations.size()}")
      val loc = locations.get(0)
      assertEquals(os.Path(URI.create(loc.getUri)).last, "build.sbt")
    } finally os.remove.all(root)
  }

  test("didChangeWatchedFiles: created .sbt file is indexed via the watcher gate") {
    val root = copyFixture("sbtbuild", "lsp-sbt-watched")
    try {
      val server = new BasamakeLanguageServer(root)
      server.connect(new TestLanguageClient)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      os.write.over(root / "New.sbt", "lazy val extra = project")

      val newFileUri = (root / "New.sbt").toNIO.toUri.toString
      server.didChangeWatchedFiles(new DidChangeWatchedFilesParams(
        java.util.List.of(new FileEvent(newFileUri, FileChangeType.Created))))

      val dump = os.read(root / ".basamake" / "index_sources.txt")
      assert(dump.contains("New.sbt"), s"expected New.sbt in index_sources.txt dump, got:\n$dump")
    } finally os.remove.all(root)
  }

  test("didChangeWatchedFiles: Changed events are forwarded (mixed batch still indexes created files)") {
    val root = copyFixture("sbtbuild", "lsp-sbt-watched-changed")
    try {
      val server = new BasamakeLanguageServer(root)
      server.connect(new TestLanguageClient)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      assert(eventually(server.isWorkspaceIndexingDone), "workspace index should finish")

      os.write.over(root / "New.sbt", "lazy val extra = project")
      val newFileUri = (root / "New.sbt").toNIO.toUri.toString
      val changedFileUri = (root / "build.sbt").toNIO.toUri.toString
      server.didChangeWatchedFiles(new DidChangeWatchedFilesParams(
        java.util.List.of(
          new FileEvent(newFileUri, FileChangeType.Created),
          new FileEvent(changedFileUri, FileChangeType.Changed))))

      val dump = os.read(root / ".basamake" / "index_sources.txt")
      assert(dump.contains("New.sbt"), s"expected New.sbt in index_sources.txt dump, got:\n$dump")
    } finally os.remove.all(root)
  }
}
