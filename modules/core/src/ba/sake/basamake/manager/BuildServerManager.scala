package ba.sake.basamake.manager

import ba.sake.basamake.core.*
import ba.sake.basamake.bsp.{BspConnectionSupervisor, BspDiscovery, BspConnectionId, BspConnectionState}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Path
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.collection.mutable
import scala.compiletime.uninitialized

// Owns durable records, channels, and connection lifecycles.
// Coordinates N connections (1 in M1, N in M2).
class BuildServerManager extends StrictLogging {
  private val connections = mutable.LinkedHashMap[BspConnectionId, DurableRecord]()
  private val channels    = mutable.LinkedHashMap[BspConnectionId, BlockingQueue[ConnectionMessage]]()
  private var client: LanguageClient = uninitialized

  def initialize(workspaceRoot: Path, lspClient: LanguageClient): Unit = {
    client = lspClient
    val bspFiles = BspDiscovery.discover(workspaceRoot)
    logger.info(s"Discovered ${bspFiles.size} BSP connection(s)")

    for bspFile <- bspFiles do
      val id = BspConnectionId(bspFile.path.toAbsolutePath.toString)
      val record = DurableRecord(
        bspFile = bspFile,
        attemptCounter = 0,
        lastKnownDiagnostics = Map.empty,
        currentState = BspConnectionState.Idle
      )
      val queue = new LinkedBlockingQueue[ConnectionMessage]()
      connections(id) = record
      channels(id) = queue

      val vt = Thread.ofVirtual().start(() =>
        BspConnectionSupervisor.supervise(record, queue, client)
      )
      logger.info(s"Spawned supervisor for $id on VT ${vt.threadId()}")
  }

  // Route a document URI to the owning connection's queue.
  // M1: always returns the first (only) connection.
  // M2: routing table lookup based on file ownership.
  def route(uri: String): BlockingQueue[ConnectionMessage] =
    if channels.isEmpty then
      throw IllegalStateException(
        "No BSP connections available. Is the workspace initialized?"
      )
    channels.values.head

  def shutdown(): Unit =
    // Signal all supervisors to detach
    connections.values.foreach: record =>
      record.currentState = BspConnectionState.Detached
    // Send poison pill to unblock any supervisor stuck in queue.take()
    channels.values.foreach: queue =>
      queue.offer(ConnectionMessage.Shutdown)
    logger.info("All connections detached")

  /** Force-kill any BSP processes that survived the graceful shutdown.
    * Does a second pass after a grace period to catch processes spawned
    * by supervisors that were mid-retry when shutdown was called. */
  def killBspProcesses(): Unit =
    Thread.sleep(500)
    killAllBspProcesses()
    Thread.sleep(200)
    killAllBspProcesses() // catch late spawns during first pass

  private def killAllBspProcesses(): Unit =
    connections.values.foreach: record =>
      record.bspProcess.foreach: p =>
        if p.isAlive then
          logger.info(s"Force-killing BSP process ${p.pid()}")
          p.destroyForcibly()
        record.bspProcess = None
}
