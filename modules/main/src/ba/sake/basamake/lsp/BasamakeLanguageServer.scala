package ba.sake.basamake.lsp

import java.net.URI
import java.util.concurrent.CompletableFuture
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*

import ba.sake.basamake.navigation.{SymbolDefinition, SymbolTable}
import ba.sake.basamake.navigation.indexing.{WorkspaceIndex, SemanticdbDirs}
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
    logger.debug("initialize called")
    val capabilities = ServerCapabilities()
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    capabilities.setDefinitionProvider(true)
    capabilities.setReferencesProvider(true)
    capabilities.setDocumentSymbolProvider(true)
    // Advertise rename handling so VS Code sends didRenameFiles notifications.
    // MUST declare filters: vscode-languageclient only registers its
    // workspace/didRenameFiles listener when filters are present
    // (fileOperations.js: capability?.filters !== undefined).
    val fileOps = new FileOperationsServerCapabilities()
    val didRenameOpts = new FileOperationOptions()
    didRenameOpts.setFilters(java.util.List.of(
      new FileOperationFilter(new FileOperationPattern("**/*.{scala,java,sbt}"))
    ))
    fileOps.setDidRename(didRenameOpts)
    val wsCaps = new WorkspaceServerCapabilities()
    wsCaps.setFileOperations(fileOps)
    capabilities.setWorkspace(wsCaps)
    val roots = loadSemanticdbRootsFromDataJson()
    try {
      workspaceIndex.initialize(roots)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to initialize workspace index: ${e.getMessage}")
    }
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


  override def getWorkspaceService(): WorkspaceService = this
  override def getTextDocumentService(): TextDocumentService = this

  // ----- WorkspaceService
  override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = {
    logger.debug(s"didChangeConfiguration: ${params.getSettings}")
  }

  override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = {
    val changes = params.getChanges.asScala.toList
    val events = changes.map(e => s"${e.getType}=${e.getUri}").mkString(", ")
    logger.debug(s"didChangeWatchedFiles (${changes.size} event(s)): $events")
    // React to creates/deletes (terminal mv, external tools — these never send
    // didRenameFiles). Ignore change events: didSave already compiles those.
    val created = changes.filter(_.getType == FileChangeType.Created).map(_.getUri)
    val deleted = changes.filter(_.getType == FileChangeType.Deleted).map(_.getUri)
    if (created.nonEmpty || deleted.nonEmpty) {
      bspManager.onWatchedFilesChanged(created, deleted)
    }
    // purge index state for files actually removed from disk
    if (deleted.nonEmpty) {
      val deletedPaths = deleted.flatMap { uri =>
        try Some(os.Path(URI.create(uri))) catch { case _: Exception => None }
      }.toSet
      if (deletedPaths.nonEmpty) workspaceIndex.onFilesDeleted(deletedPaths)
    }
  }

  /** VS Code sends workspace/didRenameFiles when user renames a file.
    * Clear old diagnostics, re-index new file into WorkspaceIndex,
    * and trigger BSP compile so diagnostics appear for the new file. */
  override def didRenameFiles(params: RenameFilesParams): Unit = {
    val renames = params.getFiles.asScala.map(r => s"${r.getOldUri} -> ${r.getNewUri}").mkString(", ")
    logger.debug(s"didRenameFiles (${params.getFiles.size()} file(s)): $renames")
    params.getFiles.forEach { rename =>
      bspManager.clearDiagnostics(rename.getOldUri)
      val oldPath = try Some(os.Path(URI.create(rename.getOldUri))) catch { case _: Exception => None }
      oldPath.foreach(p => workspaceIndex.onFilesDeleted(Set(p)))
      val newPath = os.Path(URI.create(rename.getNewUri))
      workspaceIndex.onDidOpen(newPath)
      Thread.ofVirtual().start(() => bspManager.poke(rename.getNewUri, compile = true))
    }
  }

  // ----- TextDocumentService
  override def didOpen(params: DidOpenTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    logger.debug(s"didOpen: $uri")
    val path = os.Path(URI.create(uri))
    workspaceIndex.onDidOpen(path)
    Thread.ofVirtual().start(() => bspManager.poke(uri, compile = false))
  }

  override def didChange(params: DidChangeTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    logger.debug(s"didChange: $uri")
    val path = os.Path(URI.create(uri))
    // TODO in new thread?
    workspaceIndex.onDidChange(path)
  }

  override def didSave(params: DidSaveTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    logger.debug(s"didSave: $uri")
    val path = os.Path(URI.create(uri))
    Thread.ofVirtual().start(() => {
      bspManager.poke(uri, compile = true)
      workspaceIndex.onDidSave(path)
    })
  }

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    // TODO in new thread?
    val uri = params.getTextDocument.getUri
    logger.debug(s"didClose: $uri")
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
      logger.debug(s"definition: $uri at ${params.getPosition.getLine}:${params.getPosition.getCharacter}")
      Thread.ofVirtual().start(() => bspManager.poke(uri, compile = false))
      val path = os.Path(URI.create(uri))
      val line = params.getPosition.getLine
      val char = params.getPosition.getCharacter
      val locs = workspaceIndex.gotoDefinitions(path, line, char).map(toLspLocation).asJava
      logger.debug(s"definition at $line:$char → ${locs.size()} location(s): ${locs.asScala.map(_.getUri).mkString(", ")}")
      org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(locs)
    }

  override def references(params: ReferenceParams): CompletableFuture[java.util.List[? <: Location]] =
    CompletableFuture.supplyAsync { () =>
      val uri = params.getTextDocument.getUri
      logger.debug(s"references: $uri at ${params.getPosition.getLine}:${params.getPosition.getCharacter}, includeDecl=${params.getContext.isIncludeDeclaration}")
      Thread.ofVirtual().start(() => bspManager.poke(uri, compile = false))
      val path = os.Path(URI.create(uri))
      val line = params.getPosition.getLine
      val char = params.getPosition.getCharacter
      val includeDecl = params.getContext.isIncludeDeclaration
      val locs = workspaceIndex.references(path, line, char, includeDecl).map(toLspLocation).asJava
      logger.debug(s"references at $line:$char (includeDecl=$includeDecl) → ${locs.size()} location(s): ${locs.asScala.map(_.getUri).mkString(", ")}")
      locs
    }

  // documentSymbol returns empty for v1 — descriptor → SymbolKind map is deferred follow-up
  override def documentSymbol(params: DocumentSymbolParams)
      : CompletableFuture[
        java.util.List[org.eclipse.lsp4j.jsonrpc.messages.Either[SymbolInformation, DocumentSymbol]]
      ] = {
    logger.debug(s"documentSymbol: ${params.getTextDocument.getUri}")
    CompletableFuture.completedFuture(
      List.empty.asJava
    )
  }

  private def toLspLocation(loc: SymbolDefinition): Location = {
    val uri = loc.path.toNIO.toUri.toString
    val range = new Range(
        new Position(loc.range.startLine, loc.range.startCharacter),
        new Position(loc.range.endLine, loc.range.endCharacter)
    )
    new Location(uri, range)
  }

  /** Read .basamake/bsp/.../data.json files and collect (sourceRootDir, semanticdbDir) pairs.
    * Speeds up subsequent startups by indexing BSP-managed output dirs without
    * walking the entire workspace. Returns empty list if no data.json files exist. */
  private def loadSemanticdbRootsFromDataJson(): List[SemanticdbDirs] = {
    val bspDir = workspacePath / ".basamake/bsp"
    if (!os.exists(bspDir) || !os.isDir(bspDir)) {
      logger.debug(s"No BSP data.json files found in ${bspDir}")
      return Nil
    }
    try {
      val dataFiles = os.walk(bspDir, maxDepth = 2).filter(_.last == "data.json")
      dataFiles.flatMap { f =>
        try {
          val data = os.read(f).parseJson[BspTargetData]
          data.targets.map(t => SemanticdbDirs(t.sourceRootDir, t.semanticdbDir))
        } catch {
          case e: Exception =>
            logger.error(s"Skipping ${f.relativeTo(workspacePath)}: ${e.getMessage}")
            Nil
        }
      }.toList
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load data.json files: ${e.getMessage}")
        Nil
    }
  }
}
