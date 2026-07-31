package ba.sake.basamake.bsp

import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.*
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.util.{ProcessUtils, ScalacOptionsUtils, UriUtils}

/** One BSP connection: process + liveness.
  *
  * Concurrency: one `Object` lock per connection serializes ensureConnected/poke/compile.
  * JVM/Scala `synchronized` is reentrant — `compile` calls `poke` under the same lock
  * without self-deadlock. `process.onExit().thenRun(() => alive = false)` is the only
  * async piece; its callback never re-enters the lock.
  *
  * Storm protection: MaxRespawnPerCall = 1 (one respawn per user poke, never a hot loop);
  * after MaxConsecutiveFails=3 rapid fails within CooldownMs=5000, ensureConnected
  * throws BspUnavailable (swallowed by BspManager.poke) instead of hammering BSP build tool. */
class BspConnection private (
    val spec: BspConnectionSpec,
    spawnFn: () => HandshakeResult,
    killTreeFn: java.lang.Process => Unit,
    eventSink: BspEventSink
) extends StrictLogging {

  @volatile private var process: java.lang.Process = null
  @volatile private var buildServer: BuildServer = null
  @volatile private var alive = false
  @volatile var inverseSourcesUnsupported = false

  /** target → source dirs (from handshake SourcesResult). Used by selectTargets. */
  @volatile private var sourceDirsByTarget: Map[BuildTargetIdentifier, List[String]] = Map.empty

  private val lock = new Object
  private val consecutiveFails = new AtomicInteger(0)
  private val lastFailMs = new AtomicLong(0)

  private val MaxRespawnPerCall = 1
  private val CooldownMs = 5_000L
  private val MaxConsecutiveFails = 3
  private val PingTimeoutSec = 2L
  private val ShutdownTimeoutSec = 2L

  def ensureConnected(): Unit = lock.synchronized {
    if (alive) return
    val now = System.currentTimeMillis()
    if (consecutiveFails.get() >= MaxConsecutiveFails &&
        (now - lastFailMs.get()) < CooldownMs) {
      throw BspUnavailable("in cooldown after repeated failures")
    }
    spawnAndHandshake()
    process.onExit().thenRun(() => alive = false)
    alive = true
    consecutiveFails.set(0)
  }

  def poke(): Unit = lock.synchronized {
    if (!alive) { ensureConnected(); return }
    try {
      buildServer.workspaceBuildTargets().get(PingTimeoutSec, TimeUnit.SECONDS)
    } catch {
      case e: Exception =>
        logger.warn(s"ping failed, killing process: ${e.getMessage}")
        killTree(); alive = false
        ensureConnected()                // one respawn attempt, errors bubble
    }
  }

  def compile(uri: String): Unit = lock.synchronized {
    poke()                              // liveness first; reentrant on same lock
    val targetIds = selectTargets(uri)
    if (targetIds.nonEmpty) {
      try {
        val result = buildServer.buildTargetCompile(new CompileParams(targetIds.asJava))
          .get(spec.compileTimeoutSec, TimeUnit.SECONDS)
        val ok = result.getStatusCode == StatusCode.OK || hasBestEffortFlag(targetIds)
        if (ok) onAfterCompile(targetIds)
      } catch {
        case e: Exception => logger.error(s"compile failed for $uri", e)
      }
    }
  }

  def shutdown(): Unit = lock.synchronized {
    alive = false
    if (buildServer != null) tryGracefulShutdown()
    killTree()
  }

  /** Flattened source dirs from the handshake — passed to BspManager.onAfterCompile
    * which forwards to WorkspaceIndex.invalidate. */
  def sourceDirs: List[String] = sourceDirsByTarget.values.flatten.toList

  private def spawnAndHandshake(): Unit = {
    try {
      val result = spawnFn()
      process = result.process
      buildServer = result.buildServer
      sourceDirsByTarget = BspConnection.extractTargetSourceDirs(result.sources)
    } catch {
      case e: Exception =>
        consecutiveFails.incrementAndGet()
        lastFailMs.set(System.currentTimeMillis())
        throw e
    }
  }

  private def onAfterCompile(targetIds: List[BuildTargetIdentifier]): Unit = {
    val dirs = sourceDirs
    if (dirs.nonEmpty) eventSink match {
      case s: BspAfterCompileSink => s.onAfterCompile(dirs)
      case _ => ()  // no-op if the sink does not implement the after-compile hook
    }
  }

  private def killTree(): Unit =
    if (process != null && process.isAlive) killTreeFn(process)

  private def tryGracefulShutdown(): Unit =
    try {
      buildServer.buildShutdown().get(ShutdownTimeoutSec, TimeUnit.SECONDS)
      buildServer.onBuildExit()
    } catch { case _: Exception => () }

  private def hasBestEffortFlag(targetIds: List[BuildTargetIdentifier]): Boolean =
    try buildServer match {
      case scalaServer: ScalaBuildServer =>
        val result = scalaServer.buildTargetScalacOptions(new ScalacOptionsParams(targetIds.asJava))
          .get(2, TimeUnit.SECONDS)
        Option(result.getItems).toList.flatMap(_.asScala).exists { item =>
          ScalacOptionsUtils.hasBestEffortFlag(Option(item.getOptions).toList.flatMap(_.asScala))
        }
      case _ => false
    } catch { case _: Exception => false }

  private def selectTargets(uri: String): List[BuildTargetIdentifier] = {
    // 1. inverseSources (cached unsupported flag)
    val inverse = tryInverseSources(uri)
    if (inverse.nonEmpty) return inverse
    // 2. source-root match
    val rootMatches = BspConnection.targetIdsForUri(uri, sourceDirsByTarget)
    if (rootMatches.nonEmpty) rootMatches
    // 3. all targets (last resort)
    else if (sourceDirsByTarget.keys.nonEmpty) sourceDirsByTarget.keys.toList
    else Nil
  }

  private def tryInverseSources(uri: String): List[BuildTargetIdentifier] = {
    if (buildServer == null || inverseSourcesUnsupported) return Nil
    try {
      val result = buildServer.buildTargetInverseSources(
        new InverseSourcesParams(new TextDocumentIdentifier(uri))
      ).get(2, TimeUnit.SECONDS)
      result.getTargets.asScala.toList
    } catch {
      case e: Exception =>
        if (!inverseSourcesUnsupported) {
          inverseSourcesUnsupported = true
          logger.info(s"inverseSources unsupported by ${spec.content.name} (${e.getMessage}) — caching, will skip")
        }
        Nil
    }
  }

  // ---- test hooks (package-private) ----
  private[bsp] def aliveForTesting: Boolean = alive
  private[bsp] def simulateProcessExitForTesting(): Unit =
    if (process != null) alive = false
}

