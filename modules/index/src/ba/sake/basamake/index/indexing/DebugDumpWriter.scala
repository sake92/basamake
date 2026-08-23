package ba.sake.basamake.index.indexing

import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.index.SymbolTable

/** Writes `.basamake/index_sources.txt` (synchronous, cheap — tests rely on it
  * being fresh after invalidate) and `.basamake/symbol_table.txt` (opt-in,
  * throttled background flusher — the full-table serialize (~13MB) must never
  * run on the BSP event thread after every compile). */
private[index] final class DebugDumpWriter(
    workspacePath: os.Path,
    sourcesMap: java.util.concurrent.ConcurrentMap[os.Path, SourceData],
    symbolTable: SymbolTable,
    debugSymbolTableDump: Boolean
) extends StrictLogging {

  private val DumpFlushIntervalMs = 60_000L
  private val symbolTableDirty = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val dumpFlusherStarted = new java.util.concurrent.atomic.AtomicBoolean(false)
  // serialize debug-dump file writes (os.write.over is not atomic)
  private val dumpLock = new Object

  /** True while a symbol-table dump write is pending on the flusher. */
  private[index] def isDirty: Boolean = symbolTableDirty.get()

  /** Test seam: flush the heavy dump immediately. */
  private[index] def flush(): Unit = {
    symbolTableDirty.set(false)
    writeSymbolTableDump()
  }

  /** Full dump after initialize. */
  def writeDebugDump(): Unit = {
    writeIndexSourcesDump()
    if (debugSymbolTableDump) writeSymbolTableDump()
  }

  /** Cheap refresh after index state changes (invalidate / file create+delete). */
  def refresh(): Unit = {
    writeIndexSourcesDump()
    if (debugSymbolTableDump) {
      symbolTableDirty.set(true)
      ensureDumpFlusher()
    }
  }

  /** Lazy-start the background symbol-table flusher (one virtual thread). */
  private def ensureDumpFlusher(): Unit = {
    if (!dumpFlusherStarted.compareAndSet(false, true)) return
    Thread.ofVirtual().start(() => {
      var running = true
      while (running) {
        try Thread.sleep(DumpFlushIntervalMs)
        catch { case _: InterruptedException => running = false }
        if (running && symbolTableDirty.getAndSet(false)) writeSymbolTableDump()
      }
    })
  }

  private def writeIndexSourcesDump(): Unit = {
    try {
      val pairs = sourcesMap.entrySet().asScala.flatMap { e =>
        e.getValue.semanticdbPath.map(sem => e.getKey -> sem)
      }.toMap
      val allSources = sourcesMap.keySet().asScala.toSet
      val dump = SemanticdbIndexing.dumpPairs(pairs, allSources, workspacePath)
      val dumpDir = workspacePath / ".basamake"
      os.makeDir.all(dumpDir)
      dumpLock.synchronized {
        os.write.over(dumpDir / "index_sources.txt", dump)
      }
    } catch {
      case e: Exception => logger.warn(s"Failed to write index_sources.txt: ${e.getMessage}")
    }
  }

  private def writeSymbolTableDump(): Unit = {
    try {
      val dumpDir = workspacePath / ".basamake"
      os.makeDir.all(dumpDir)
      dumpLock.synchronized {
        os.write.over(dumpDir / "symbol_table.txt", symbolTable.all.toVector.sortBy(_.symbol).mkString("\n"), createFolders = true)
      }
    } catch {
      case e: Exception => logger.warn(s"Failed to write symbol_table.txt: ${e.getMessage}")
    }
  }
}
