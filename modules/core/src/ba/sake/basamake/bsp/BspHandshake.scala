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
      spec: ConnectionSpec,
      queue: java.util.concurrent.BlockingQueue[ConnectionMessage],
      durable: DurableRecord,
      timeoutSec: Long = 60
  ): HandshakeResult =
    logger.info(s"Starting BSP handshake for ${spec.path}")

    val config = parseBspJson(spec.path)

    // Kill any leftover BSP processes for this workspace (zombie cleanup)
    killStaleBspProcesses(config.workingDir)

    val pb = new java.lang.ProcessBuilder(config.argv*)
    pb.directory(config.workingDir.toFile)
    pb.redirectError(java.lang.ProcessBuilder.Redirect.PIPE)

    val process = pb.start()
    durable.bspProcess = Some(process) // store IMMEDIATELY — killable even if handshake fails
    logger.info(s"Spawned BSP process (pid ${process.pid()}) with argv: ${config.argv.mkString(" ")}")

    val buildClient = OurBuildClient(queue)

    val launcher =
      new org.eclipse.lsp4j.jsonrpc.Launcher.Builder[BuildServer]()
        .setRemoteInterface(classOf[BuildServer])
        .setLocalService(buildClient)
        .setInput(process.getInputStream)
        .setOutput(process.getOutputStream)
        .create()

    val buildServer = launcher.getRemoteProxy
    launcher.startListening()
    logger.info("BSP launcher started, reader thread listening")

    // Handshake sequence
    val caps = new BuildClientCapabilities(List("scala").asJava)
    val initParams = new InitializeBuildParams(
      "basamake",
      "0.1.0",
      "2.1.0",
      spec.workingDir.toUri.toString,
      caps
    )

    logger.info("Sending buildInitialize...")
    buildServer.buildInitialize(initParams).get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    logger.info("buildInitialize OK")

    buildServer.onBuildInitialized()
    logger.info("onBuildInitialized sent")

    logger.info("Requesting workspaceBuildTargets...")
    val targetsResult = buildServer.workspaceBuildTargets().get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    val targetIds = targetsResult.getTargets.asScala.map(_.getId).toList
    logger.info(s"Found ${targetIds.size} build targets: ${targetIds.map(_.getUri).mkString(", ")}")

    logger.info("Requesting buildTargetSources...")
    val sourcesParams = new SourcesParams(targetIds.asJava)
    val sourcesResult = buildServer.buildTargetSources(sourcesParams).get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    logger.info("buildTargetSources OK")

    HandshakeResult(process, buildServer, targetsResult, sourcesResult)

  private case class BspJsonConfig(argv: List[String], workingDir: Path)

  private def parseBspJson(jsonPath: Path): BspJsonConfig =
    val raw = java.nio.file.Files.readString(jsonPath)
    val argv = extractJsonArray(raw, "argv")
    val workingDir = Option(jsonPath.getParent.getParent)
      .getOrElse(Path.of("."))
    BspJsonConfig(argv, workingDir)

  // Bare-minimum JSON array extractor
  private def extractJsonArray(raw: String, key: String): List[String] =
    val pattern = s""""$key"\\s*:\\s*\\[(.*?)\\]""".r
    pattern.findFirstMatchIn(raw) match
      case Some(m) =>
        val inner = m.group(1)
        "\"(.*?)\"".r.findAllMatchIn(inner).map(_.group(1)).toList
      case None =>
        logger.warn(s"Could not find '$key' in BSP JSON")
        Nil

  // Kill leftover deder bsp processes for this workspace directory
  private def killStaleBspProcesses(workingDir: Path): Unit =
    try
      ProcessHandle.allProcesses()
        .filter(p => p.info().command().orElse("").contains("deder bsp"))
        .filter(p => p.info().arguments().orElse(Array()).mkString(" ").contains("bsp"))
        .forEach: p =>
          logger.info(s"Killing stale BSP process ${p.pid()}")
          p.destroyForcibly()
    catch case _: Exception => ()
