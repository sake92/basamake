package ba.sake.basamake.bsp

import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, CopyOnWriteArrayList, Executors, TimeUnit}
import java.net.URI
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.*
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.util.{ProcessUtils, ScalacOptionsUtils, UriUtils}
import ba.sake.basamake.index.indexing.SemanticdbDirs
import ba.sake.tupson.{given, *}

/** One BSP connection: process + liveness.
  *
  * Concurrency: spawnLock (ReentrantLock — virtual threads park, not pin)
  * serializes spawnAndHandshake + killTree (ping-failure recovery).
  * Volatile spawning flag lets fast-path callers detect an in-progress spawn and queue/return
  * without blocking. Pending compiles that arrive during spawn are queued in a CopyOnWriteArrayList
  * with deduplication (addIfAbsent) and drained after spawn succeeds. On spawn failure the
  * queue is cleared; the next user action will attempt a fresh spawn.
  *
  * Compiles are DEBOUNCED per target: `requestCompile` schedules one compile per target at
  * most, 500 ms after the first request; further requests for the same target within the
  * window are coalesced, and a request arriving while a compile runs yields exactly ONE
  * follow-up. The single-thread executor serializes all compiles on this connection. */
class BspConnection (
    val spec: BspConnectionSpec,
    spawnFn: () => HandshakeResult,
    killTreeFn: java.lang.Process => Unit,
    events: BspEvents,
    debounceMs: Long
) extends StrictLogging {

  @volatile private var process: Option[java.lang.Process] = None
  @volatile private var buildServer: Option[BuildServer] = None
  @volatile private var alive = false
  @volatile var inverseSourcesUnsupported = false


  @volatile private var sourceRootDirByTarget: Map[BuildTargetIdentifier, os.Path] = Map.empty
  /** target → source dirs (from handshake SourcesResult). Used by selectTargets. */
  @volatile private var sourceDirsByTarget: Map[BuildTargetIdentifier, List[String]] = Map.empty
  @volatile private var classDirectoryByTarget: Map[BuildTargetIdentifier, os.Path] = Map.empty
  /** target → semanticdb target dir (from handshake ScalacOptionsResult). */
  @volatile private var semanticdbDirByTarget: Map[BuildTargetIdentifier, os.Path] = Map.empty
  /** target → dependency source jars (from handshake DependencySourcesResult). */
  @volatile private var dependencySourcesByTarget: Map[BuildTargetIdentifier, List[os.Path]] = Map.empty

  private val spawnLock = new java.util.concurrent.locks.ReentrantLock()
  /** True while spawnAndHandshake is in progress. Volatile for fast-path checks. */
  @volatile private var spawning = false
  /** Compile target IDs that arrived during spawn. Dedup via addIfAbsent. */
  private val pendingCompileTargetIds = new CopyOnWriteArrayList[BuildTargetIdentifier]()
  /** Targets with a scheduled-but-not-started compile — the debounce/coalesce set. */
  private val pendingCompileTargets = new ConcurrentHashMap[BuildTargetIdentifier, java.lang.Boolean]()
  /** Serializes compiles on this connection (sbt can only run one build at a time). */
  private val compileExecutor = Executors.newSingleThreadScheduledExecutor((r: Runnable) => {
    val t = new Thread(r, "basamake-bsp-compile")
    t.setDaemon(true)
    t
  })
  
  private val PingTimeoutSec = 3L
  private val ShutdownTimeoutSec = 2L

  def ensureConnected(): Unit = {
    if (alive) return
    if (spawning) return          // another caller is spawning; any intent is already queued
    spawnLock.lock()
    try {
      if (alive) return           // re-check after lock acquire
      if (spawning) return        // another thread started spawn between our check and lock
      spawning = true
      try {
        events.onConnectionStarted(spec)
        spawnAndHandshake()
        process.foreach(_.onExit().thenRun(() => alive = false))
        alive = true
        events.onConnectionSucceeded(spec, sourceDirsByTarget.size)
        // Index catch-up: push ALL targets' semanticdb dirs to the index right after
        // handshake, so pre-existing semanticdb output (e.g. from earlier builds) is
        // paired without waiting for each target to be compiled on demand.
        try onAfterCompile(semanticdbDirByTarget.keySet.toList)
        catch { case e: Exception => logger.warn(s"Index catch-up failed: ${e.getMessage}", e) }
        // Dependency source jars are known right after the handshake — notify the
        // dependency index so it can cache them in the background.
        notifyDependencySources()
      } catch {
        case e: Exception =>
          pendingCompileTargetIds.clear()   // discard queued work
          events.onConnectionFailed(spec, e.getMessage)
          throw e
      } finally {
        spawning = false
      }
    } finally {
      spawnLock.unlock()
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
      buildServer.get.workspaceBuildTargets().get(PingTimeoutSec, TimeUnit.SECONDS)
    } catch {
      case e: Exception =>
        // Kill ONLY on a real error (stream closed) or when the process is dead.
        // A live-but-unresponsive process is usually busy compiling — killing it
        // destroys a healthy build server and forces a slow respawn for nothing.
        val streamClosed = BspConnection.isStreamClosed(e)
        val processAlive = process.exists(_.isAlive)
        if (streamClosed || !processAlive) {
          logger.warn(s"ping failed, process dead or stream closed (${e.getMessage}) — killing and respawning")
          spawnLock.lock()
          try { killTree(); alive = false }
          finally { spawnLock.unlock() }
          if (!spawning) ensureConnected()
        } else {
          logger.debug(s"ping failed but process alive (${e.getMessage}) — keeping connection, server may be busy")
        }
    }
  }

  /** Request a compile for the target(s) owning `uri` — debounced and coalesced
    * per target: at most one pending + one running compile per target, so a burst
    * of didOpen/didSave/watcher events collapses into a single build. */
  def requestCompile(uri: String): Unit = {
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
    scheduleCompiles(targetIds)
  }

  private def scheduleCompiles(targetIds: List[BuildTargetIdentifier]): Unit = {
    targetIds.foreach { tid =>
      if (pendingCompileTargets.putIfAbsent(tid, java.lang.Boolean.TRUE) == null) {
        logger.info(s"Compile scheduled (debounced): ${tid.getUri} in ${debounceMs}ms")
        compileExecutor.schedule(new Runnable {
          override def run(): Unit = {
            // removed BEFORE compiling, so a poke during the in-flight compile
            // re-schedules exactly one follow-up (and further pokes coalesce into it)
            pendingCompileTargets.remove(tid)
            if (!alive) {
              // connection died between schedule and fire — back to the spawn queue
              pendingCompileTargetIds.addIfAbsent(tid)
              if (!spawning) ensureConnected()
              return
            }
            compileTargets(List(tid))
          }
        }, debounceMs, TimeUnit.MILLISECONDS)
      }
    }
  }

  private def compileTargets(targetIds: List[BuildTargetIdentifier]): Unit = {
    if (targetIds.nonEmpty) {
      val startTime = System.currentTimeMillis()
      val idsStr = targetIds.map(_.getUri).mkString(", ")
      logger.info(s"Compile start: $idsStr")
      try {
        val result = buildServer.get.buildTargetCompile(new CompileParams(targetIds.asJava))
          .get(spec.compileTimeoutSec, TimeUnit.SECONDS)
        val took = System.currentTimeMillis() - startTime
        logger.info(s"Compile finished: $idsStr — ${result.getStatusCode} in ${took}ms")
        if result.getStatusCode == StatusCode.OK || hasBestEffortFlag(targetIds) then
          onAfterCompile(targetIds)
      } catch {
        case e: Exception => 
          val took = System.currentTimeMillis() - startTime
          logger.error(s"compile failed for $idsStr after ${took}ms", e)
          if hasBestEffortFlag(targetIds) then
            onAfterCompile(targetIds)
      }
    }
  }

  def shutdown(): Unit = {
    spawnLock.lock()
    try {
      alive = false
      spawning = false
      pendingCompileTargetIds.clear()
      pendingCompileTargets.clear()
      compileExecutor.shutdownNow()
      tryGracefulShutdown()
      killTree()
      // process/buildServer are dead — drop the references
      process = None
      buildServer = None
    } finally {
      spawnLock.unlock()
    }
  }

  private def spawnAndHandshake(): Unit = {
    val result = spawnFn()
    process = Some(result.process)
    buildServer = Some(result.buildServer)
    sourceRootDirByTarget = BspConnection.sourceRootDirByTarget(result.scalacOptions, spec.workingDir)
    sourceDirsByTarget = BspConnection.extractTargetSourceDirs(result.sources)
    classDirectoryByTarget = BspConnection.extractTargetClassDir(result.scalacOptions)
    semanticdbDirByTarget = BspConnection.extractTargetSemanticdbDir(result.scalacOptions, classDirectoryByTarget)
    // Seed from the deps persisted in this connection's data.json (previous
    // sessions), then merge the fresh handshake result — servers intermittently
    // return empty DependencySourcesResult, which must never wipe known deps.
    val freshDeps = BspConnection.extractTargetDependencySources(result.dependencySources)
    dependencySourcesByTarget = BspConnection.mergeDeps(loadPersistedDependencySources(), freshDeps)
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
    if (roots.nonEmpty) {
      logger.info(s"Compile done → forwarding ${roots.size} semanticdb root(s) to index: ${roots.map(r => r.semanticdbDir.toString).mkString(", ")}")
      events.onAfterCompile(roots)
    }
    // Persist BSP metadata for faster startup next time
    writeTargetData()
  }

  /** Fires the dependency-source hook once per connection (after handshake).
    * Per-target map — the receiver registers targets lazily and indexes nothing eagerly. */
  private def notifyDependencySources(): Unit = {
    if (dependencySourcesByTarget.nonEmpty) events.onDependencySources(dependencySourcesByTarget)
  }

  /** Dependency source jars for the BSP target owning `uri` (source-root match,
    * no RPC — safe to call synchronously from LSP request handlers). Empty when
    * the connection isn't alive yet (caller falls back to data.json warm data)
    * or when no target owns the uri — a file outside every source root has NO
    * authoritative dependency set, and we never guess. */
  def dependencySourcesFor(uri: String): List[os.Path] = {
    if (!alive) return Nil
    val tids = BspConnection.targetIdsForUri(uri, sourceDirsByTarget)
    tids.flatMap(tid => dependencySourcesByTarget.getOrElse(tid, Nil)).distinct
  }

  /** Writes .basamake/bsp/<name>_<hash>/data.json with target metadata
    * (source dirs + semanticdb dirs) for fast WorkspaceIndex startup.
    * Defensive: a target missing from scalacOptions (common before its first
    * compile) falls back to defaults instead of throwing away persistence. */
  private def writeTargetData(): Unit = {
    try {
      val dirName = BspConnectionSpec.dirName(spec)
      val dataDir = spec.workspaceRoot / ".basamake" / "bsp" / dirName
      os.makeDir.all(dataDir)
      val targetInfos = (sourceDirsByTarget.keySet ++ semanticdbDirByTarget.keySet ++ dependencySourcesByTarget.keySet).toList
        .map { tid =>
          BspTargetInfo(
            id = tid.getUri,
            sourceRootDir = sourceRootDirByTarget.getOrElse(tid, spec.workspaceRoot),
          //  sourceDirs = sourceDirsByTarget.getOrElse(tid, Nil),
            semanticdbDir = semanticdbDirByTarget.getOrElse(tid, classDirectoryByTarget.getOrElse(tid, spec.workspaceRoot)),
            dependencySources = dependencySourcesByTarget.getOrElse(tid, Nil).map(_.toString)
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

  /** Dependency sources persisted in this connection's own data.json (previous
    * sessions). Seeds the handshake merge so an intermittently-empty
    * DependencySourcesResult can never wipe deps we already know. */
  private def loadPersistedDependencySources(): Map[BuildTargetIdentifier, List[os.Path]] = {
    try {
      val dirName = BspConnectionSpec.dirName(spec)
      val dataFile = spec.workspaceRoot / ".basamake" / "bsp" / dirName / "data.json"
      if (!os.exists(dataFile)) return Map.empty
      val data = os.read(dataFile).parseJson[BspTargetData]
      data.targets.flatMap { t =>
        val deps = t.dependencySources.flatMap(s => try Some(os.Path(s)) catch { case _: Exception => None })
        if (deps.nonEmpty) Some(new BuildTargetIdentifier(t.id) -> deps) else None
      }.toMap
    } catch {
      case e: Exception =>
        logger.debug(s"Failed to load persisted dependency sources: ${e.getMessage}")
        Map.empty
    }
  }

  /** Re-request dependency sources for `tids` and merge (non-empty only — an
    * empty result keeps the existing deps). Re-fires the dep hook and persists
    * data.json when anything actually changed. */
  private[bsp] def refreshDependencySources(tids: List[BuildTargetIdentifier]): Unit = {
    if (buildServer.isEmpty) return
    try {
      val result = buildServer.get.buildTargetDependencySources(new DependencySourcesParams(tids.asJava))
        .get(5, TimeUnit.SECONDS)
      val fresh = BspConnection.extractTargetDependencySources(result)
      val merged = BspConnection.mergeDeps(dependencySourcesByTarget, fresh)
      if (merged != dependencySourcesByTarget) {
        dependencySourcesByTarget = merged
        logger.info(s"Refreshed dependency sources for ${fresh.size} target(s)")
        notifyDependencySources()
        writeTargetData()
      }
    } catch {
      case e: Exception => logger.debug(s"dependencySources refresh failed: ${e.getMessage}")
    }
  }

  private def killTree(): Unit =
    process.foreach(p => if (p.isAlive) killTreeFn(p))

  private def tryGracefulShutdown(): Unit =
    buildServer.foreach { bs =>
      try {
        bs.buildShutdown().get(ShutdownTimeoutSec, TimeUnit.SECONDS)
        bs.onBuildExit()
      } catch { case _: Exception => () }
    }

  private def hasBestEffortFlag(targetIds: List[BuildTargetIdentifier]): Boolean =
    try buildServer match {
      case Some(scalaServer: ScalaBuildServer) =>
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
    if (buildServer.isEmpty || inverseSourcesUnsupported) return Nil
    try {
      val result = buildServer.get.buildTargetInverseSources(
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
    process.foreach(_ => alive = false)
  private[bsp] def setSpawningFlagForTesting(v: Boolean): Unit = { spawning = v }
  private[bsp] def pendingCompileTargetIdsForTesting: Vector[BuildTargetIdentifier] = {
    import scala.jdk.CollectionConverters.*
    pendingCompileTargetIds.asScala.toVector
  }
}

object BspConnection {
  /** Production factory. The spawn closure captures only `events` and the spec
    * — no instance self-reference, no wrapper (connection-scoped events carry
    * the connection id themselves). */
  def apply(spec: BspConnectionSpec, events: BspEvents): BspConnection =
    new BspConnection(
      spec,
      () => BspHandshake.execute(spec, events, BspConnectionId(spec.path.toString)),
      p => ProcessUtils.terminateProcessTree(p),
      events,
      debounceMs = 500
    )

  /** Merge fresh dependency sources into old. A fresh EMPTY list never replaces an
    * existing non-empty one (servers intermittently return empty results — known
    * deps stay authoritative); fresh non-empty lists win. */
  private[bsp] def mergeDeps(
      oldDeps: Map[BuildTargetIdentifier, List[os.Path]],
      freshDeps: Map[BuildTargetIdentifier, List[os.Path]]
  ): Map[BuildTargetIdentifier, List[os.Path]] =
    freshDeps.foldLeft(oldDeps) { case (acc, (tid, paths)) =>
      if (paths.nonEmpty) acc.updated(tid, paths) else acc
    }

  /** Target ids of a DidChangeBuildTarget event, excluding DELETED ones
    * (deleted targets have nothing worth re-asking about). */
  private[bsp] def changedTargetIds(params: DidChangeBuildTarget): List[BuildTargetIdentifier] =
    Option(params.getChanges).toList.flatMap(_.asScala)
      .filterNot(c => c.getKind == BuildTargetEventKind.DELETED)
      .map(_.getTarget)
      .distinct

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

  /** target → dependency source jars (absolute paths), from the handshake
    * DependencySourcesResult. Lenient: non-file URIs / unparseable entries are skipped. */
  private[bsp] def extractTargetDependencySources(result: DependencySourcesResult): Map[BuildTargetIdentifier, List[os.Path]] = {
    Option(result.getItems).toList.flatMap(_.asScala).map { item =>
      val paths = Option(item.getSources).toList.flatMap(_.asScala).flatMap(BspConnection.toSourcePath)
      item.getTarget -> paths
    }.toMap
  }

  /** Converts a dependency-source URI (possibly a `jar:` URI) to an absolute path. */
  private[bsp] def toSourcePath(uri: String): Option[os.Path] = {
    try {
      val cleaned = if (uri.startsWith("jar:")) uri.stripPrefix("jar:") else uri
      val noEntry = cleaned.takeWhile(_ != '!') // drop any "!/entry" suffix
      Some(os.Path(URI.create(noEntry)))
    } catch {
      case _: Exception => None
    }
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
