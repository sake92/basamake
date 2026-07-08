package ba.sake.basamake.manager

import ba.sake.basamake.core.*
import ba.sake.basamake.bsp.BspConnectionSupervisor
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Path
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.collection.mutable
import scala.compiletime.uninitialized

// Owns durable records, channels, and connection lifecycles.
// Coordinates N connections (1 in M1, N in M2).
class BuildServerManager extends StrictLogging:
  private val connections = mutable.LinkedHashMap[ConnectionId, DurableRecord]()
  private val channels    = mutable.LinkedHashMap[ConnectionId, BlockingQueue[ConnectionMessage]]()
  private var client: LanguageClient = uninitialized

  def initialize(workspaceRoot: Path, lspClient: LanguageClient): Unit =
    client = lspClient
    val specs = Discovery.discover(workspaceRoot)
    logger.info(s"Discovered ${specs.size} BSP connection(s)")

    for spec <- specs do
      val id = ConnectionId(spec.path.getFileName.toString)
      val record = DurableRecord(
        spec = spec,
        attemptCounter = 0,
        lastKnownDiagnostics = Map.empty,
        currentState = ConnectionState.Idle
      )
      val queue = new LinkedBlockingQueue[ConnectionMessage]()
      connections(id) = record
      channels(id) = queue

      val vt = Thread.ofVirtual().start(() =>
        BspConnectionSupervisor.supervise(record, queue, client)
      )
      logger.info(s"Spawned supervisor for $id on VT ${vt.threadId()}")

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
    connections.values.foreach: record =>
      record.currentState = ConnectionState.Detached
    logger.info("All connections detached")