object BspConnection {
  def apply(spec: BspConnectionSpec, eventSink: BspEventSink): BspConnection =
    new BspConnection(
      spec,
      () => BspHandshake.execute(spec, eventSink),
      p => ProcessUtils.terminateProcessTree(p),
      eventSink
    )

  /** Test factory: inject spawn + killTree. */
  private[bsp] def forTesting(
      spec: BspConnectionSpec,
      spawn: () => HandshakeResult,
      killTree: java.lang.Process => Unit,
      eventSink: BspEventSink
  ): BspConnection = new BspConnection(spec, spawn, killTree, eventSink)

  private[bsp] def extractTargetSourceDirs(sources: SourcesResult): Map[BuildTargetIdentifier, List[String]] = {
    def ensureTrailingSlash(uri: String): String = if (uri.endsWith("/")) uri else s"$uri/"
    sources.getItems.asScala.toList.map { item =>
      val roots = Option(item.getSources).map(_.asScala.toList).getOrElse(Nil)
        .filterNot(_.getGenerated)
        .collect {
          case si if si.getKind == SourceItemKind.DIRECTORY => ensureTrailingSlash(si.getUri)
          case si if si.getKind == SourceItemKind.FILE      => si.getUri
        }
      item.getTarget -> roots
    }.toMap
  }

  private[bsp] def targetIdsForUri(
      uri: String, targetToSourceRoots: Map[BuildTargetIdentifier, List[String]]
  ): List[BuildTargetIdentifier] = {
    def inSourceRoot(u: String, root: String): Boolean = {
      val nu = UriUtils.normalizeUri(u)
      val nr = UriUtils.normalizeUri(root)
      if (nr.endsWith("/")) nu.startsWith(nr)
      else nu == nr || nu.startsWith(s"$nr/")
    }
    targetToSourceRoots.toList.collect {
      case (tid, roots) if roots.exists(inSourceRoot(uri, _)) => tid
    }
  }
}
