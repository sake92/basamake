package ba.sake.basamake.lsp

import java.net.URI
import java.util.concurrent.CompletableFuture
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*

import ba.sake.basamake.navigation.{SymbolDefinition, SymbolTable, InMemorySymbolTable, CompositeSymbolTable, HoverProvider}
import ba.sake.basamake.navigation.indexing.{WorkspaceIndex, SemanticdbDirs, IndexedSymbolTable}
import ba.sake.basamake.bsp.{BspManager, BspTargetData}
import ba.sake.basamake.config.BasamakeConfig
import ba.sake.tupson.{given, *}

class BasamakeLanguageServer(workspacePath: os.Path) extends LanguageClientAware, LanguageServer, TextDocumentService, WorkspaceService, StrictLogging {

  @volatile private var client: LanguageClient = uninitialized

  private val progressReporter = new IndexingProgressReporter
  private val workspaceIndexingDone = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** True once the background workspace indexing (launched by initialize) has
    * finished — used by tests to await index readiness. */
  private[lsp] def isWorkspaceIndexingDone: Boolean = workspaceIndexingDone.get()

  private val workspaceSymbolTable = new InMemorySymbolTable
  private val depsSymbolTable = new IndexedSymbolTable(progressReporter)
  private val symbolTable = new CompositeSymbolTable(workspaceSymbolTable, depsSymbolTable)
  private val workspaceIndex = new WorkspaceIndex(
    workspacePath,
    symbolTable,
    BasamakeConfig.load(workspacePath).ignorePatterns.toVector,
    progressReporter
  )
  private val bspManager = BspManager(workspacePath, workspaceIndex, depsSymbolTable)
  private val hoverProvider = HoverProvider(workspaceIndex)

  // ----- LanguageClientAware
  override def connect(client: LanguageClient): Unit = {
    logger.debug(s"Client connected: ${client}")
    this.client = client
    progressReporter.setClient(client)
  }

  // ----- LanguageServer
  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    logger.debug("initialize called")
    val capabilities = ServerCapabilities()
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    capabilities.setDefinitionProvider(true)
    capabilities.setReferencesProvider(true)
    capabilities.setDocumentSymbolProvider(true)
    capabilities.setHoverProvider(true)
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
    // Progress needs the client's window/workDoneProgress/create handler, which
    // vscode-languageclient registers only AFTER the initialize handshake completes
    // — so indexing moves to a background thread and initialize returns early.
    val workDoneProgress: Boolean = Option(params.getCapabilities)
      .flatMap(c => Option(c.getWindow))
      .flatMap(w => Option(w.getWorkDoneProgress))
      .map(_.booleanValue()) // java.lang.Boolean → scala.Boolean
      .getOrElse(false)
    progressReporter.setEnabled(workDoneProgress)

