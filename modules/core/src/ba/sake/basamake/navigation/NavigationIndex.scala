package ba.sake.basamake.navigation

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.{BuildServer, BuildTargetIdentifier, OutputPathsParams, OutputPathsResult, OutputPathItemKind, ScalacOptionsParams, ScalacOptionsResult, ScalaBuildServer}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{Location, Position, Range, SymbolInformation, SymbolKind}
import org.eclipse.lsp4j.jsonrpc.messages.Either

final case class SemanticdbOccurrence(symbol: String, range: Range, isDefinition: Boolean)

final case class SemanticdbFileSlice(
    sourceUri: String,
    occurrences: List[SemanticdbOccurrence],
    symbolDefinitions: Map[String, List[Location]],
    symbolReferences: Map[String, List[Location]],
    documentSymbols: List[Either[SymbolInformation, org.eclipse.lsp4j.DocumentSymbol]]
) {
  def symbolAt(position: Position): Option[String] = {
    val matchingOccs = occurrences.filter(occ => NavigationRangeUtils.contains(occ.range, position))
    if matchingOccs.isEmpty then None
    else {
      val smallest = matchingOccs.minBy { occ =>
        val range = occ.range
        val lineSpan = range.getEnd.getLine - range.getStart.getLine
        val charSpan = if lineSpan == 0 then
          range.getEnd.getCharacter - range.getStart.getCharacter
        else
          Int.MaxValue
        (lineSpan, charSpan)
      }
      Some(smallest.symbol)
    }
  }
}

object NavigationIndex {

  private[navigation] final case class TargetState(
      targetOrder: List[BuildTargetIdentifier] = Nil,
      workspaceSlicesByTarget: Map[BuildTargetIdentifier, Map[String, SemanticdbFileSlice]] = Map.empty,
      dependencySlicesByTarget: Map[BuildTargetIdentifier, List[SemanticdbFileSlice]] = Map.empty,
      // precomputed query views, rebuilt on every commit
      slicesByUri: Map[String, List[SemanticdbFileSlice]] = Map.empty,
      mergedDefinitions: Map[String, List[Location]] = Map.empty,
      mergedReferences: Map[String, List[Location]] = Map.empty,
      orderedWorkspace: List[SemanticdbFileSlice] = Nil,
      orderedDependency: List[SemanticdbFileSlice] = Nil
  )

  private[navigation] object TargetState {
    def build(
        targetId: BuildTargetIdentifier,
        workspaceSlices: Map[String, SemanticdbFileSlice],
        dependencySlices: List[SemanticdbFileSlice]
    ): TargetState = {
      val orderedWs = workspaceSlices.values.toList.sortBy(_.sourceUri)
      val allSlices = orderedWs ++ dependencySlices
      TargetState(
        targetOrder = List(targetId),
        workspaceSlicesByTarget = Map(targetId -> workspaceSlices),
        dependencySlicesByTarget = Map(targetId -> dependencySlices),
        slicesByUri = allSlices.groupBy(_.sourceUri),
        mergedDefinitions = mergeSymbolLocations(allSlices.flatMap(_.symbolDefinitions)),
        mergedReferences = mergeSymbolLocations(allSlices.flatMap(_.symbolReferences)),
        orderedWorkspace = orderedWs,
        orderedDependency = dependencySlices
      )
    }

    private def mergeSymbolLocations(
        entries: List[(String, List[Location])]
    ): Map[String, List[Location]] =
      entries.foldLeft(Map.empty[String, List[Location]]) {
        case (acc, (symbol, locations)) =>
          acc.updated(symbol, acc.getOrElse(symbol, Nil) ++ locations)
      }
  }
}

