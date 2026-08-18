package ba.sake.basamake.bsp

/** Builds real components for BspManager tests using the same construction
  * production uses (BasamakeLanguageServer fields). No testing-only
  * constructors, no nulls, no injected fake clients in production code. */
object BspManagerTestSupport {

  /** New initialized manager on a fixture copy under ./tmp. Caller cleans up
    * the dir. Signatures below mirror production (verified):
    * IndexedSymbolTable(progressListener = noop), WorkspaceIndex(..., defaults). */
  def managerFor(root: os.Path, client: org.eclipse.lsp4j.services.LanguageClient): BspManager =
    managerFor(root, client, workDoneProgress = false)

  /** Same as above, with explicit workDoneProgress capability. */
  def managerFor(root: os.Path, client: org.eclipse.lsp4j.services.LanguageClient, workDoneProgress: Boolean): BspManager = {
    val symbolTable = new ba.sake.basamake.index.InMemorySymbolTable
    val depsTable = new ba.sake.basamake.index.indexing.IndexedSymbolTable() // default noop listener
    val index = new ba.sake.basamake.index.indexing.WorkspaceIndex(
      root,
      symbolTable,
      Some(depsTable)
    )
    val mgr = new BspManager(root, index, depsTable)
    mgr.initialize(client, warmDeps = Nil, workDoneProgress = workDoneProgress)
    mgr
  }
}
