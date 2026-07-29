package ba.sake.basamake.lsp

import java.net.URI
import java.util.concurrent.CompletableFuture
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*

import ba.sake.basamake.navigation.{SymbolDefinition, SymbolTable}
import ba.sake.basamake.lsp.index.WorkspaceIndex

class BasamakeLanguageServer(workspacePath: os.Path) extends LanguageClientAware, LanguageServer, TextDocumentService, WorkspaceService, StrictLogging {

  @volatile private var client: LanguageClient = uninitialized

  private val symbolTable = new SymbolTable
  private val workspaceIndex = new WorkspaceIndex(symbolTable)

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
    workspaceIndex.initialize(workspacePath)

    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  override def shutdown(): CompletableFuture[Object] = {
    logger.debug("Shutdown...")
    CompletableFuture.completedFuture(null)
  }

  override def exit(): Unit = {
    logger.debug("Exit...")
    System.exit(0)
  }

  override def getWorkspaceService(): WorkspaceService = this
  override def getTextDocumentService(): TextDocumentService = this

  // ----- WorkspaceService
  override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()
  override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = ()

  // ----- TextDocumentService
  override def didOpen(params: DidOpenTextDocumentParams): Unit = {
    val path = os.Path(URI.create(params.getTextDocument.getUri))
    workspaceIndex.onDidOpen(path, params.getTextDocument.getText)
  }

  override def didChange(params: DidChangeTextDocumentParams): Unit = {
    val path = os.Path(URI.create(params.getTextDocument.getUri))
    // Full sync — last change's text is the whole document
    val text = params.getContentChanges.asScala.last.getText
    workspaceIndex.onDidChange(path, text)
  }

  override def didSave(params: DidSaveTextDocumentParams): Unit = {
    val path = os.Path(URI.create(params.getTextDocument.getUri))
    // Option[String] in lsp4j 1.0.0 — getText returns nullable String
    workspaceIndex.onDidSave(path, Option(params.getText))
  }

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = os.Path(URI.create(params.getTextDocument.getUri))
    workspaceIndex.onDidClose(path)
  }

  override def definition(params: DefinitionParams)
      : CompletableFuture[org.eclipse.lsp4j.jsonrpc.messages.Either[
        java.util.List[? <: Location],
        java.util.List[? <: LocationLink]
      ]] =
    CompletableFuture.supplyAsync { () =>
      val path = os.Path(URI.create(params.getTextDocument.getUri))
      val line = params.getPosition.getLine
      val char = params.getPosition.getCharacter
      val locs = workspaceIndex.gotoDefinitions(path, line, char).map(toLspLocation).asJava
      org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(locs)
    }

  override def references(params: ReferenceParams): CompletableFuture[java.util.List[? <: Location]] =
    CompletableFuture.supplyAsync { () =>
      val path = os.Path(URI.create(params.getTextDocument.getUri))
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
