package ba.sake.basamake.bsp

import ba.sake.basamake.core.*
import ba.sake.basamake.util.Log
import ch.epfl.scala.bsp4j.*
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

final case class HandshakeResult(
    process: java.lang.Process,
    buildServer: BuildServer,
    targets: WorkspaceBuildTargetsResult,
    sources: SourcesResult
)

object BspHandshake:

  // Straight-line blocking handshake on the calling virtual thread.
  // Errors propagate up to the supervisor for state transition.
  def execute(
      spec: ConnectionSpec,
      queue: java.util.concurrent.BlockingQueue[ConnectionMessage],
      timeoutSec: Long = 60
  ): HandshakeResult =
    Log.info(s"Starting BSP handshake for ${spec.path}")

    val config = parseBspJson(spec.path)

    // Kill any leftover BSP processes for this workspace (zombie cleanup)
    killStaleBspProcesses(config.workingDir)

    val pb = new java.lang.ProcessBuilder(config.argv*)
    pb.directory(config.workingDir.toFile)
    pb.redirectError(java.lang.ProcessBuilder.Redirect.PIPE)

    val process = pb.start()
    Log.info(s"Spawned BSP process (pid ${process.pid()}) with argv: ${config.argv.mkString(" ")}")

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
    Log.info("BSP launcher started, reader thread listening")

    // Handshake sequence
    val caps = new BuildClientCapabilities(List("scala").asJava)
    val initParams = new InitializeBuildParams(
      "basamake",
      "0.1.0",
      "2.1.0",
      spec.workingDir.toUri.toString,
      caps
    )

    Log.info("Sending buildInitialize...")
    buildServer.buildInitialize(initParams).get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    Log.info("buildInitialize OK")

    buildServer.onBuildInitialized()
    Log.info("onBuildInitialized sent")

    Log.info("Requesting workspaceBuildTargets...")
    val targetsResult = buildServer.workspaceBuildTargets().get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    val targetIds = targetsResult.getTargets.asScala.map(_.getId).toList
    Log.info(s"Found ${targetIds.size} build targets: ${targetIds.map(_.getUri).mkString(", ")}")

    Log.info("Requesting buildTargetSources...")
    val sourcesParams = new SourcesParams(targetIds.asJava)
    val sourcesResult = buildServer.buildTargetSources(sourcesParams).get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    Log.info("buildTargetSources OK")

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
        Log.warn(s"Could not find '$key' in BSP JSON")
        Nil

  // Kill leftover deder bsp processes for this workspace directory
  private def killStaleBspProcesses(workingDir: Path): Unit =
    try
      ProcessHandle.allProcesses()
        .filter(p => p.info().command().orElse("").contains("deder bsp"))
        .filter(p => p.info().arguments().orElse(Array()).mkString(" ").contains("bsp"))
        .forEach: p =>
          Log.info(s"Killing stale BSP process ${p.pid()}")
          p.destroyForcibly()
    catch case _: Exception => ()
