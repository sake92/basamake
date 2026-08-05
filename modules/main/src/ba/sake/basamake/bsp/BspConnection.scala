package ba.sake.basamake.bsp

import java.util.concurrent.{CompletableFuture, CopyOnWriteArrayList, TimeUnit}
import java.net.URI
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.*
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.util.{ProcessUtils, ScalacOptionsUtils, UriUtils}
import ba.sake.basamake.navigation.indexing.SemanticdbDirs

/** One BSP connection: process + liveness.
  *
  * Concurrency: spawnLock serializes spawnAndHandshake + killTree (ping-failure recovery).
  * Volatile spawning flag lets fast-path callers detect an in-progress spawn and queue/return
  * without blocking. poke/compile on an alive connection may run concurrently (BSP server
  * handles it). Pending compiles that arrive during spawn are queued in a CopyOnWriteArrayList
  * with deduplication (addIfAbsent) and drained after spawn succeeds. On spawn failure the
  * queue is cleared; the next user action will attempt a fresh spawn. */
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
  /** True after the first successful compile on this connection. Reset on respawn. */
  @volatile var compiledOnce = false


  @volatile private var sourceRootDirByTarget: Map[BuildTargetIdentifier, os.Path] = Map.empty
  /** target → source dirs (from handshake SourcesResult). Used by selectTargets. */
  @volatile private var sourceDirsByTarget: Map[BuildTargetIdentifier, List[String]] = Map.empty
  @volatile private var classDirectoryByTarget: Map[BuildTargetIdentifier, os.Path] = Map.empty
  /** target → semanticdb target dir (from handshake ScalacOptionsResult). */
  @volatile private var semanticdbDirByTarget: Map[BuildTargetIdentifier, os.Path] = Map.empty

  private val spawnLock = new Object
  /** True while spawnAndHandshake is in progress. Volatile for fast-path checks. */
  @volatile private var spawning = false
  /** Compile target IDs that arrived during spawn. Dedup via addIfAbsent. */
  private val pendingCompileTargetIds = new CopyOnWriteArrayList[BuildTargetIdentifier]()
  
  private val PingTimeoutSec = 3L
  private val ShutdownTimeoutSec = 2L

  def ensureConnected(): Unit = {
    if (alive) return
    if (spawning) return          // another caller is spawning; any intent is already queued
    spawnLock.synchronized {
      if (alive) return           // re-check after lock acquire
      if (spawning) return        // another thread started spawn between our check and lock
      spawning = true
      try {
        eventSink.onConnectionStarted(spec)
        spawnAndHandshake()
        process.onExit().thenRun(() => alive = false)
        alive = true
        compiledOnce = false
        eventSink.onConnectionSucceeded(spec, sourceDirsByTarget.size)
      } catch {
        case e: Exception =>
          pendingCompileTargetIds.clear()   // discard queued work
          eventSink.onConnectionFailed(spec, e.getMessage)
          throw e
      } finally {
        spawning = false
      }
    }
    drainPendingCompiles()         // outside spawnLock — BSP is alive now
  }

  def poke(): Unit = {
    if (!alive) {
      if (spawning) return         // spawn in progress → no-op
      ensureConnected()
      return
    }
    try {
      buildServer.workspaceBuildTargets().get(PingTimeoutSec, TimeUnit.SECONDS)
    } catch {
      case e: Exception =>
        // Kill ONLY on a real error (stream closed) or when the process is dead.
        // A live-but-unresponsive process is usually busy compiling — killing it
        // destroys a healthy build server and forces a slow respawn for nothing.
        val streamClosed = BspConnection.isStreamClosed(e)
        val processAlive = process != null && process.isAlive
        if (streamClosed || !processAlive) {
          logger.warn(s"ping failed, process dead or stream closed (${e.getMessage}) — killing and respawning")
          spawnLock.synchronized { killTree(); alive = false }
          if (!spawning) ensureConnected()
        } else {
          logger.debug(s"ping failed but process alive (${e.getMessage}) — keeping connection, server may be busy")
        }
    }
  }

  def compile(uri: String): Unit = {
    if (!alive) {
      if (spawning) {
        val tids = selectTargets(uri)
        for (tid <- tids) pendingCompileTargetIds.addIfAbsent(tid)
        return
      }
      ensureConnected()
      if (!alive) return                 // spawn failed
    }
    poke()                                // liveness check
    val targetIds = selectTargets(uri)
    compileTargets(targetIds)
  }

  private def compileTargets(targetIds: List[BuildTargetIdentifier]): Unit = {
    if (targetIds.nonEmpty) {
      try {
        val result = buildServer.buildTargetCompile(new CompileParams(targetIds.asJava))
          .get(spec.compileTimeoutSec, TimeUnit.SECONDS)
        if result.getStatusCode == StatusCode.OK || hasBestEffortFlag(targetIds) then
          onAfterCompile(targetIds)
      } catch {
        case e: Exception => 
          logger.error(s"compile failed for ${targetIds.map(_.getUri).mkString(", ")}", e)
          if hasBestEffortFlag(targetIds) then
            onAfterCompile(targetIds)
      } finally {
        compiledOnce = true
      }
    }
  }

  def shutdown(): Unit = spawnLock.synchronized {
    alive = false
    spawning = false
    pendingCompileTargetIds.clear()
    if (buildServer != null) tryGracefulShutdown()
    killTree()
  }

  private def spawnAndHandshake(): Unit = {
    val result = spawnFn()
    process = result.process
    buildServer = result.buildServer
    sourceRootDirByTarget = BspConnection.sourceRootDirByTarget(result.scalacOptions, spec.workingDir)
    sourceDirsByTarget = BspConnection.extractTargetSourceDirs(result.sources)
    classDirectoryByTarget = BspConnection.extractTargetClassDir(result.scalacOptions)
    semanticdbDirByTarget = BspConnection.extractTargetSemanticdbDir(result.scalacOptions, classDirectoryByTarget)
  }

  private def drainPendingCompiles(): Unit = {
    if (pendingCompileTargetIds.isEmpty) return
    val targetIds = {
      val list = new java.util.ArrayList(pendingCompileTargetIds)
      pendingCompileTargetIds.clear()
      list.asScala.toList
    }
    compileTargets(targetIds)
  }

  private def onAfterCompile(targetIds: List[BuildTargetIdentifier]): Unit = {
    val roots = for {
      tid <- targetIds
      semDir <- semanticdbDirByTarget.get(tid)
      srcRoot = sourceRootDirByTarget.getOrElse(tid, spec.workspaceRoot)
    } yield SemanticdbDirs(srcRoot, semDir)
    if (roots.nonEmpty) eventSink match {
      case s: BspAfterCompileSink => s.onAfterCompile(roots)
      case _ => ()
    }
    // Persist BSP metadata for faster startup next time
    writeTargetData()
  }

  /** Writes .basamake/bsp/<name>_<hash>/data.json with target metadata
    * (source dirs + semanticdb dirs) for fast WorkspaceIndex startup. */
  private def writeTargetData(): Unit = {
    try {
      val dirName = BspConnectionSpec.dirName(spec)
      val dataDir = spec.workspaceRoot / ".basamake" / "bsp" / dirName
      os.makeDir.all(dataDir)
      val targetInfos = (sourceDirsByTarget.keySet ++ semanticdbDirByTarget.keySet).toList
        .map { tid =>
          BspTargetInfo(
            id = tid.getUri,
            sourceRootDir = sourceRootDirByTarget(tid),
          //  sourceDirs = sourceDirsByTarget.getOrElse(tid, Nil),
            semanticdbDir = semanticdbDirByTarget(tid)
          )
        }
      val bspFileRel = try spec.path.relativeTo(spec.workspaceRoot).toString
        catch { case _: Exception => spec.path.toString }
      val data = BspTargetData(bspFile = bspFileRel, targets = targetInfos)
      os.write.over(dataDir / "data.json", ba.sake.tupson.toJson(data))
      logger.debug(s"Wrote BSP target data to $dataDir")
    } catch {
      case e: Exception => logger.warn(s"Failed to write BSP target data: ${e.getMessage}")
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
        if (org.eclipse.lsp4j.jsonrpc.JsonRpcException.indicatesStreamClosed(e)) {
          // process died — NOT a method-support issue, don't cache
          logger.debug(s"buildTargetInverseSources stream closed for $uri — process likely died")
        } else {
          val isMethodNotFound = e match {
            case ree: org.eclipse.lsp4j.jsonrpc.ResponseErrorException =>
              ree.getResponseError.getCode ==
                org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.MethodNotFound.getValue
            case _ => false
          }
          if (isMethodNotFound) {
            logger.debug(s"buildTargetInverseSources not supported by ${spec.content.name} — MethodNotFound")
          } else {
            logger.warn(s"buildTargetInverseSources failed for $uri: ${e.getMessage}")
          }
          if (!inverseSourcesUnsupported) {
            inverseSourcesUnsupported = true
            logger.info(s"inverseSources unsupported by ${spec.content.name} (${e.getMessage}) — caching, will skip")
          }
        }
        Nil
    }
  }

  // ---- test hooks (package-private) ----
  private[bsp] def aliveForTesting: Boolean = alive
  private[bsp] def simulateProcessExitForTesting(): Unit =
    if (process != null) alive = false
  private[bsp] def setSpawningFlagForTesting(v: Boolean): Unit = { spawning = v }
  private[bsp] def pendingCompileTargetIdsForTesting: Vector[BuildTargetIdentifier] = {
    import scala.jdk.CollectionConverters.*
    pendingCompileTargetIds.asScala.toVector
  }
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

  /** True when the exception (possibly ExecutionException-wrapped from future.get)
    * indicates the BSP stream is closed — i.e. a real error, not a busy server. */
  private[bsp] def isStreamClosed(e: Throwable): Boolean = {
    val unwrapped = e match {
      case ee: java.util.concurrent.ExecutionException if ee.getCause != null => ee.getCause
      case other => other
    }
    org.eclipse.lsp4j.jsonrpc.JsonRpcException.indicatesStreamClosed(unwrapped)
  }

  /** Per-target source root for SemanticDB URI resolution.
    * Chain: explicit `-sourceroot` flag → BSP working dir (the .bsp file's parent —
    * the project base for sbt, which does NOT pass `-sourceroot` at all).
    * Callers fall back to the workspace root for targets missing from the map.
    * Never falls back to os.pwd: the LSP process cwd is unrelated to the build layout. */
  private[bsp] def sourceRootDirByTarget(
      opts: ScalacOptionsResult,
      workingDir: os.Path
  ): Map[BuildTargetIdentifier, os.Path] = {
    Option(opts.getItems).toList.flatMap(_.asScala).map { item =>
      val target = item.getTarget
      val fromFlag = ScalacOptionsUtils.sourceRootDir(Option(item.getOptions).toList.flatMap(_.asScala))
      target -> fromFlag.getOrElse(workingDir)
    }.toMap
  }

  private[bsp] def extractTargetSourceDirs(sources: SourcesResult): Map[BuildTargetIdentifier, List[String]] = {
    def ensureTrailingSlash(uri: String): String = if (uri.endsWith("/")) uri else s"$uri/"
    def parentDirUri(uri: String): String = {
      val noTrail = if (uri.endsWith("/")) uri.stripSuffix("/") else uri
      val idx = noTrail.lastIndexOf('/')
      if (idx > 0) ensureTrailingSlash(noTrail.substring(0, idx))
      else ensureTrailingSlash(noTrail)
    }
    sources.getItems.asScala.toList.map { item =>
      val allDirs = Option(item.getSources).map(_.asScala.toList).getOrElse(Nil)
        .filterNot(_.getGenerated)
        .map {
          case si if si.getKind == SourceItemKind.DIRECTORY => ensureTrailingSlash(si.getUri)
          case si if si.getKind == SourceItemKind.FILE      => parentDirUri(si.getUri)
        }
        .distinct
      // Keep only top-level dirs (drop subdirs of other dirs in the list)
      val topLevel = allDirs.filterNot(d => allDirs.exists(other => other != d && d.startsWith(other)))
      item.getTarget -> topLevel
    }.toMap
  }

  private[bsp] def extractTargetClassDir(opts: ScalacOptionsResult): Map[BuildTargetIdentifier, os.Path] = {
    Option(opts.getItems).toList.flatMap(_.asScala).map { item =>
      val path = os.Path(URI.create(item.getClassDirectory))
      item.getTarget -> path
    }.toMap
  }

  private[bsp] def extractTargetSemanticdbDir(opts: ScalacOptionsResult, classDirectoryByTarget: Map[BuildTargetIdentifier, os.Path]): Map[BuildTargetIdentifier, os.Path] = {
    Option(opts.getItems).toList.flatMap(_.asScala).map { item =>
      val target = item.getTarget
      val path = ScalacOptionsUtils.semanticdbTargetPath(Option(item.getOptions).toList.flatMap(_.asScala))
      val fallback = classDirectoryByTarget(target) // fall back to class directory if no explicit semanticdb-target
      target -> path.getOrElse(fallback)
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
