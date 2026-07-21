package ba.sake.basamake.navigation

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, BuildServer, OutputPathItemKind, OutputPathsParams, ScalacOptionsParams, ScalaBuildServer}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{Location, Position, Range, SymbolInformation, SymbolKind}
import org.eclipse.lsp4j.jsonrpc.messages.Either
import scala.meta.internal.semanticdb.{Range as SemanticRange, TextDocument, TextDocuments}

object SemanticdbIndexing extends StrictLogging {

  def candidateSemanticdbRoots(
      outputRoots: List[String],
      scalacOptions: Option[(List[String], Option[String])]
  ): Set[os.Path] = {
    val rootsFromSemanticdbTarget = scalacOptions.toList.flatMap { case (options, _) =>
      semanticdbTargetPaths(options)
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

  def hasSemanticdbFlags(options: List[String]): Boolean =
    options.exists(_ == "-Xsemanticdb") ||
      options.exists(s => s == "-semanticdb-target" || s.startsWith("-semanticdb-target:")) ||
      options.exists(_.startsWith("-P:semanticdb:")) ||
      options.exists(_ == "-Xplugin:semanticdb")

  def indexWorkspaceTarget(
      workspaceRoot: os.Path,
      semanticdbRoots: Set[os.Path],
      sourceRoots: List[os.Path],
      openUris: Set[String] = Set.empty
  ): Map[String, SemanticdbFileSlice] = {
    val allFiles = semanticdbRoots.flatMap(semanticdbFilesUnder).toList.distinct
    if allFiles.isEmpty then return Map.empty

    // Parse all files concurrently
    val parsed: List[(String, SemanticdbFileSlice)] =
      if allFiles.size == 1 then
        allFiles.flatMap { f =>
          parseSemanticdbFile(workspaceRoot, f, sourceRoots).map(s => s.sourceUri -> s)
        }
      else {
        val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
          val futures = allFiles.map { f =>
            executor.submit[java.util.Map.Entry[String, SemanticdbFileSlice]] { () =>
              parseSemanticdbFile(workspaceRoot, f, sourceRoots)
                .map(s => java.util.Map.entry(s.sourceUri, s))
                .orNull
            }
          }
          futures.flatMap(f => Option(f.get()).map(e => e.getKey -> e.getValue))
        } finally executor.shutdown()
      }

    // Priority-sort: open files first, then rest
    val (openSlices, otherSlices) = parsed.partition((uri, _) => openUris.contains(uri))

    (openSlices ++ otherSlices).toMap
  }

  def indexRoots(
      workspaceRoot: os.Path,
      semanticdbRoots: Set[os.Path],
      sourceRoots: List[os.Path]
  ): Map[String, SemanticdbFileSlice] = {
    val semanticdbFiles = semanticdbRoots.flatMap(semanticdbFilesUnder).toList.distinct

    if semanticdbFiles.size <= 1 then
      // Single file or empty — no need for concurrency
      semanticdbFiles.flatMap { file =>
        parseSemanticdbFile(workspaceRoot, file, sourceRoots).map(slice => slice.sourceUri -> slice)
      }.toMap
    else {
      val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
      try {
        val futures = semanticdbFiles.map { file =>
          executor.submit[java.util.Map.Entry[String, SemanticdbFileSlice]] { () =>
            parseSemanticdbFile(workspaceRoot, file, sourceRoots)
              .map(slice => java.util.Map.entry(slice.sourceUri, slice))
              .orNull
          }
        }
        futures.flatMap { f =>
          Option(f.get()).map(e => e.getKey -> e.getValue)
        }.toMap
      } finally {
        executor.shutdown()
      }
    }
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

  def resolveCandidates(
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
