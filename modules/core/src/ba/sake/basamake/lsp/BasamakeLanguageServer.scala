package ba.sake.basamake.lsp

import java.util.concurrent.CompletableFuture
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*
import ba.sake.basamake.core.ConnectionMessage
import ba.sake.basamake.config.BasamakeConfig
import ba.sake.basamake.manager.BuildServerManager
import ba.sake.basamake.util.LoggingUtils

class BasamakeLanguageServer(
    private val manager: BuildServerManager = BuildServerManager()
) extends LanguageServer, TextDocumentService, LanguageClientAware, StrictLogging {

  private var client: LanguageClient = uninitialized
  @volatile private var isInitialized = false
  private var workspaceRoot: os.Path = uninitialized

  // ---- LanguageClientAware ----
  override def connect(client: LanguageClient): Unit =
    this.client = client

  // ---- LanguageServer ----
  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    workspaceRoot = Option(params.getRootUri) match
      case Some(uri) => os.Path(java.net.URI.create(uri))
      // TODO support multi-root workspaces
      case None      => Option(params.getWorkspaceFolders)
                          .flatMap(_.asScala.headOption)
                          .map(f => os.Path(java.net.URI.create(f.getUri)))
                          .getOrElse(os.pwd)
    // Reconfigure file logging to the actual workspace
    LoggingUtils.configureFileLogging(workspaceRoot)
    logger.info(s"initialize: workspace=$workspaceRoot")

    val capabilities = ServerCapabilities()
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    capabilities.setDefinitionProvider(true)
    capabilities.setReferencesProvider(true)
    capabilities.setDocumentSymbolProvider(true)
    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  override def initialized(params: InitializedParams): Unit = {
    logger.debug("Initialized. Spawning BSP connections...")
    isInitialized = true
    val config = BasamakeConfig.load(workspaceRoot)
    logger.debug(s"Config loaded with ${config.bspOverrides.size} override(s)")
    manager.initialize(workspaceRoot, client, config)
  }

  override def shutdown(): CompletableFuture[Object] = {
    logger.debug("shutdown...")
    cleanup()
    CompletableFuture.completedFuture(null)
  }

  override def exit(): Unit = {
    logger.debug("exit...")
    cleanup()
    sys.exit(0)
  }

  /** Also called after transport closes (stdin EOF) to clean up child BSP processes. */
  def cleanup(): Unit = {
    manager.shutdown()
  }

  override def getTextDocumentService: TextDocumentService = this

  override def getWorkspaceService: WorkspaceService =
    new WorkspaceService:
      override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()
      override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = ()

  // ---- TextDocumentService ----
  override def didOpen(params: DidOpenTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    logger.debug(s"didOpen: $uri")
    manager.trackDidOpen(uri)
    offerToConnection(uri, ConnectionMessage.DidOpen(params))
  }

  override def didChange(params: DidChangeTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    logger.debug(s"didChange: $uri")
    offerToConnection(uri, ConnectionMessage.DidChange(params))
  }

  override def didSave(params: DidSaveTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    logger.debug(s"didSave: $uri")
    offerToConnection(uri, ConnectionMessage.DidSave(params))
  }

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    logger.debug(s"didClose: $uri")
    manager.trackDidClose(uri)
    offerToConnection(uri, ConnectionMessage.DidClose(params))
  }

  private def offerToConnection(uri: String, msg: ConnectionMessage): Unit ={
    if !isInitialized then
      // TODO return error to client? (e.g. publish diagnostics)
      logger.warn(s"Not initialized, dropping message for $uri")
      return
    try manager.route(uri) match {
      case Some(queue) =>
        logger.debug(s"Offering message for $uri to BSP connection")
        queue.offer(msg)
      case None =>
        logger.debug(s"No BSP connection found for $uri, dropping message")
        // TODO reply with error to client? (e.g. publish diagnostics)
    }
    catch case e: Exception => logger.error(s"Failed to route message for $uri", e)
  }

  // TODO support completion
  override def completion(params: CompletionParams)
      : CompletableFuture[org.eclipse.lsp4j.jsonrpc.messages.Either[
        java.util.List[CompletionItem],
        CompletionList
      ]] =
    CompletableFuture.completedFuture(
      org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
        java.util.Collections.emptyList[CompletionItem]()
      )
    )

  // TODO support hover  
  override def hover(params: HoverParams): CompletableFuture[Hover] =
    CompletableFuture.completedFuture(null)

  override def definition(params: DefinitionParams)
      : CompletableFuture[org.eclipse.lsp4j.jsonrpc.messages.Either[
        java.util.List[? <: Location],
        java.util.List[? <: LocationLink]
      ]] =
    CompletableFuture.completedFuture(
      org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
        manager.definition(params.getTextDocument.getUri, params.getPosition).asJava
      )
    )

  override def references(params: ReferenceParams): CompletableFuture[java.util.List[? <: Location]] =
    CompletableFuture.completedFuture(
      manager.references(params.getTextDocument.getUri, params.getPosition).asJava
    )


  override def documentSymbol(params: DocumentSymbolParams)
      : CompletableFuture[
        java.util.List[org.eclipse.lsp4j.jsonrpc.messages.Either[SymbolInformation, DocumentSymbol]]
      ] =
    CompletableFuture.completedFuture(
      manager.documentSymbols(params.getTextDocument.getUri).asJava
    )

  override def signatureHelp(params: SignatureHelpParams): CompletableFuture[SignatureHelp] =
    CompletableFuture.completedFuture(null)

  override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
    CompletableFuture.completedFuture(null)
}