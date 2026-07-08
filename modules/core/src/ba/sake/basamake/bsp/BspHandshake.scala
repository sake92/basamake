package ba.sake.basamake.bsp

import ba.sake.basamake.core.*
import com.typesafe.scalalogging.StrictLogging
import ch.epfl.scala.bsp4j.*
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

final case class HandshakeResult(
    process: java.lang.Process,
    buildServer: BuildServer,
    targets: WorkspaceBuildTargetsResult,
    sources: SourcesResult
)

object BspHandshake extends StrictLogging:

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

    val config = parseBspJson(bspFile.path)

    val pb = new java.lang.ProcessBuilder(config.argv*)
    pb.directory(config.workingDir.toFile)
    pb.redirectError(java.lang.ProcessBuilder.Redirect.PIPE)

    val process = pb.start()
    durable.bspProcess = Some(process) // store IMMEDIATELY — killable even if handshake fails
    logger.info(s"Spawned BSP process (pid ${process.pid()}) with argv: ${config.argv.mkString(" ")}")

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

  private case class BspJsonConfig(argv: List[String], workingDir: Path)

  private def parseBspJson(jsonPath: Path): BspJsonConfig =
    val raw = java.nio.file.Files.readString(jsonPath)
    val argv = extractJsonArray(raw, "argv")
    val workingDir = Option(jsonPath.getParent.getParent)
      .getOrElse(Path.of("."))
    BspJsonConfig(argv, workingDir)

  // Bare-minimum JSON array extractor
  // TODO use tupson library for proper JSON parsing
  private def extractJsonArray(raw: String, key: String): List[String] =
    val pattern = s""""$key"\\s*:\\s*\\[(.*?)\\]""".r
    pattern.findFirstMatchIn(raw) match
      case Some(m) =>
        val inner = m.group(1)
        "\"(.*?)\"".r.findAllMatchIn(inner).map(_.group(1)).toList
      case None =>
        logger.warn(s"Could not find '$key' in BSP JSON")
        Nil
