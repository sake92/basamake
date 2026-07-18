package ba.sake.basamake.navigation

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.{BuildServer, BuildTargetIdentifier, OutputPathsParams, OutputPathItemKind, ScalacOptionsParams, ScalaBuildServer}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{Location, Position, Range, SymbolInformation, SymbolKind}
import org.eclipse.lsp4j.jsonrpc.messages.Either
import scala.meta.internal.semanticdb.{Range as SemanticRange, TextDocuments}

final case class SemanticdbOccurrence(symbol: String, range: Range, isDefinition: Boolean)

final case class SemanticdbFileSlice(
    sourceUri: String,
    occurrences: List[SemanticdbOccurrence],
    symbolDefinitions: Map[String, List[Location]],
    symbolReferences: Map[String, List[Location]],
    documentSymbols: List[Either[SymbolInformation, org.eclipse.lsp4j.DocumentSymbol]]
) {
  def symbolAt(position: Position): Option[String] =
    occurrences.find(occ => contains(occ.range, position)).map(_.symbol)

  private def contains(range: Range, pos: Position): Boolean = {
    val startsBefore =
      pos.getLine > range.getStart.getLine ||
        (pos.getLine == range.getStart.getLine && pos.getCharacter >= range.getStart.getCharacter)
    val endsAfter =
      pos.getLine < range.getEnd.getLine ||
        (pos.getLine == range.getEnd.getLine && pos.getCharacter <= range.getEnd.getCharacter)
    startsBefore && endsAfter
  }
}

final class SemanticdbNavigationIndex extends StrictLogging {

  private final case class TargetState(
      fileSlices: Map[String, SemanticdbFileSlice] = Map.empty
  )

  private val targetStates = mutable.Map.empty[String, TargetState]

  def clear(): Unit = synchronized {
    targetStates.clear()
  }

  private[navigation] def setTargetSlicesForTest(
      targetId: String,
      fileSlices: Map[String, SemanticdbFileSlice]
  ): Unit = synchronized {
    targetStates.update(targetId, TargetState(fileSlices))
  }

  def refresh(
      workspaceRoot: os.Path,
      buildServer: BuildServer,
      targetIds: List[String],
      sourceRootsByTarget: Map[String, List[String]]
  ): Unit = synchronized {
    val buildTargetIds = targetIds.map(id => new BuildTargetIdentifier(id)).asJava
    val outputRootsByTarget = fetchOutputRoots(buildServer, buildTargetIds)
    val scalaOptionsByTarget = fetchScalacOptions(buildServer, buildTargetIds)

    targetIds.foreach { targetId =>
      val semanticdbRoots = candidateSemanticdbRoots(
        outputRootsByTarget.getOrElse(targetId, Nil),
        scalaOptionsByTarget.get(targetId)
      )

      val sourceRoots = sourceRootsByTarget.getOrElse(targetId, Nil).flatMap(uriToPathOption)
      if semanticdbRoots.nonEmpty then {
        val newSlices = indexTarget(workspaceRoot, semanticdbRoots, sourceRoots)
        targetStates.update(targetId, TargetState(newSlices))
        logger.debug(s"SemanticDB index refreshed for $targetId: ${newSlices.size} file(s)")
      } else {
        logger.debug(s"No SemanticDB roots found for $targetId")
        targetStates.update(targetId, TargetState(Map.empty))
      }
    }
  }

  def definition(uri: String, position: Position): List[Location] = synchronized {
    val normalized = normalizeUri(uri)
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    postProcessLocations(symbols.flatMap(symbol => allDefinitions.getOrElse(symbol, Nil)))
  }

  def references(uri: String, position: Position): List[Location] = synchronized {
    val normalized = normalizeUri(uri)
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    postProcessLocations(symbols.flatMap { symbol =>
      val defs = allDefinitions.getOrElse(symbol, Nil)
      val refs = allReferences.getOrElse(symbol, Nil)
      (defs ++ refs).distinct
    })
  }

  def documentSymbols(uri: String): List[Either[SymbolInformation, org.eclipse.lsp4j.DocumentSymbol]] = synchronized {
    slicesForUri(normalizeUri(uri)).flatMap(_.documentSymbols)
  }

  private def slicesForUri(uri: String): List[SemanticdbFileSlice] =
    targetStates.values.toList.flatMap(_.fileSlices.get(uri))

  private def allDefinitions: Map[String, List[Location]] =
    targetStates.values.toList
      .flatMap(_.fileSlices.values)
      .flatMap(_.symbolDefinitions)
      .foldLeft(Map.empty[String, List[Location]]) {
      case (acc, (symbol, locations)) =>
        acc.updated(symbol, acc.getOrElse(symbol, Nil) ++ locations)
    }

