package ba.sake.basamake.lsp

import java.nio.file.Paths
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

class BasamakeLanguageServer extends LanguageServer, TextDocumentService, LanguageClientAware, StrictLogging:

  private val manager = BuildServerManager()
  private var client: LanguageClient = uninitialized
  @volatile private var isInitialized = false
  private var workspaceRoot: java.nio.file.Path = uninitialized

  // ---- LanguageClientAware ----
  override def connect(client: LanguageClient): Unit =
    this.client = client

  // ---- LanguageServer ----
  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] =
    workspaceRoot = Option(params.getRootUri) match
      case Some(uri) => Paths.get(java.net.URI.create(uri))
      case None      => Option(params.getWorkspaceFolders)
                          .flatMap(_.asScala.headOption)
                          .map(f => Paths.get(java.net.URI.create(f.getUri)))
                          .getOrElse(Paths.get("."))
    // Reconfigure file logging to the actual workspace
    LoggingUtils.configureFileLogging(workspaceRoot)
    logger.info(s"initialize: workspace=$workspaceRoot")

    val capabilities = ServerCapabilities()
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    CompletableFuture.completedFuture(new InitializeResult(capabilities))

  override def initialized(params: InitializedParams): Unit =
    logger.info("initialized — spawning BSP connections")
    isInitialized = true
    val config = BasamakeConfig.load(workspaceRoot)
    logger.info(s"Config loaded: ${config.bspOverrides.size} override(s)")
    manager.initialize(workspaceRoot, client, config)

  override def shutdown(): CompletableFuture[Object] =
    logger.info("shutdown")
    manager.shutdown()
    CompletableFuture.completedFuture(null)

  override def exit(): Unit =
    logger.info("exit — terminating")
    manager.shutdown()
    manager.killBspProcesses()
    sys.exit(0)

  /** Called after transport closes (stdin EOF) to clean up child BSP processes. */
  def cleanup(): Unit =
    manager.shutdown()
    manager.killBspProcesses()

  override def getTextDocumentService: TextDocumentService = this

  override def getWorkspaceService: WorkspaceService =
    new WorkspaceService:
      override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()
      override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = ()

  // ---- TextDocumentService ----

  override def didOpen(params: DidOpenTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    logger.debug(s"didOpen: $uri")
    offerToConnection(uri, ConnectionMessage.DidOpen(params))

  override def didChange(params: DidChangeTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    offerToConnection(uri, ConnectionMessage.DidChange(params))

  override def didSave(params: DidSaveTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    logger.debug(s"didSave: $uri")
    offerToConnection(uri, ConnectionMessage.DidSave(params))

  override def didClose(params: DidCloseTextDocumentParams): Unit =
    val uri = params.getTextDocument.getUri
    logger.debug(s"didClose: $uri")
    offerToConnection(uri, ConnectionMessage.DidClose(params))

  private def offerToConnection(uri: String, msg: ConnectionMessage): Unit =
    if !isInitialized then
      logger.warn(s"Not initialized, dropping message for $uri")
      return
    try manager.route(uri).offer(msg)
    catch case e: Exception => logger.error(s"Failed to route message for $uri", e)

  // ---- Unsupported M3/M4 methods — return empty results ----

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

  override def definition(params: DefinitionParams)
      : CompletableFuture[org.eclipse.lsp4j.jsonrpc.messages.Either[
        java.util.List[? <: Location],
        java.util.List[? <: LocationLink]
      ]] =
    CompletableFuture.completedFuture(
      org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
        java.util.Collections.emptyList[Location]()
      )
    )

  override def references(params: ReferenceParams): CompletableFuture[java.util.List[? <: Location]] =
    CompletableFuture.completedFuture(java.util.Collections.emptyList[Location]())

  override def hover(params: HoverParams): CompletableFuture[Hover] =
    CompletableFuture.completedFuture(null)

  override def documentSymbol(params: DocumentSymbolParams)
      : CompletableFuture[
        java.util.List[org.eclipse.lsp4j.jsonrpc.messages.Either[SymbolInformation, DocumentSymbol]]
      ] =
    CompletableFuture.completedFuture(java.util.Collections.emptyList())

  override def signatureHelp(params: SignatureHelpParams): CompletableFuture[SignatureHelp] =
    CompletableFuture.completedFuture(null)

  override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
    CompletableFuture.completedFuture(null)
