package ba.sake.basamake.bsp

import ba.sake.basamake.core.*
import com.typesafe.scalalogging.StrictLogging
import ch.epfl.scala.bsp4j.*
import scala.jdk.CollectionConverters.*

final case class HandshakeResult(
    process: java.lang.Process,
    buildServer: BuildServer,
    targets: WorkspaceBuildTargetsResult,
    sources: SourcesResult
)

object BspHandshake extends StrictLogging {

  // Straight-line blocking handshake on the calling virtual thread.
  // Errors propagate up to the supervisor for state transition.
  // durable.bspProcess is set IMMEDIATELY after spawn, before any blocking calls,
  // so killBspProcesses() can always find and destroy the child process.
  def execute(
      bspFile: BspConnectionFile,
      queue: java.util.concurrent.BlockingQueue[ConnectionMessage],
      durable: DurableRecord,
      timeoutSec: Long = 60
  ): HandshakeResult = {
    logger.info(s"Starting BSP handshake for ${bspFile.path}")

    val pb = new java.lang.ProcessBuilder(bspFile.argv*)
    pb.directory(bspFile.workingDir.toFile)
    pb.redirectError(java.lang.ProcessBuilder.Redirect.PIPE)

    val process = pb.start()
    durable.bspProcess = Some(process) // store IMMEDIATELY — killable even if handshake fails
    logger.info(s"Spawned BSP process (pid ${process.pid()}) with argv: ${bspFile.argv.mkString(" ")}")

    val buildClient = BasamakeBuildClient(queue)

    val launcher =
      new org.eclipse.lsp4j.jsonrpc.Launcher.Builder[BuildServer]()
        .setRemoteInterface(classOf[BuildServer])
        .setLocalService(buildClient)
        .setInput(process.getInputStream)
        .setOutput(process.getOutputStream)
        .create()
    val buildServer = launcher.getRemoteProxy
    launcher.startListening()
    logger.info(s"BSP launcher started for ${bspFile.path}.")

    // Handshake sequence
    val caps = new BuildClientCapabilities(List("scala", "java").asJava)
    val initParams = new InitializeBuildParams(
      "basamake",
      "0.1.0",
      "2.1.0",
      bspFile.workingDir.toUri.toString,
      caps
    )

    logger.debug("Sending buildInitialize...")
    buildServer.buildInitialize(initParams).get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    logger.debug("buildInitialize OK")

    buildServer.onBuildInitialized()
    logger.debug("onBuildInitialized sent")

    logger.debug("Requesting workspaceBuildTargets...")
    val targetsResult = buildServer.workspaceBuildTargets().get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    val targetIds = targetsResult.getTargets.asScala.map(_.getId).toList
    logger.debug(s"Found ${targetIds.size} build targets: ${targetIds.map(_.getUri).mkString(", ")}")

    logger.debug("Requesting buildTargetSources...")
    val sourcesParams = new SourcesParams(targetIds.asJava)
    val sourcesResult = buildServer.buildTargetSources(sourcesParams).get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    logger.debug("buildTargetSources OK")

    HandshakeResult(process, buildServer, targetsResult, sourcesResult)
  }
}
