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

final class NavigationIndex extends StrictLogging {

  private final case class TargetState(
      targetOrder: List[BuildTargetIdentifier] = Nil,
      workspaceSlicesByTarget: Map[BuildTargetIdentifier, Map[String, SemanticdbFileSlice]] = Map.empty,
      dependencySlicesByTarget: Map[BuildTargetIdentifier, List[SemanticdbFileSlice]] = Map.empty
  )

  private val targetStates = mutable.Map.empty[BuildTargetIdentifier, TargetState]
  private val targetSemanticdbFlags = mutable.Map.empty[BuildTargetIdentifier, Boolean]
  private val targetBestEffortFlags = mutable.Map.empty[BuildTargetIdentifier, Boolean]
  private val depSliceCache = mutable.Map.empty[Set[String], List[SemanticdbFileSlice]]

  def clear(): Unit = synchronized {
    targetStates.clear()
    depSliceCache.synchronized { depSliceCache.clear() }
  }

  private[navigation] def setTargetSlicesForTest(
      targetId: BuildTargetIdentifier,
      fileSlices: Map[String, SemanticdbFileSlice]
  ): Unit = synchronized {
    val current = targetStates.getOrElse(targetId, TargetState(targetOrder = List(targetId)))
    targetStates.update(
      targetId,
      current.copy(
        targetOrder = List(targetId),
        workspaceSlicesByTarget = current.workspaceSlicesByTarget + (targetId -> fileSlices)
      )
    )
  }

  private[navigation] def setTargetDependencySlicesForTest(
      targetId: BuildTargetIdentifier,
      slices: List[SemanticdbFileSlice]
  ): Unit = synchronized {
    val current = targetStates.getOrElse(targetId, TargetState(targetOrder = List(targetId)))
    targetStates.update(
      targetId,
      current.copy(
        targetOrder = List(targetId),
        dependencySlicesByTarget = current.dependencySlicesByTarget + (targetId -> slices)
      )
    )
  }

  def refresh(
      workspaceRoot: os.Path,
      buildServer: BuildServer,
      targetIds: List[BuildTargetIdentifier],
      sourceRootsByTarget: Map[BuildTargetIdentifier, List[String]],
      dependencySourceUrisByTarget: Map[BuildTargetIdentifier, List[String]]
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

    val outputRootsByTarget = try {
      resolveOutputRoots(outputPathsFuture.get(10, TimeUnit.SECONDS))
    } catch {
      case e: Exception =>
        logger.debug(s"buildTargetOutputPaths failed: ${e.getMessage}")
        Map.empty
    }

    val scalaOptionsByTarget = try {
      resolveScalacOptions(scalacOptionsFuture.get(10, TimeUnit.SECONDS))
    } catch {
      case e: Exception =>
        logger.debug(s"buildTargetScalacOptions failed: ${e.getMessage}")
        Map.empty
    }

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

      val workspaceSlices =
        if semanticdbRoots.nonEmpty then
          SemanticdbIndexing.indexWorkspaceTarget(workspaceRoot, semanticdbRoots, sourceRoots)
        else Map.empty

      commitRefresh(targetId, flagsDetected, bestEffort, workspaceSlices, dependencySlices)
      logger.info(
        s"Navigation index refreshed for ${targetId.getUri}: workspace=${workspaceSlices.size} dependency=${dependencySlices.size}"
      )
    }
  }

  private def commitRefresh(
      targetId: BuildTargetIdentifier,
      flagsDetected: Boolean,
      bestEffort: Boolean,
      workspaceSlices: Map[String, SemanticdbFileSlice],
      dependencySlices: List[SemanticdbFileSlice]
  ): Unit = synchronized {
    targetSemanticdbFlags(targetId) = flagsDetected
    targetBestEffortFlags(targetId) = bestEffort
    targetStates.update(
      targetId,
      TargetState(
        targetOrder = List(targetId),
        workspaceSlicesByTarget = Map(targetId -> workspaceSlices),
        dependencySlicesByTarget = Map(targetId -> dependencySlices)
      )
    )
  }

  def definition(uri: String, position: Position): List[Location] = synchronized {
    val normalized = NavigationUriUtils.normalizeUri(uri)
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    NavigationSymbolLookup
      .firstDefinition(symbols, normalized, orderedWorkspaceSlices, orderedDependencySlices)
      .toList
  }

  def references(uri: String, position: Position): List[Location] = synchronized {
    val normalized = NavigationUriUtils.normalizeUri(uri)
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    symbols.flatMap { symbol =>
      if NavigationSymbolLookup.isLocalSymbol(symbol) then
        // Local symbols: find references only in current file
        val currentFileSlices = slicesForUri(normalized)
        val defs = currentFileSlices.flatMap(_.symbolDefinitions.getOrElse(symbol, Nil))
        val refs = currentFileSlices.flatMap(_.symbolReferences.getOrElse(symbol, Nil))
        NavigationLocationUtils.postProcessLocations((defs ++ refs).distinct)
      else
        // Non-local symbols: search all files
        val candidateKeys = NavigationSymbolLookup.candidateSymbolKeys(symbol)
        val defs = allDefinitions.getOrElse(symbol, Nil) ++ candidateKeys.flatMap(k => allDefinitions.getOrElse(k, Nil))
        val refs = allReferences.getOrElse(symbol, Nil) ++ candidateKeys.flatMap(k => allReferences.getOrElse(k, Nil))
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
    targetStates.values.toList.flatMap { state =>
      state.targetOrder.flatMap { targetId =>
        state.workspaceSlicesByTarget.get(targetId).toList.flatMap(_.get(uri))
      } ++ state.targetOrder.flatMap { targetId =>
        state.dependencySlicesByTarget.get(targetId).toList.flatten.filter(_.sourceUri == uri)
      }
    }

  private def allDefinitions: Map[String, List[Location]] =
    targetStates.values.toList
      .flatMap(state =>
        state.targetOrder.flatMap { targetId =>
          state.workspaceSlicesByTarget.get(targetId).toList.flatMap(_.values)
        } ++ state.targetOrder.flatMap { targetId =>
          state.dependencySlicesByTarget.get(targetId).toList.flatten
        }
      )
      .flatMap(_.symbolDefinitions)
      .foldLeft(Map.empty[String, List[Location]]) {
      case (acc, (symbol, locations)) =>
        acc.updated(symbol, acc.getOrElse(symbol, Nil) ++ locations)
    }

  private def allReferences: Map[String, List[Location]] =
    targetStates.values.toList
      .flatMap(state =>
        state.targetOrder.flatMap { targetId =>
          state.workspaceSlicesByTarget.get(targetId).toList.flatMap(_.values)
        } ++ state.targetOrder.flatMap { targetId =>
          state.dependencySlicesByTarget.get(targetId).toList.flatten
        }
      )
      .flatMap(_.symbolReferences)
      .foldLeft(Map.empty[String, List[Location]]) {
      case (acc, (symbol, locations)) =>
        acc.updated(symbol, acc.getOrElse(symbol, Nil) ++ locations)
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
    targetStates.values.toList.flatMap { state =>
      state.targetOrder.flatMap { targetId =>
        state.workspaceSlicesByTarget.get(targetId).toList.flatMap(_.values.toList.sortBy(_.sourceUri))
      }
    }

  private def orderedDependencySlices: List[SemanticdbFileSlice] =
    targetStates.values.toList.flatMap { state =>
      state.targetOrder.flatMap { targetId =>
        state.dependencySlicesByTarget.get(targetId).toList.flatten
      }
    }

}
