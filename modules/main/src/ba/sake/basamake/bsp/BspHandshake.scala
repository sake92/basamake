package ba.sake.basamake.bsp

import scala.jdk.CollectionConverters.*
import com.typesafe.scalalogging.StrictLogging
import ch.epfl.scala.bsp4j.*
import ba.sake.basamake.util.ProcessUtils

/** Union of BuildServer + ScalaBuildServer. lsp4j discovers all @JsonRequest
  * methods from both parent interfaces. Single proxy, both casts work. */
trait BasamakeBspServer extends BuildServer, ScalaBuildServer

final case class HandshakeResult(
    process: java.lang.Process,
    buildServer: BuildServer,
    targets: WorkspaceBuildTargetsResult,
    sources: SourcesResult,
    dependencySources: DependencySourcesResult,
    scalacOptions: ScalacOptionsResult
)

object BspHandshake extends StrictLogging {

  /** Spawn a BSP process, create a JSON-RPC proxy implementing both BuildServer and
    * ScalaBuildServer, run the full handshake (initialize → sources → dependency
    * sources → scalacOptions), and return the result. On failure the process is
    * killed before the exception propagates. */
  def execute(
      bspFile: BspConnectionSpec,
      eventSink: BspEventSink,
      timeoutSec: Long = 60
  ): HandshakeResult = {
    val pb = new java.lang.ProcessBuilder(bspFile.content.argv*)
    pb.directory(bspFile.workingDir.toIO)
    val logDir = bspFile.workingDir / ".basamake" / "logs"
    os.makeDir.all(logDir)
    pb.redirectError(java.lang.ProcessBuilder.Redirect.appendTo((logDir / s"bsp-${bspFile.content.name}.log").toIO))

    val process = pb.start()
    logger.info(s"BSP process spawned for ${bspFile.path} (pid ${process.pid()})")

    try {
      val buildClient = BasamakeBuildClient(eventSink)

      val launcher =
        new org.eclipse.lsp4j.jsonrpc.Launcher.Builder[BasamakeBspServer]()
          .setRemoteInterface(classOf[BasamakeBspServer])
          .setLocalService(buildClient)
          .setInput(process.getInputStream)
          .setOutput(process.getOutputStream)
          .create()
      val remoteProxy = launcher.getRemoteProxy
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
      remoteProxy.buildInitialize(initParams).get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      logger.debug("buildInitialize OK")

      remoteProxy.onBuildInitialized()
      logger.debug("onBuildInitialized sent")

      logger.debug("Requesting workspaceBuildTargets...")
      val targetsResult = remoteProxy.workspaceBuildTargets().get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      val targetIds = targetsResult.getTargets.asScala.map(_.getId).toList
      logger.debug(s"Found ${targetIds.size} build targets: ${targetIds.map(_.getUri).mkString(", ")}")

      logger.debug("Requesting buildTargetSources...")
      val sourcesParams = new SourcesParams(targetIds.asJava)
      val sourcesFuture = remoteProxy.buildTargetSources(sourcesParams)

      logger.debug("Requesting buildTargetDependencySources...")
      val dependencySourcesParams = new DependencySourcesParams(targetIds.asJava)
      val depSourcesFuture = remoteProxy.buildTargetDependencySources(dependencySourcesParams)

      val sourcesResult = sourcesFuture.get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      logger.debug("buildTargetSources OK")

      val dependencySourcesResult = depSourcesFuture.get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      logger.debug("buildTargetDependencySources OK")

      // Query scalacOptions for semanticdb target dirs — best-effort; non-Scala
      // BSP servers may not support this. Fall back to empty result.
      logger.debug("Requesting buildTargetScalacOptions...")
      val scalacOptionsResult = try {
        remoteProxy.buildTargetScalacOptions(new ScalacOptionsParams(targetIds.asJava))
          .get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      } catch {
        case e: Exception =>
          logger.debug(s"buildTargetScalacOptions failed (${e.getMessage}), continuing without scalacOptions")
          new ScalacOptionsResult(java.util.Collections.emptyList())
      }
      logger.debug("buildTargetScalacOptions OK")

      HandshakeResult(process, remoteProxy, targetsResult, sourcesResult, dependencySourcesResult, scalacOptionsResult)
    } catch {
      case e: Exception =>
        val signaled = ProcessUtils.terminateProcessTree(process)
        logger.warn(s"Handshake failed, killed process ${process.pid()} (signaled $signaled nodes)", e)
        throw e
    }
  }
}
