package ba.sake.basamake.navigation

import java.util.zip.ZipFile
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import ch.epfl.scala.bsp4j.{BuildServer, BuildTargetIdentifier, OutputPathsParams, OutputPathItemKind, ScalacOptionsParams, ScalaBuildServer}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{Location, Position, Range, SymbolInformation, SymbolKind}
import org.eclipse.lsp4j.jsonrpc.messages.Either
import scala.meta.internal.semanticdb.{Range as SemanticRange, TextDocument, TextDocuments}

final case class SemanticdbOccurrence(symbol: String, range: Range, isDefinition: Boolean)

final case class SemanticdbFileSlice(
    sourceUri: String,
    occurrences: List[SemanticdbOccurrence],
    symbolDefinitions: Map[String, List[Location]],
    symbolReferences: Map[String, List[Location]],
    documentSymbols: List[Either[SymbolInformation, org.eclipse.lsp4j.DocumentSymbol]]
) {
  def symbolAt(position: Position): Option[String] =
    occurrences.find(occ => NavigationRangeUtils.contains(occ.range, position)).map(_.symbol)
}

final class SemanticdbNavigationIndex extends StrictLogging {

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
    depSliceCache.clear()
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
    val outputRootsByTarget = fetchOutputRoots(buildServer, buildTargetIds)
    val scalaOptionsByTarget = fetchScalacOptions(buildServer, buildTargetIds)

