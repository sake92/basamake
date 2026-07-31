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
  // Stale semanticdb: source edited after compile, goto still works
  // ═══════════════════════════════════════════════════════════════

  test("LSP stale semanticdb: goto getMsg works despite newer source file") {
    val root = copyFixture("sbt", "lsp-stale-sem")
    try {
      val mainFile = root / "src" / "main" / "scala" / "Main.scala"
      val original = os.read(mainFile)
      // Edit source AFTER semanticdb was generated (mtime will be newer)
      os.write.over(mainFile, "// edited after compile\n" + original)
      Thread.sleep(10) // ensure mtime difference

      val server = new BasamakeLanguageServer(root)
      server.connect(fakeClient)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)

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
}
