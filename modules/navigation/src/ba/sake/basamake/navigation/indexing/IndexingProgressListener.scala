package ba.sake.basamake.navigation.indexing

/** Indexing domains reported as progress. */
enum IndexingPhase:
  case Workspace, Dependencies, Jdk

/** Callback for indexing progress — emitted by the navigation module, consumed
  * by the LSP layer (workDoneProgress). The navigation module never touches LSP. */
trait IndexingProgressListener:
  /** @param done units completed
    * @param total units to complete
    * @param message short human-readable detail (file/jar name) */
  def onProgress(phase: IndexingPhase, done: Long, total: Long, message: String): Unit

object IndexingProgressListener:
  val noop: IndexingProgressListener = (_, _, _, _) => ()
