package ba.sake.basamake.lsp

import java.net.URI
import java.util.concurrent.CompletableFuture
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*

import ba.sake.basamake.navigation.{SymbolDefinition, SymbolTable}
import ba.sake.basamake.navigation.indexing.WorkspaceIndex
import ba.sake.basamake.bsp.{BspManager, BspTargetData}
import ba.sake.tupson.{given, *}

class BasamakeLanguageServer(workspacePath: os.Path) extends LanguageClientAware, LanguageServer, TextDocumentService, WorkspaceService, StrictLogging {

  @volatile private var client: LanguageClient = uninitialized

  private val symbolTable = new SymbolTable
  private val workspaceIndex = new WorkspaceIndex(workspacePath, symbolTable)
  private val bspManager = BspManager(workspacePath, workspaceIndex)

  // ----- LanguageClientAware
  override def connect(client: LanguageClient): Unit = {
    logger.debug(s"Client connected: ${client}")
    this.client = client
  }

  // ----- LanguageServer
  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    val capabilities = ServerCapabilities()
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    capabilities.setDefinitionProvider(true)
    capabilities.setReferencesProvider(true)
    capabilities.setDocumentSymbolProvider(true)

    // Build symbol table from semanticdb files + parsed source files
    val semanticdbDirs = loadSemanticdbDirsFromDataJson()
    workspaceIndex.initialize(semanticdbDirs)
    // Wire BSP manager (discovers .bsp configs, lazy spawn on first poke)
    bspManager.initialize(workspacePath, client)

    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  override def shutdown(): CompletableFuture[Object] = {
    logger.debug("Shutdown...")
    cleanup()
    CompletableFuture.completedFuture(null)
  }

  override def exit(): Unit = {
    logger.debug("Exit...")
    cleanup()
    System.exit(0)
  }

  /** Idempotent cleanup — called by shutdown/exit and the JVM shutdown hook. */
  def cleanup(): Unit = bspManager.shutdown()

  /** Read .basamake/bsp/.../data.json files and collect semanticdb dirs.
    * Speeds up subsequent startups by indexing BSP-managed output dirs without
    * walking the entire workspace. Returns empty list if no data.json files exist. */
  private def loadSemanticdbDirsFromDataJson(): List[String] = {
    val bspDir = workspacePath / ".basamake" / "bsp"
    if (!os.isDir(bspDir)) return Nil
    try {
      val dataFiles = os.walk(bspDir, maxDepth = 2).filter(_.last == "data.json")
      dataFiles.flatMap { f =>
        try {
          val data = os.read(f).parseJson[BspTargetData]
          data.targets.flatMap(_.semanticdbDirs)
        } catch {
          case e: Exception =>
            logger.debug(s"Skipping ${f.relativeTo(workspacePath)}: ${e.getMessage}")
            Nil
        }
      }.toList
    } catch {
      case e: Exception =>
        logger.debug(s"Failed to load data.json: ${e.getMessage}")
        Nil
    }
  }

  override def getWorkspaceService(): WorkspaceService = this
  override def getTextDocumentService(): TextDocumentService = this

  // ----- WorkspaceService
  override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()
  override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = ()

  // ----- TextDocumentService
  override def didOpen(params: DidOpenTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    val path = os.Path(URI.create(uri))
    workspaceIndex.onDidOpen(path, params.getTextDocument.getText)
    // liveness only — fire-and-forget; never block UI
    Thread.ofVirtual().start(() => bspManager.poke(uri, compile = false))
  }

  override def didChange(params: DidChangeTextDocumentParams): Unit = {
    val path = os.Path(URI.create(params.getTextDocument.getUri))
    // Full sync — last change's text is the whole document
    val text = params.getContentChanges.asScala.last.getText
    workspaceIndex.onDidChange(path, text)
  }

  override def didSave(params: DidSaveTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    val path = os.Path(URI.create(uri))
    workspaceIndex.onDidSave(path, Option(params.getText))
    // liveness + compile — fire-and-forget; didSave has no return value
    Thread.ofVirtual().start(() => bspManager.poke(uri, compile = true))
  }

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    val path = os.Path(URI.create(uri))
    workspaceIndex.onDidClose(path)
    bspManager.clearDiagnostics(uri)
  }

  override def definition(params: DefinitionParams)
      : CompletableFuture[org.eclipse.lsp4j.jsonrpc.messages.Either[
        java.util.List[? <: Location],
        java.util.List[? <: LocationLink]
      ]] =
    CompletableFuture.supplyAsync { () =>
      val uri = params.getTextDocument.getUri
      // Fire-and-forget liveness — does NOT block the nav response.
      Thread.ofVirtual().start(() => bspManager.poke(uri, compile = false))
      val path = os.Path(URI.create(uri))
      val line = params.getPosition.getLine
      val char = params.getPosition.getCharacter
      val locs = workspaceIndex.gotoDefinitions(path, line, char).map(toLspLocation).asJava
      org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(locs)
    }

  override def references(params: ReferenceParams): CompletableFuture[java.util.List[? <: Location]] =
    CompletableFuture.supplyAsync { () =>
      val uri = params.getTextDocument.getUri
      Thread.ofVirtual().start(() => bspManager.poke(uri, compile = false))
      val path = os.Path(URI.create(uri))
      val line = params.getPosition.getLine
      val char = params.getPosition.getCharacter
      val includeDecl = params.getContext.isIncludeDeclaration
      workspaceIndex.references(path, line, char, includeDecl).map(toLspLocation).asJava
    }

  // documentSymbol returns empty for v1 — descriptor → SymbolKind map is deferred follow-up
  override def documentSymbol(params: DocumentSymbolParams)
      : CompletableFuture[
        java.util.List[org.eclipse.lsp4j.jsonrpc.messages.Either[SymbolInformation, DocumentSymbol]]
      ] =
    CompletableFuture.completedFuture(
      List.empty.asJava
    )

  private def toLspLocation(loc: SymbolDefinition): Location = {
    val uri = loc.path.toNIO.toUri.toString
    val range = new Range(
        new Position(loc.range.startLine, loc.range.startCharacter),
        new Position(loc.range.endLine, loc.range.endCharacter)
    )
    new Location(uri, range)
  }
}