    val (roots, warmDeps) = loadBspDataFromDataJson()
    Thread.ofVirtual().start(() => {
      try {
        workspaceIndex.initialize(roots)
      } catch {
        case e: Exception => logger.error(s"Failed to initialize workspace index: ${e.getMessage}")
      } finally {
        workspaceIndexingDone.set(true)
      }
    })
    // Dependency sources are NOT indexed eagerly: BspManager registers the warm-start
    // targets (cached jars only) and indexes a target's jars lazily when one of its
    // files is opened / poked. The JDK index runs on its OWN background thread — its
    // first progress event (enqueue begin) must not fire on the initialize thread:
    // the client's window/workDoneProgress/create handler only exists after the
    // handshake, and a rejected createProgress would stall initialize for seconds.
    // A cold JDK indexes once in the background, prioritized ahead of all dep jars.
    Thread.ofVirtual().start(() => {
      try {
        depsSymbolTable.ensureJdkIndexed()
      } catch {
        case e: Exception =>
          logger.error(s"Failed to start JDK indexing: ${e.getMessage}")
      }
    })
    // Wire BSP manager (discovers .bsp configs, lazy spawn on first poke)
    bspManager.initialize(workspacePath, client, warmDeps)
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
    // React to creates/deletes/changes (terminal mv, git checkout, external tools —
    // these never send didRenameFiles). Editor saves also produce a change event;
    // the per-target debounce coalesces it with the didSave compile.
    val created = changes.filter(_.getType == FileChangeType.Created).map(_.getUri)
    val deleted = changes.filter(_.getType == FileChangeType.Deleted).map(_.getUri)
    val changed = changes.filter(_.getType == FileChangeType.Changed).map(_.getUri)
    if (created.nonEmpty || deleted.nonEmpty || changed.nonEmpty) {
      bspManager.onWatchedFilesChanged(created, deleted, changed)
    }
    // keep the workspace source list live for created/deleted source files
    val createdPaths = created.flatMap(uriToSourcePath)
    val deletedPaths = deleted.flatMap(uriToSourcePath)
    if (createdPaths.nonEmpty) workspaceIndex.onFilesCreated(createdPaths.toSet)
    if (deletedPaths.nonEmpty) workspaceIndex.onFilesDeleted(deletedPaths.toSet)
  }

  /** Convert a watched-file URI to a source path (.scala/.java/.sbt only). */
  private def uriToSourcePath(uri: String): Option[os.Path] =
    try {
      val p = os.Path(URI.create(uri))
      if (p.ext == "scala" || p.ext == "java" || p.ext == "sbt") Some(p) else None
    } catch { case _: Exception => None }

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
      if (newPath.ext == "scala" || newPath.ext == "java" || newPath.ext == "sbt") workspaceIndex.onFilesCreated(Set(newPath))
      workspaceIndex.onDidOpen(newPath)
      Thread.ofVirtual().start(() => bspManager.poke(rename.getNewUri, compile = true))
    }
  }

  // ----- TextDocumentService
  override def didOpen(params: DidOpenTextDocumentParams): Unit = {
    val uri = params.getTextDocument.getUri
    logger.info(s"didOpen: $uri — scheduling compile")
    val path = os.Path(URI.create(uri))
    workspaceIndex.onDidOpen(path)
    Thread.ofVirtual().start(() => {
      bspManager.ensureDepsIndexedFor(uri)
      bspManager.poke(uri, compile = true)
    })
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
    logger.info(s"didSave: $uri — scheduling compile")
    val path = os.Path(URI.create(uri))
    Thread.ofVirtual().start(() => {
      bspManager.ensureDepsIndexedFor(uri)
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
      Thread.ofVirtual().start(() => {
        bspManager.ensureDepsIndexedFor(uri)
        bspManager.poke(uri, compile = false)
      })
      val path = os.Path(URI.create(uri))
      val line = params.getPosition.getLine
      val char = params.getPosition.getCharacter
      val depCandidates = bspManager.dependencySourcesFor(uri)
      val locs = workspaceIndex.gotoDefinitions(path, line, char, depCandidates).map(toLspLocation).asJava
      logger.debug(s"definition at $line:$char → ${locs.size()} location(s): ${locs.asScala.map(_.getUri).mkString(", ")}")
      org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(locs)
    }

  override def references(params: ReferenceParams): CompletableFuture[java.util.List[? <: Location]] =
    CompletableFuture.supplyAsync { () =>
      val uri = params.getTextDocument.getUri
      logger.debug(s"references: $uri at ${params.getPosition.getLine}:${params.getPosition.getCharacter}, includeDecl=${params.getContext.isIncludeDeclaration}")
      Thread.ofVirtual().start(() => {
        bspManager.ensureDepsIndexedFor(uri)
        bspManager.poke(uri, compile = false)
      })
      val path = os.Path(URI.create(uri))
      val line = params.getPosition.getLine
      val char = params.getPosition.getCharacter
      val includeDecl = params.getContext.isIncludeDeclaration
      val depCandidates = bspManager.dependencySourcesFor(uri)
      val locs = workspaceIndex.references(path, line, char, includeDecl, depCandidates).map(toLspLocation).asJava
      logger.debug(s"references at $line:$char (includeDecl=$includeDecl) → ${locs.size()} location(s): ${locs.asScala.map(_.getUri).mkString(", ")}")
      locs
    }

  override def hover(params: HoverParams): CompletableFuture[Hover] =
    CompletableFuture.supplyAsync { () =>
      val uri = params.getTextDocument.getUri
      logger.debug(s"hover: $uri at ${params.getPosition.getLine}:${params.getPosition.getCharacter}")
      Thread.ofVirtual().start(() => {
        bspManager.ensureDepsIndexedFor(uri)
        bspManager.poke(uri, compile = false)
      })
      val path = os.Path(URI.create(uri))
      val line = params.getPosition.getLine
      val char = params.getPosition.getCharacter
      val depCandidates = bspManager.dependencySourcesFor(uri)
      hoverProvider.hover(path, line, char, depCandidates) match {
        case Some(info) =>
          logger.debug(s"hover at $line:$char → ${info.signature}")
          val md = new MarkupContent()
          md.setKind("markdown")
          md.setValue(info.markdown)
          new Hover(md)
        case None =>
          logger.debug(s"hover at $line:$char → no info")
          null
      }
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

  /** Read .basamake/bsp/.../data.json files and collect (sourceRootDir, semanticdbDir)
    * pairs plus warm per-target dependency source jars (source root → jars). Speeds
    * up subsequent startups by indexing BSP-managed output dirs + known dep sources
    * without walking the entire workspace. Returns empty lists if no data.json files
    * exist. */
  private def loadBspDataFromDataJson(): (List[SemanticdbDirs], List[(os.Path, List[os.Path])]) = {
    val bspDir = workspacePath / ".basamake/bsp"
    if (!os.exists(bspDir) || !os.isDir(bspDir)) {
      logger.debug(s"No BSP data.json files found in ${bspDir}")
      return (Nil, Nil)
    }
    try {
      val dataFiles = os.walk(bspDir, maxDepth = 2).filter(_.last == "data.json")
      var roots = List.empty[SemanticdbDirs]
      var warmDeps = List.empty[(os.Path, List[os.Path])]
      dataFiles.foreach { f =>
        try {
          val data = os.read(f).parseJson[BspTargetData]
          data.targets.foreach { t =>
            roots = SemanticdbDirs(t.sourceRootDir, t.semanticdbDir) :: roots
            val deps = t.dependencySources.flatMap(s => try Some(os.Path(s)) catch { case _: Exception => None })
            if (deps.nonEmpty) warmDeps = (t.sourceRootDir, deps) :: warmDeps
          }
        } catch {
          case e: Exception =>
            logger.error(s"Skipping ${f.relativeTo(workspacePath)}: ${e.getMessage}")
        }
      }
      (roots, warmDeps.distinct)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load data.json files: ${e.getMessage}")
        (Nil, Nil)
    }
  }
}