  private def allReferences: Map[String, List[Location]] =
    targetStates.values.toList
      .flatMap(_.fileSlices.values)
      .flatMap(_.symbolReferences)
      .foldLeft(Map.empty[String, List[Location]]) {
      case (acc, (symbol, locations)) =>
        acc.updated(symbol, acc.getOrElse(symbol, Nil) ++ locations)
    }

  private def fetchOutputRoots(
      buildServer: BuildServer,
      targetIds: java.util.List[BuildTargetIdentifier]
  ): Map[String, List[String]] =
    try {
      val result = buildServer.buildTargetOutputPaths(new OutputPathsParams(targetIds)).get()
      Option(result.getItems)
        .map(_.asScala.toList)
        .getOrElse(Nil)
        .map { item =>
          val roots =
            Option(item.getOutputPaths).map(_.asScala.toList).getOrElse(Nil).collect {
              case p if p.getKind == OutputPathItemKind.DIRECTORY => p.getUri
            }
          item.getTarget.getUri -> roots
        }
        .toMap
    } catch {
      case e: Exception =>
        logger.debug(s"buildTargetOutputPaths failed: ${e.getMessage}")
        Map.empty
    }

  private def fetchScalacOptions(
      buildServer: BuildServer,
      targetIds: java.util.List[BuildTargetIdentifier]
  ): Map[String, (List[String], Option[String])] =
    buildServer match {
      case scalaBuild: ScalaBuildServer =>
        try {
          val result = scalaBuild.buildTargetScalacOptions(new ScalacOptionsParams(targetIds)).get()
          Option(result.getItems)
            .map(_.asScala.toList)
            .getOrElse(Nil)
            .map(item =>
              item.getTarget.getUri ->
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
    val rootsFromOutputs = outputRoots.flatMap(uriToPathOption)
    val rootsFromClassDir = scalacOptions.toList.flatMap(_._2).flatMap(uriToPathOption)
    val roots = (rootsFromOutputs ++ rootsFromClassDir).toSet
    val flagsPresent = scalacOptions.exists { case (options, _) => hasSemanticdbFlags(options) }
    if roots.nonEmpty && !flagsPresent then
      logger.debug("SemanticDB flags absent in scalac options; indexing from discovered output/class directories")
    roots
  }

  private def hasSemanticdbFlags(options: List[String]): Boolean =
    options.exists(_ == "-Xsemanticdb") ||
      options.exists(_ == "-semanticdb-target") ||
      options.exists(_.startsWith("-P:semanticdb:")) ||
      options.exists(_ == "-Xplugin:semanticdb")

  private def indexTarget(
      workspaceRoot: os.Path,
      semanticdbRoots: Set[os.Path],
      sourceRoots: List[os.Path]
  ): Map[String, SemanticdbFileSlice] = {
    SemanticdbNavigationIndex.indexRoots(workspaceRoot, semanticdbRoots, sourceRoots)
  }

  private def normalizeUri(uri: String): String =
    try java.nio.file.Path.of(java.net.URI.create(uri)).toUri.toString
    catch case _: Exception => uri

  private def postProcessLocations(locations: List[Location]): List[Location] = {
    val normalizedExisting = locations
      .flatMap(normalizeLocation)
      .filter(locationExists)
    normalizedExisting
      .groupBy(loc => s"${loc.getUri}:${loc.getRange.getStart.getLine}:${loc.getRange.getStart.getCharacter}:${loc.getRange.getEnd.getLine}:${loc.getRange.getEnd.getCharacter}")
      .values
      .map(_.head)
      .toList
  }

  private def normalizeLocation(loc: Location): Option[Location] =
    Option(loc).flatMap { l =>
      Option(l.getUri).map { uri =>
        val normalized = normalizeUri(uri)
        if normalized == uri then l
        else new Location(normalized, l.getRange)
      }
    }

  private def locationExists(loc: Location): Boolean =
    uriToPathOption(loc.getUri) match
      case Some(path) => os.exists(path)
      case None       => true

  private def uriToPathOption(uri: String): Option[os.Path] =
    try Some(os.Path(java.net.URI.create(uri)))
    catch case _: Exception =>
      try Some(os.Path(uri))
      catch case _: Exception => None

  private def contains(range: Range, pos: Position): Boolean = {
    val startsBefore =
      pos.getLine > range.getStart.getLine ||
        (pos.getLine == range.getStart.getLine && pos.getCharacter >= range.getStart.getCharacter)
    val endsAfter =
      pos.getLine < range.getEnd.getLine ||
        (pos.getLine == range.getEnd.getLine && pos.getCharacter <= range.getEnd.getCharacter)
    startsBefore && endsAfter
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
        val sourceUri = normalizeUri(resolveSourceUri(workspaceRoot, semanticdbFile, doc.uri, sourceRoots))
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
    if docUri.startsWith("file:") then normalizeUri(docUri)
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

  private def normalizeUri(uri: String): String =
    try java.nio.file.Path.of(java.net.URI.create(uri)).toUri.toString
    catch case _: Exception => uri

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