final class NavigationIndex(
    private val depSliceCache: DependencySliceCache = new DependencySliceCache()
) extends StrictLogging {

  import NavigationIndex.TargetState

  private val targetStates = mutable.Map.empty[BuildTargetIdentifier, TargetState]
  private val targetSemanticdbFlags = mutable.Map.empty[BuildTargetIdentifier, Boolean]
  private val targetBestEffortFlags = mutable.Map.empty[BuildTargetIdentifier, Boolean]
  private val workspaceIndexStates =
    mutable.Map.empty[BuildTargetIdentifier, SemanticdbIndexing.WorkspaceIndexState]

  /** Clears per-connection state. The dep slice cache is owned by BuildServerManager
    * and intentionally survives — it must not be cleared here. */
  def clear(): Unit = synchronized {
    targetStates.clear()
    workspaceIndexStates.clear()
  }

  private[navigation] def setTargetSlicesForTest(
      targetId: BuildTargetIdentifier,
      fileSlices: Map[String, SemanticdbFileSlice]
  ): Unit = synchronized {
    val currentDeps = targetStates.get(targetId).flatMap(_.dependencySlicesByTarget.get(targetId)).getOrElse(Nil)
    targetStates.update(targetId, TargetState.build(targetId, fileSlices, currentDeps))
  }

  private[navigation] def setTargetDependencySlicesForTest(
      targetId: BuildTargetIdentifier,
      slices: List[SemanticdbFileSlice]
  ): Unit = synchronized {
    val currentWs = targetStates.get(targetId).flatMap(_.workspaceSlicesByTarget.get(targetId)).getOrElse(Map.empty)
    targetStates.update(targetId, TargetState.build(targetId, currentWs, slices))
  }

  def refresh(
      workspaceRoot: os.Path,
      buildServer: BuildServer,
      targetIds: List[BuildTargetIdentifier],
      sourceRootsByTarget: Map[BuildTargetIdentifier, List[String]],
      dependencySourceUrisByTarget: Map[BuildTargetIdentifier, List[String]],
      openUris: Set[String] = Set.empty  // reserved for future two-phase commit
  ): Unit = {
    val buildTargetIds = targetIds.asJava

    // Issue both requests concurrently
    val outputPathsFuture = buildServer.buildTargetOutputPaths(new OutputPathsParams(buildTargetIds))
    val scalacOptionsFuture = buildServer match {
      case scalaBuild: ScalaBuildServer =>
        scalaBuild.buildTargetScalacOptions(new ScalacOptionsParams(buildTargetIds))
      case _ =>
        java.util.concurrent.CompletableFuture.completedFuture(
          new ScalacOptionsResult(java.util.Collections.emptyList())
        )
    }

    var optionRequestsFailed = false
    val outputRootsByTarget = try {
      resolveOutputRoots(outputPathsFuture.get(10, TimeUnit.SECONDS))
    } catch {
      case e: Exception =>
        optionRequestsFailed = true
        logger.debug(s"buildTargetOutputPaths failed: ${e.getMessage}")
        Map.empty
    }

    val scalaOptionsByTarget = try {
      resolveScalacOptions(scalacOptionsFuture.get(10, TimeUnit.SECONDS))
    } catch {
      case e: Exception =>
        optionRequestsFailed = true
        logger.debug(s"buildTargetScalacOptions failed: ${e.getMessage}")
        Map.empty
    }

    // Dead/dying BSP returns failures; committing empty roots would clobber a good index.
    if optionRequestsFailed then
      logger.warn(s"Skipping navigation refresh: BSP option requests failed, keeping previous index state")
      return

    targetIds.foreach { targetId =>
      val opts = scalaOptionsByTarget.get(targetId)
      val semanticdbRoots = SemanticdbIndexing.candidateSemanticdbRoots(
        outputRootsByTarget.getOrElse(targetId, Nil),
        opts
      )
      val flagsDetected = opts.exists { case (options, _) => SemanticdbIndexing.hasSemanticdbFlags(options) }
      val bestEffort = opts.exists { case (options, _) => options.exists(_ == "-Ybest-effort") }

      val sourceRoots = sourceRootsByTarget.getOrElse(targetId, Nil).flatMap(NavigationUriUtils.uriToPathOption)
      val dependencySourceUris = dependencySourceUrisByTarget.getOrElse(targetId, Nil)
      val dependencySlices =
        DependencySourceIndexing.indexDependencySources(workspaceRoot, dependencySourceUris, depSliceCache)

      val prevWsState = synchronized { workspaceIndexStates.get(targetId) }
        .getOrElse(SemanticdbIndexing.WorkspaceIndexState(Map.empty, sourceRoots))
      val (workspaceSlices, newWsState) =
        if semanticdbRoots.nonEmpty then
          SemanticdbIndexing.indexWorkspaceTargetIncremental(workspaceRoot, semanticdbRoots, sourceRoots, prevWsState)
        else (Map.empty[String, SemanticdbFileSlice], SemanticdbIndexing.WorkspaceIndexState(Map.empty, sourceRoots))

      commitRefresh(targetId, flagsDetected, bestEffort, workspaceSlices, dependencySlices, newWsState)
      logger.info(
        s"Navigation index refreshed for ${targetId.getUri}: workspace=${workspaceSlices.size} dependency=${dependencySlices.size}"
      )
    }

    // prune state for targets that no longer exist
    synchronized {
      val stale = targetStates.keySet -- targetIds.toSet
      stale.foreach { t =>
        targetStates.remove(t)
        workspaceIndexStates.remove(t)
        targetSemanticdbFlags.remove(t)
        targetBestEffortFlags.remove(t)
      }
    }
  }

  private def commitRefresh(
      targetId: BuildTargetIdentifier,
      flagsDetected: Boolean,
      bestEffort: Boolean,
      workspaceSlices: Map[String, SemanticdbFileSlice],
      dependencySlices: List[SemanticdbFileSlice],
      workspaceIndexState: SemanticdbIndexing.WorkspaceIndexState
  ): Unit = synchronized {
    targetSemanticdbFlags(targetId) = flagsDetected
    targetBestEffortFlags(targetId) = bestEffort
    workspaceIndexStates.update(targetId, workspaceIndexState)
    targetStates.update(targetId, TargetState.build(targetId, workspaceSlices, dependencySlices))
  }

  def definition(uri: String, position: Position): List[Location] = synchronized {
    val normalized = NavigationUriUtils.normalizeUri(uri)
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    val ownerState = ownerStateForUri(normalized)
    NavigationSymbolLookup
      .firstDefinition(symbols, normalized, ownerState.map(_.orderedWorkspace).getOrElse(Nil), ownerState.map(_.orderedDependency).getOrElse(Nil))
      .toList
  }

  def references(uri: String, position: Position): List[Location] = synchronized {
    val normalized = NavigationUriUtils.normalizeUri(uri)
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    val ownerState = ownerStateForUri(normalized)
    symbols.flatMap { symbol =>
      if NavigationSymbolLookup.isLocalSymbol(symbol) then
        // Local symbols: find references only in current file
        val currentFileSlices = slicesForUri(normalized)
        val defs = currentFileSlices.flatMap(_.symbolDefinitions.getOrElse(symbol, Nil))
        val refs = currentFileSlices.flatMap(_.symbolReferences.getOrElse(symbol, Nil))
        NavigationLocationUtils.postProcessLocations((defs ++ refs).distinct)
      else
        // Non-local symbols: exact match in owning target state
        val defs = ownerState.toList.flatMap(_.mergedDefinitions.getOrElse(symbol, Nil))
        val refs = ownerState.toList.flatMap(_.mergedReferences.getOrElse(symbol, Nil))
        NavigationLocationUtils.postProcessLocations((defs ++ refs).distinct)
    }.distinct
  }

  def documentSymbols(uri: String): List[Either[SymbolInformation, org.eclipse.lsp4j.DocumentSymbol]] = synchronized {
    slicesForUri(NavigationUriUtils.normalizeUri(uri)).flatMap(_.documentSymbols)
  }

  def getTargetSemanticdbFlags: Map[BuildTargetIdentifier, Boolean] = synchronized {
    targetSemanticdbFlags.toMap
  }

  def getTargetBestEffortFlags: Map[BuildTargetIdentifier, Boolean] = synchronized {
    targetBestEffortFlags.toMap
  }

  private def slicesForUri(uri: String): List[SemanticdbFileSlice] =
    targetStates.values.toList.flatMap(_.slicesByUri.getOrElse(uri, Nil))

  /** Selects the owning target state for a source URI. If the URI appears in multiple
    * target states, the first target in `targetOrder` wins. Returns `None` if the URI
    * is not known (logged at debug level — no fallback across targets). */
  private def ownerStateForUri(uri: String): Option[TargetState] = {
    val ordered = targetStates.values.toList.sortBy { state =>
      // preserve insertion order: first target with this URI wins
      targetStates.find(_._2 eq state).map { case (tid, _) =>
        targetStates.keys.toList.indexOf(tid)
      }.getOrElse(Int.MaxValue)
    }
    val owner = ordered.find(_.slicesByUri.contains(uri))
    if owner.isEmpty then
      logger.debug(s"No owning target state for URI $uri")
    owner
  }

  private def resolveOutputRoots(
      result: OutputPathsResult
  ): Map[BuildTargetIdentifier, List[String]] =
    Option(result.getItems)
      .map(_.asScala.toList)
      .getOrElse(Nil)
      .map { item =>
        val roots =
          Option(item.getOutputPaths).map(_.asScala.toList).getOrElse(Nil).collect {
            case p if p.getKind == OutputPathItemKind.DIRECTORY => p.getUri
          }
        item.getTarget -> roots
      }
      .toMap

  /** targetId -> (scalacOptions, classDirectory) */
  private def resolveScalacOptions(
      result: ScalacOptionsResult
  ): Map[BuildTargetIdentifier, (List[String], Option[String])] =
    Option(result.getItems)
      .map(_.asScala.toList)
      .getOrElse(Nil)
      .map(item =>
        item.getTarget ->
          (
            Option(item.getOptions).map(_.asScala.toList).getOrElse(Nil),
            Option(item.getClassDirectory).filter(_.nonEmpty)
          )
      )
      .toMap

  private def orderedWorkspaceSlices: List[SemanticdbFileSlice] =
    targetStates.values.toList.flatMap(_.orderedWorkspace)

  private def orderedDependencySlices: List[SemanticdbFileSlice] =
    targetStates.values.toList.flatMap(_.orderedDependency)

}
