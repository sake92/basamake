package ba.sake.basamake.bsp

import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import ch.epfl.scala.bsp4j.*
import ba.sake.basamake.core.*
import ba.sake.basamake.util.ProcessUtils

/** Union of BuildServer + ScalaBuildServer. lsp4j discovers all @JsonRequest
  * methods from both parent interfaces. Single proxy, both casts work. */
trait BasamakeBspServer extends BuildServer, ScalaBuildServer

final case class HandshakeResult(
    process: java.lang.Process,
    buildServer: BuildServer,
    targets: WorkspaceBuildTargetsResult,
    sources: SourcesResult,
    dependencySources: DependencySourcesResult
)

object BspHandshake extends StrictLogging {

  /** Spawn a BSP process, create a JSON-RPC proxy implementing both BuildServer and ScalaBuildServer,
    * run the full handshake (initialize → sources → dependency sources), and return the result.
    * The proxy always supports both interfaces — use sites match on `buildServer` to access Scala-specific methods.
    * On failure the process is killed before the exception propagates. */
  def execute(
      bspFile: BspConnectionSpec,
      queue: java.util.concurrent.BlockingQueue[ConnectionMessage],
      timeoutSec: Long = 60
  ): HandshakeResult = {
    val pb = new java.lang.ProcessBuilder(bspFile.content.argv*)
    pb.directory(bspFile.workingDir.toIO)
    pb.redirectError(java.lang.ProcessBuilder.Redirect.PIPE)

    val process = pb.start()
    logger.info(s"BSP process spawned for ${bspFile.path} (pid ${process.pid()})")

    // Drain stderr asynchronously — prevents pipe-buffer deadlock (64KB on Linux)
    // when BSP child logs enough to stderr that it blocks the process.
    Thread.ofVirtual().start(() => {
      val stderr = process.getErrorStream
      try
        val reader = java.io.BufferedReader(java.io.InputStreamReader(stderr, java.nio.charset.StandardCharsets.UTF_8))
        try Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
          logger.debug(s"[bsp-stderr ${bspFile.content.name}] $line")
        }
        finally reader.close()
      catch case _: java.io.IOException => () // process terminated, expected
    })

    try {
      val buildClient = BasamakeBuildClient(queue)

      val launcher =
        new org.eclipse.lsp4j.jsonrpc.Launcher.Builder[BasamakeBspServer]()
          .setRemoteInterface(classOf[BasamakeBspServer])
          .setLocalService(buildClient)
          .setInput(process.getInputStream)
          .setOutput(process.getOutputStream)
          .create()
      val buildServer: BuildServer = launcher.getRemoteProxy
      launcher.startListening()
      logger.info(s"BSP launcher started for ${bspFile.path}.")

      // Handshake sequence
      val caps = new BuildClientCapabilities(List("scala", "java").asJava)
      val initParams = new InitializeBuildParams(
        "basamake",
        "0.1.0",
        "2.1.0",
        bspFile.workingDir.toNIO.toUri.toString,
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
      val sourcesFuture = buildServer.buildTargetSources(sourcesParams)

      logger.debug("Requesting buildTargetDependencySources...")
      val dependencySourcesParams = new DependencySourcesParams(targetIds.asJava)
      val depSourcesFuture = buildServer.buildTargetDependencySources(dependencySourcesParams)

      val sourcesResult = sourcesFuture.get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      logger.debug("buildTargetSources OK")

      val dependencySourcesResult = depSourcesFuture.get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      logger.debug("buildTargetDependencySources OK")

      HandshakeResult(process, buildServer, targetsResult, sourcesResult, dependencySourcesResult)
    } catch {
      case e: Exception =>
        val signaled = ProcessUtils.terminateProcessTree(process)
        logger.warn(s"Handshake failed, killed process ${process.pid()} (signaled $signaled nodes)", e)
        throw e
    }
  }
}
