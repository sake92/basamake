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
      events: BspEvents,
      connId: BspConnectionId
  ): HandshakeResult = {
    val timeoutSec = bspFile.handshakeTimeoutSec
    val pb = new java.lang.ProcessBuilder(bspFile.content.argv*)
    pb.directory(bspFile.workingDir.toIO)
    val dirName = BspConnectionSpec.dirName(bspFile)
    val logDir = bspFile.workspaceRoot / ".basamake" / "bsp" / dirName
    os.makeDir.all(logDir)
    pb.redirectError(java.lang.ProcessBuilder.Redirect.appendTo((logDir / "stderr.log").toIO))

    val process = pb.start()
    logger.info(s"BSP process spawned for ${bspFile.path} (pid ${process.pid()})")

    // Brief pause: give the BSP process time to start its internal server
    // (e.g. deder-bsp starts a background Deder server, needs a moment).
    Thread.sleep(200)

    try {
      val buildClient = BasamakeBuildClient(events, connId)

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

      HandshakeResult(process, remoteProxy, sourcesResult, dependencySourcesResult, scalacOptionsResult)
    } catch {
      case e: Exception =>
        val signaled = ProcessUtils.terminateProcessTree(process)
        logger.warn(s"Handshake failed, killed process ${process.pid()} (signaled $signaled nodes)", e)
        val bspFileRel = try bspFile.path.relativeTo(bspFile.workspaceRoot).toString
          catch { case _: Exception => bspFile.path.toString }
        throw new RuntimeException(BspHandshake.describeHandshakeFailure(e, timeoutSec, logDir, bspFileRel), e)
    }
  }

  /** Human-readable handshake failure. Timeouts get a descriptive message pointing
    * at the build server's stderr log and the config override — a bare
    * TimeoutException has a null message which would otherwise surface as
    * "Failed to connect to X: null". */
  private[bsp] def describeHandshakeFailure(e: Exception, timeoutSec: Long, logDir: os.Path, bspFileRel: String): String = {
    val isTimeout = e match {
      case _: java.util.concurrent.TimeoutException => true
      case ee: java.util.concurrent.ExecutionException =>
        ee.getCause.isInstanceOf[java.util.concurrent.TimeoutException]
      case _ => false
    }
    if (isTimeout)
      s"BSP handshake timed out after ${timeoutSec}s — the build server is slow to start " +
        s"(see ${logDir / "stderr.log"}). Increase the timeout in .basamake/config.json, " +
        s"""e.g. {"bspOverrides": [{"bspFile": "$bspFileRel", "handshakeTimeoutSec": 300}]}"""
    else
      Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
  }
}