    targetIds.foreach { targetId =>
      val opts = scalaOptionsByTarget.get(targetId)
      val semanticdbRoots = candidateSemanticdbRoots(
        outputRootsByTarget.getOrElse(targetId, Nil),
        opts
      )
      val flagsDetected = opts.exists { case (options, _) => hasSemanticdbFlags(options) }
      val bestEffort = opts.exists { case (options, _) => options.exists(_ == "-Ybest-effort") }

      val sourceRoots = sourceRootsByTarget.getOrElse(targetId, Nil).flatMap(NavigationUriUtils.uriToPathOption)
      val dependencySourceUris = dependencySourceUrisByTarget.getOrElse(targetId, Nil)
      val depUriSet = dependencySourceUris.toSet
      val dependencySlices =
        if depUriSet.nonEmpty then
          depSliceCache.synchronized {
            depSliceCache.getOrElseUpdate(depUriSet, indexDependencySources(workspaceRoot, dependencySourceUris))
          }
        else Nil

      val workspaceSlices =
        if semanticdbRoots.nonEmpty then
          indexWorkspaceTarget(workspaceRoot, semanticdbRoots, sourceRoots)
        else Map.empty

      commitRefresh(targetId, flagsDetected, bestEffort, workspaceSlices, dependencySlices)
      logger.info(
        s"SemanticDB index refreshed for ${targetId.getUri}: workspace=${workspaceSlices.size} dependency=${dependencySlices.size}"
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
      .firstDefinition(symbols, orderedWorkspaceSlices, orderedDependencySlices)
      .toList
  }

  def references(uri: String, position: Position): List[Location] = synchronized {
    val normalized = NavigationUriUtils.normalizeUri(uri)
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    symbols.flatMap { symbol =>
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

  private def fetchOutputRoots(
      buildServer: BuildServer,
      targetIds: java.util.List[BuildTargetIdentifier]
  ): Map[BuildTargetIdentifier, List[String]] =
    try {
      val result = buildServer.buildTargetOutputPaths(new OutputPathsParams(targetIds))
        .get(10, TimeUnit.SECONDS)
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
    } catch {
      case e: Exception =>
        logger.debug(s"buildTargetOutputPaths failed: ${e.getMessage}")
        Map.empty
    }

  /** targetId -> (scalacOptions, classDirectory) */
  private def fetchScalacOptions(
      buildServer: BuildServer,
      targetIds: java.util.List[BuildTargetIdentifier]
  ): Map[BuildTargetIdentifier, (List[String], Option[String])] =
    buildServer match {
      case scalaBuild: ScalaBuildServer =>
        try {
          val result = scalaBuild.buildTargetScalacOptions(new ScalacOptionsParams(targetIds))
            .get(10, TimeUnit.SECONDS)
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
        } catch {
          case e: Exception =>
            logger.debug(s"buildTargetScalacOptions failed: ${e.getMessage}")
            Map.empty
        }
      case _ => Map.empty
    }

  private def candidateSemanticdbRoots(
      outputRoots: List[String],
      scalacOptions: Option[(List[String], Option[String])]
  ): Set[os.Path] = {
    val rootsFromSemanticdbTarget = scalacOptions.toList.flatMap { case (options, _) =>
      SemanticdbNavigationIndex.semanticdbTargetPaths(options)
    }.toSet
    if rootsFromSemanticdbTarget.nonEmpty then rootsFromSemanticdbTarget
    else {
      val rootsFromOutputs = outputRoots.flatMap(NavigationUriUtils.uriToPathOption)
      val rootsFromClassDir = scalacOptions.toList.flatMap(_._2).flatMap(NavigationUriUtils.uriToPathOption)
      val roots = (rootsFromOutputs ++ rootsFromClassDir).toSet
      scalacOptions.foreach { case (options, _) =>
        if roots.nonEmpty && !hasSemanticdbFlags(options) then
          logger.warn(s"SemanticDB flags absent in scalac options [${options.mkString(", ")}]; indexing from discovered output/class directories")
      }
      roots
    }
  }

  private def hasSemanticdbFlags(options: List[String]): Boolean =
    options.exists(_ == "-Xsemanticdb") ||
      options.exists(s => s == "-semanticdb-target" || s.startsWith("-semanticdb-target:")) ||
      options.exists(_.startsWith("-P:semanticdb:")) ||
      options.exists(_ == "-Xplugin:semanticdb")

  private def indexWorkspaceTarget(
      workspaceRoot: os.Path,
      semanticdbRoots: Set[os.Path],
      sourceRoots: List[os.Path]
  ): Map[String, SemanticdbFileSlice] = {
    SemanticdbNavigationIndex.indexRoots(workspaceRoot, semanticdbRoots, sourceRoots)
  }

  private def indexDependencySources(
      workspaceRoot: os.Path,
      dependencySourceUris: List[String]
  ): List[SemanticdbFileSlice] =
    dependencySourceUris.flatMap(indexDependencySourceUri(workspaceRoot, _))

  private def indexDependencySourceUri(workspaceRoot: os.Path, uri: String): List[SemanticdbFileSlice] =
    dependencySourceEntries(workspaceRoot, uri).flatMap { case (entryUri, content) =>
      indexSourceContent(entryUri, content)
    }

  private def dependencySourceEntries(workspaceRoot: os.Path, uri: String): List[(String, String)] = {
    resolveSourcePath(uri) match {
      case Some(path) if os.isDir(path) =>
        os.walk(path)
          .filter(p => os.isFile(p) && isSourceFile(p.last))
          .toList
          .flatMap { file =>
            readText(file).toList.map(text => file.toNIO.toUri.toString -> text)
          }
      case Some(path) if os.isFile(path) && isArchiveFile(path.last) =>
        readArchiveEntries(workspaceRoot, path, uri)
      case Some(path) if os.isFile(path) && isSourceFile(path.last) =>
        readText(path).toList.map(text => path.toNIO.toUri.toString -> text)
      case Some(path) if os.isFile(path) =>
        if isArchiveFile(path.last) then readArchiveEntries(workspaceRoot, path, uri) else Nil
      case None if uri.contains("!") =>
        val archiveUri = uri.take(uri.indexOf('!')).stripPrefix("jar:")
        resolveSourcePath(archiveUri) match {
          case Some(path) if os.isFile(path) && isArchiveFile(path.last) => readArchiveEntries(workspaceRoot, path, archiveUri)
          case _                                                         => Nil
        }
      case _ => Nil
    }
  }

  private def readArchiveEntries(workspaceRoot: os.Path, path: os.Path, archiveUri: String): List[(String, String)] =
    Using.resource(new ZipFile(path.toNIO.toFile)) { zip =>
      val baseArchiveUri =
        NavigationUriUtils.canonicalFileUri(
          if archiveUri.startsWith("jar:") then archiveUri.stripPrefix("jar:").takeWhile(_ != '!')
          else archiveUri
        )
      zip.entries.asScala.toList.collect {
        case entry if !entry.isDirectory && isSourceFile(entry.getName) =>
          val extracted = extractArchiveEntry(workspaceRoot, baseArchiveUri, entry.getName, zip.getInputStream(entry))
          val entryUri = extracted.toNIO.toUri.toString
          entryUri -> os.read(extracted)
      }
    }

  private def extractArchiveEntry(
      workspaceRoot: os.Path,
      archiveUri: String,
      entryName: String,
      inputStream: java.io.InputStream
  ): os.Path = {
    val cacheRoot = workspaceRoot / ".basamake" / "dependency-sources" / DependencySourceParsing.dependencyCacheKey(archiveUri)
    val relPath = os.RelPath(entryName)
    val target = cacheRoot / relPath
    if os.exists(target) then return target
    os.makeDir.all(target / os.up)
    Using.resource(inputStream) { in =>
      os.write.over(target, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
    }
    target
  }

  private def indexSourceContent(
      sourceUri: String,
      content: String
  ): List[SemanticdbFileSlice] = {
    val fileName = sourceUri.split('/').lastOption.getOrElse(sourceUri)
    val definitions = DependencySourceParsing.extractDefinitions(fileName, content)
    if definitions.isEmpty then Nil
    else {
      val occurrences = definitions.flatMap { defn =>
        Some(SemanticdbOccurrence(defn.symbol, defn.range, isDefinition = true))
      }
      val symbolDefinitions =
        definitions.groupMap(_.symbol)(d => new Location(sourceUri, d.range)) ++
          definitions.groupMap(_.ownerName)(d => new Location(sourceUri, d.range)) ++
          definitions.groupMap(_.name)(d => new Location(sourceUri, d.range))
      val documentSymbols =
        definitions.flatMap { defn =>
          val symbolInfo = new SymbolInformation()
          symbolInfo.setName(defn.name)
          symbolInfo.setKind(defn.kind)
          symbolInfo.setLocation(new Location(sourceUri, defn.range))
          Some(Either.forLeft[SymbolInformation, org.eclipse.lsp4j.DocumentSymbol](symbolInfo))
        }
      List(
        SemanticdbFileSlice(
          sourceUri = sourceUri,
          occurrences = occurrences,
          symbolDefinitions = symbolDefinitions,
          symbolReferences = Map.empty,
          documentSymbols = documentSymbols
        )
      )
    }
  }

  private def isSourceFile(name: String): Boolean =
    name.endsWith(".scala") || name.endsWith(".java")

  private def isArchiveFile(name: String): Boolean =
    name.endsWith(".jar") || name.endsWith(".zip")

  private def readText(path: os.Path): Option[String] =
    try Some(os.read(path))
    catch case _: Exception => None

  private def resolveSourcePath(uri: String): Option[os.Path] =
    try Some(os.Path(java.net.URI.create(uri)))
    catch case _: Exception =>
      try
        val stripped = uri.stripPrefix("jar:")
        val archive = NavigationUriUtils.canonicalFileUri(stripped.takeWhile(_ != '!'))
        Some(os.Path(java.net.URI.create(archive)))
      catch case _: Exception =>
        try Some(os.Path(uri))
        catch case _: Exception => None

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

object SemanticdbNavigationIndex extends StrictLogging {
  def indexRoots(
      workspaceRoot: os.Path,
      semanticdbRoots: Set[os.Path],
      sourceRoots: List[os.Path]
  ): Map[String, SemanticdbFileSlice] = {
    val semanticdbFiles = semanticdbRoots.flatMap(semanticdbFilesUnder).toList.distinct
    semanticdbFiles.flatMap { file =>
      parseSemanticdbFile(workspaceRoot, file, sourceRoots).map(slice => slice.sourceUri -> slice)
    }.toMap
  }

  def semanticdbTargetPaths(options: List[String]): List[os.Path] = {
    // Scala 3: -semanticdb-target:<path> (colon-separated)
    val scala3 = options.collect {
      case s if s.startsWith("-semanticdb-target:") =>
        os.Path(s.stripPrefix("-semanticdb-target:"))
    }
    // Scala 2: -P:semanticdb:targetroot:<path> (colon-separated)
    val scala2 = options.collect {
      case s if s.startsWith("-P:semanticdb:targetroot:") =>
        os.Path(s.stripPrefix("-P:semanticdb:targetroot:"))
    }
    scala3 ++ scala2
  }

  def semanticdbFilesUnder(root: os.Path): List[os.Path] =
    if !os.exists(root) then Nil
    else
      os.walk(root)
        .filter(p => os.isFile(p) && p.last.endsWith(".semanticdb"))
        .toList

  def parseSemanticdbFile(
      workspaceRoot: os.Path,
      semanticdbFile: os.Path,
      sourceRoots: List[os.Path]
  ): Option[SemanticdbFileSlice] = {
    try {
      val bytes = os.read.bytes(semanticdbFile)
      val documents = TextDocuments.parseFrom(bytes)
      documents.documents.headOption.map { doc =>
        val sourceUri = NavigationUriUtils.normalizeUri(resolveSourceUri(workspaceRoot, semanticdbFile, doc.uri, sourceRoots))
        val symbolInfoById = doc.symbols.toList.map(si => si.symbol -> si).toMap

        val occurrences = doc.occurrences.toList.flatMap { occ =>
          occ.range.map { range =>
            SemanticdbOccurrence(occ.symbol, toLspRange(range), occ.role.isDefinition)
          }
        }

        val definitions = occurrences
          .filter(_.isDefinition)
          .groupMap(_.symbol)(occ => new Location(sourceUri, occ.range))

        val references = occurrences
          .filterNot(_.isDefinition)
          .groupMap(_.symbol)(occ => new Location(sourceUri, occ.range))

        val documentSymbols =
          occurrences
            .filter(_.isDefinition)
            .flatMap { occ =>
              symbolInfoById.get(occ.symbol).flatMap { info =>
                Option(info.displayName)
                  .filter(_.nonEmpty)
                  .orElse(Option(info.symbol))
                  .map { name =>
                    val symbolInfo = new SymbolInformation()
                    symbolInfo.setName(name)
                    symbolInfo.setKind(toLspSymbolKind(info.kind.toString))
                    symbolInfo.setLocation(new Location(sourceUri, occ.range))
                    Either.forLeft[SymbolInformation, org.eclipse.lsp4j.DocumentSymbol](symbolInfo)
                  }
              }
            }

        SemanticdbFileSlice(sourceUri, occurrences, definitions, references, documentSymbols)
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to parse SemanticDB file $semanticdbFile: ${e.getMessage}")
        None
    }
  }

  private def resolveSourceUri(
      workspaceRoot: os.Path,
      semanticdbFile: os.Path,
      docUri: String,
      sourceRoots: List[os.Path]
  ): String =
    if docUri.startsWith("file:") then NavigationUriUtils.normalizeUri(docUri)
    else {
      val relativeSource = relativeSourcePath(semanticdbFile).getOrElse(os.RelPath(docUri))
      val candidates = resolveCandidates(workspaceRoot, relativeSource, sourceRoots)
      candidates
        .find(p => os.exists(p))
        .map(_.toNIO.toUri.toString)
        .getOrElse((workspaceRoot / relativeSource).toNIO.toUri.toString)
    }

  private[navigation] def resolveCandidates(
      workspaceRoot: os.Path,
      relativeSource: os.RelPath,
      sourceRoots: List[os.Path]
  ): List[os.Path] = {
    val relSegments = relativeSource.segments.toList
    val fromWorkspace = workspaceRoot / relativeSource
    val fromRoots = sourceRoots.flatMap { root =>
      val rootSegments = root.segments.toList
      val maxOverlap = math.min(rootSegments.size, relSegments.size)
      val overlaps = (maxOverlap to 0 by -1).find { n =>
        rootSegments.takeRight(n) == relSegments.take(n)
      }.toList
      overlaps.map { overlap =>
        val rest = relSegments.drop(overlap)
        if rest.isEmpty then root
        else root / os.RelPath(rest.mkString("/"))
      }
    }
    (fromWorkspace +: fromRoots).distinct
  }

  private def relativeSourcePath(semanticdbFile: os.Path): Option[os.RelPath] = {
    val segments = semanticdbFile.segments.toList
    val marker = List("META-INF", "semanticdb")
    val idx = segments.sliding(marker.size).indexWhere(_.toList == marker)
    if idx < 0 || idx + marker.size >= segments.size then None
    else
      val relSegments = segments.drop(idx + marker.size)
      val fileName = relSegments.lastOption.map(_.stripSuffix(".semanticdb")).getOrElse("")
      Some(os.RelPath(relSegments.dropRight(1).appended(fileName).mkString("/")))
  }

  private def toLspRange(range: SemanticRange): Range =
    new Range(
      new Position(range.startLine, range.startCharacter),
      new Position(range.endLine, range.endCharacter)
    )

  private def toLspSymbolKind(kind: String): SymbolKind =
    kind match {
      case "CLASS" | "ENUM"                   => SymbolKind.Class
      case "INTERFACE" | "TRAIT"              => SymbolKind.Interface
      case "OBJECT" | "PACKAGE_OBJECT"        => SymbolKind.Object
      case "PACKAGE"                          => SymbolKind.Package
      case "METHOD"                           => SymbolKind.Method
      case "CONSTRUCTOR"                      => SymbolKind.Constructor
      case "FIELD"                            => SymbolKind.Field
      case "VAL"                              => SymbolKind.Property
      case "VAR"                              => SymbolKind.Variable
      case "PARAMETER" | "LOCAL"              => SymbolKind.Variable
      case "TYPE" | "TYPE_PARAMETER"          => SymbolKind.TypeParameter
      case "ENUM_CASE"                        => SymbolKind.EnumMember
      case _                                  => SymbolKind.Object
    }
}
