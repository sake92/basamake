package ba.sake.basamake.lsp

import java.net.URI
import java.util.concurrent.CompletableFuture
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*
import ba.sake.basamake.lsp.index.WorkspaceIndex
import ba.sake.basamake.navigation.SourceSemanticdb
import ba.sake.basamake.navigation.ScalaSourceParser
import ba.sake.basamake.navigation.JavaSourceParser
import ba.sake.basamake.navigation.SymbolLocation

class BasamakeLanguageServer(workspacePath: os.Path) extends LanguageClientAware, LanguageServer, TextDocumentService, WorkspaceService, StrictLogging {

  @volatile private var client: LanguageClient = uninitialized

  private val workspaceIndex = WorkspaceIndex()
 
  // ----- LanguageClientAware
  def connect(client: LanguageClient): Unit = {
    logger.debug(s"Client connected: ${client}")
    this.client = client
  }
  
  // ----- LanguageServer
  def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    val capabilities = ServerCapabilities()
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    capabilities.setDefinitionProvider(true)
    capabilities.setReferencesProvider(true)
    capabilities.setDocumentSymbolProvider(true)
    
    val parseSourceFile: os.Path => Option[SourceSemanticdb] = path => {
      if path.ext == "scala" then {
        val parser = ScalaSourceParser(path)
        Some(parser.parse())
      } else if path.ext == "java" then {
        val parser = JavaSourceParser(path)
        Some(parser.parse())
      } else None
    }
    initIndexFromSources(workspacePath, parseSourceFile)
    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  def shutdown(): CompletableFuture[Object] = {
    logger.debug("Shutdown...")
    CompletableFuture.completedFuture(null)
  }
  
  def exit(): Unit = {
    logger.debug("Exit...")
    System.exit(0)
  }

  def getWorkspaceService(): WorkspaceService = this
  def getTextDocumentService(): TextDocumentService = this

  // ----- WorkspaceService
  def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()
  def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = ()

  // ----- TextDocumentService
  def didChange(params: DidChangeTextDocumentParams): Unit = ()
  def didClose(params: DidCloseTextDocumentParams): Unit = ()
  def didOpen(params: DidOpenTextDocumentParams): Unit = ()
  def didSave(params: DidSaveTextDocumentParams): Unit = ()
  
  override def definition(params: DefinitionParams)
      : CompletableFuture[org.eclipse.lsp4j.jsonrpc.messages.Either[
        java.util.List[? <: Location],
        java.util.List[? <: LocationLink]
      ]] =
    CompletableFuture.supplyAsync { () =>
        val uriStr = params.getTextDocument.getUri
        val path   = os.Path(URI.create(uriStr))
        val line   = params.getPosition.getLine
        val char   = params.getPosition.getCharacter

        val locationsList = workspaceIndex.findSymbolsAt(path, line, char)
          .flatMap { symbol =>
            // Local first — nearest scope wins (compiler scoping rules)
            workspaceIndex.findLocalDefinition(path, symbol) match
              case Some(loc) => Vector(loc)
              case None      => workspaceIndex.gotoDefinitions(symbol)
          }
          .map(toLspLocation)
          .distinct
          .asJava
        org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(locationsList)
  }

  override def documentSymbol(params: DocumentSymbolParams)
      : CompletableFuture[
        java.util.List[org.eclipse.lsp4j.jsonrpc.messages.Either[SymbolInformation, DocumentSymbol]]
      ] =
    CompletableFuture.completedFuture(
      List.empty.asJava
    )

  override def references(params: ReferenceParams): CompletableFuture[java.util.List[? <: Location]] =
    CompletableFuture.completedFuture(
      List.empty.asJava
    )

  private def toLspLocation(loc: SymbolLocation): Location = {
    val uri = loc.path.toNIO.toUri.toString
    val range = new Range(
        new Position(loc.range.startLine, loc.range.startCharacter),
        new Position(loc.range.endLine, loc.range.endCharacter)
    )
    new Location(uri, range)
  }

  private def initIndexFromSources(workspaceRoot: os.Path, parseSourceFile: os.Path => Option[SourceSemanticdb]): Unit = {
    val scalaAndJavaFiles = os.walk(workspaceRoot).filter { p =>
        os.isFile(p) && (p.ext == "scala" || p.ext == "java")
    }

    for path <- scalaAndJavaFiles do {
      logger.debug(s"Parsing source file: $path")
        parseSourceFile(path).foreach { doc =>
          workspaceIndex.indexFile(path, doc)
        }
    }
    logger.debug("Index: \n" + workspaceIndex.definitions.map { case (symbol, loc) => s"$symbol -> $loc" }.mkString("\n"))
  }
}