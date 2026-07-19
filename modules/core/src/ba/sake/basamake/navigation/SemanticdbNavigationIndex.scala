package ba.sake.basamake.navigation

import java.util.zip.ZipFile
import java.security.MessageDigest
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
      targetOrder: List[String] = Nil,
      workspaceSlicesByTarget: Map[String, Map[String, SemanticdbFileSlice]] = Map.empty,
      dependencySlicesByTarget: Map[String, List[SemanticdbFileSlice]] = Map.empty
  )

  private val targetStates = mutable.Map.empty[String, TargetState]

  def clear(): Unit = synchronized {
    targetStates.clear()
  }

  private[navigation] def setTargetSlicesForTest(
      targetId: String,
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
      targetId: String,
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
      targetIds: List[String],
      sourceRootsByTarget: Map[String, List[String]],
      dependencySourceUrisByTarget: Map[String, List[String]]
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
      val dependencySourceUris = dependencySourceUrisByTarget.getOrElse(targetId, Nil)
      val dependencySlices =
        if dependencySourceUris.nonEmpty then
          indexDependencySources(workspaceRoot, dependencySourceUris)
        else Nil

      val workspaceSlices =
        if semanticdbRoots.nonEmpty then
          indexWorkspaceTarget(workspaceRoot, semanticdbRoots, sourceRoots)
        else Map.empty

      targetStates.update(
        targetId,
        TargetState(
          targetOrder = List(targetId),
          workspaceSlicesByTarget = Map(targetId -> workspaceSlices),
          dependencySlicesByTarget = Map(targetId -> dependencySlices)
        )
      )
      logger.debug(
        s"SemanticDB index refreshed for $targetId: workspace=${workspaceSlices.size} dependency=${dependencySlices.size}"
      )
    }
  }

  def definition(uri: String, position: Position): List[Location] = synchronized {
    val normalized = normalizeUri(uri)
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    firstDefinition(symbols).toList
  }

  def references(uri: String, position: Position): List[Location] = synchronized {
    val normalized = normalizeUri(uri)
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    symbols.flatMap { symbol =>
      val candidateKeys = candidateSymbolKeys(symbol)
      val defs = allDefinitions.getOrElse(symbol, Nil) ++ candidateKeys.flatMap(k => allDefinitions.getOrElse(k, Nil))
      val refs = allReferences.getOrElse(symbol, Nil) ++ candidateKeys.flatMap(k => allReferences.getOrElse(k, Nil))
      postProcessLocations((defs ++ refs).distinct)
    }.distinct
  }

  def documentSymbols(uri: String): List[Either[SymbolInformation, org.eclipse.lsp4j.DocumentSymbol]] = synchronized {
    slicesForUri(normalizeUri(uri)).flatMap(_.documentSymbols)
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
        canonicalFileUri(
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
    val cacheRoot = workspaceRoot / ".basamake" / "dependency-sources" / dependencyCacheKey(archiveUri)
    val relPath = os.RelPath(entryName)
    val target = cacheRoot / relPath
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
    val definitions = extractDefinitions(content)
    if definitions.isEmpty then Nil
    else {
      val occurrences = definitions.flatMap { defn =>
        Some(SemanticdbOccurrence(defn.name, defn.range, isDefinition = true))
      }
      val symbolDefinitions = definitions.groupMap(_.name)(defn => new Location(sourceUri, defn.range))
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

  private final case class SourceDefinition(
      name: String,
      kind: SymbolKind,
      range: Range
  )

  private val DefinitionPattern =
    """\b(object|class|trait|enum|def|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)""".r

  private def extractDefinitions(content: String): List[SourceDefinition] = {
    val lines = content.linesIterator.toVector
    lines.zipWithIndex.flatMap { case (line, lineIndex) =>
      DefinitionPattern.findAllMatchIn(line).toList.flatMap { m =>
        val keyword = m.group(1)
        val name = m.group(2)
        val kind = keyword match {
          case "object"           => SymbolKind.Object
          case "class"            => SymbolKind.Class
          case "trait"            => SymbolKind.Interface
          case "enum"             => SymbolKind.Enum
          case "def"              => SymbolKind.Method
          case "val"              => SymbolKind.Property
          case "var"              => SymbolKind.Variable
          case _                  => SymbolKind.Object
        }
        val start = m.start(2)
        val range = new Range(
          new Position(lineIndex, start),
          new Position(lineIndex, start + name.length)
        )
        Some(SourceDefinition(name, kind, range))
      }
    }.toList
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
        val archive = canonicalFileUri(stripped.takeWhile(_ != '!'))
        Some(os.Path(java.net.URI.create(archive)))
      catch case _: Exception =>
        try Some(os.Path(uri))
        catch case _: Exception => None

  private def candidateSymbolKeys(symbol: String): List[String] = {
    val clean = symbol
      .replace("()", "")
      .stripSuffix(".")
      .stripSuffix("#")
    val afterPackage =
      clean.lastIndexOf('/') match
        case idx if idx >= 0 => clean.substring(idx + 1)
        case _               => clean
    val segments = afterPackage.split('.').toList.filter(_.nonEmpty)
    segments match
      case Nil => Nil
      case many => many.inits.toList.reverse.map(_.mkString(".")).filter(_.nonEmpty)
  }

  private def firstDefinition(symbols: List[String]): Option[Location] = {
    val workspaceSlices = orderedWorkspaceSlices
    val dependencySlices = orderedDependencySlices
    symbols.iterator
      .flatMap { symbol =>
        firstDefinitionInSlices(symbol, workspaceSlices)
          .orElse(firstDefinitionInSlices(symbol, dependencySlices))
      }
      .toList
      .headOption
  }

  private def firstDefinitionInSlices(
      symbol: String,
      slices: List[SemanticdbFileSlice]
  ): Option[Location] = {
    val keys = symbol +: candidateSymbolKeys(symbol)
    slices.iterator
      .flatMap { slice =>
        keys.iterator.flatMap(key => slice.symbolDefinitions.getOrElse(key, Nil).iterator)
      }
      .toList
      .headOption
  }

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
      case None       => archivePathOption(loc.getUri).exists(os.exists(_))

  private def uriToPathOption(uri: String): Option[os.Path] =
    try Some(os.Path(java.net.URI.create(uri)))
    catch case _: Exception =>
      try Some(os.Path(uri))
      catch case _: Exception => None

  private def archivePathOption(uri: String): Option[os.Path] =
    if uri.startsWith("jar:") then
      try
        val archiveUri = canonicalFileUri(uri.stripPrefix("jar:").takeWhile(_ != '!'))
        Some(os.Path(java.net.URI.create(archiveUri)))
      catch case _: Exception => None
    else None

  private final case class MavenCoordinates(groupId: String, artifactId: String, version: String)

  private def dependencyCacheKey(archiveUri: String): String = {
    val hash8 = stableHash(archiveUri)
    mavenCoordinates(archiveUri)
      .map { coords =>
        val gav =
          s"${sanitizePathSegment(coords.groupId)}-${sanitizePathSegment(coords.artifactId)}-${sanitizePathSegment(coords.version)}"
        s"$gav-$hash8"
      }
      .getOrElse(hash8)
  }

  private def mavenCoordinates(archiveUri: String): Option[MavenCoordinates] = {
    val normalizedArchiveUri =
      if archiveUri.startsWith("jar:") then archiveUri.stripPrefix("jar:").takeWhile(_ != '!')
      else archiveUri
    val path = try java.net.URI.create(canonicalFileUri(normalizedArchiveUri)).getPath
    catch case _: Exception => normalizedArchiveUri
    val segments = path.split('/').toList.filter(_.nonEmpty)
    val maven2Index = segments.lastIndexOf("maven2")
    if maven2Index < 0 then None
    else {
      val tail = segments.drop(maven2Index + 1)
      if tail.length < 4 then None
      else {
        val groupParts = tail.dropRight(3)
        val artifactId = tail(tail.length - 3)
        val version = tail(tail.length - 2)
        if groupParts.isEmpty || artifactId.isEmpty || version.isEmpty then None
        else Some(MavenCoordinates(groupParts.mkString("."), artifactId, version))
      }
    }
  }

  private def sanitizePathSegment(value: String): String =
    value.map {
      case c if c.isLetterOrDigit || c == '.' || c == '-' || c == '_' => c
      case _                                                           => '_'
    }

  private def stableHash(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest
      .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .take(4)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def canonicalFileUri(uri: String): String = {
    val decoded =
      try java.net.URLDecoder.decode(uri, java.nio.charset.StandardCharsets.UTF_8)
      catch case _: Exception => uri
    try java.nio.file.Path.of(java.net.URI.create(decoded)).toUri.toString
    catch case _: Exception => decoded
  }

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
