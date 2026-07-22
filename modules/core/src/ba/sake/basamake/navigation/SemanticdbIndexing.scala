package ba.sake.basamake.navigation

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, BuildServer, OutputPathItemKind, OutputPathsParams, ScalacOptionsParams, ScalaBuildServer}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{Location, Position, Range, SymbolInformation, SymbolKind}
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.meta.internal.semanticdb.{Range as SemanticRange, TextDocument, TextDocuments}

object SemanticdbIndexing extends StrictLogging {

  final case class IndexedFile(mtime: Long, size: Long, slice: SemanticdbFileSlice)

  /** Per-target incremental indexing state. mtime+size fingerprint: compilers always
    * bump mtime on write, so unchanged files are never re-parsed; worst case is a
    * harmless false-positive re-parse if a tool rewrites identical content. */
  final case class WorkspaceIndexState(
      files: Map[os.Path, IndexedFile],
      sourceRoots: List[os.Path]
  )

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
      openUris: Set[String] = Set.empty  // reserved for future two-phase commit
  ): Map[String, SemanticdbFileSlice] =
    indexWorkspaceTargetIncremental(
      workspaceRoot,
      semanticdbRoots,
      sourceRoots,
      WorkspaceIndexState(Map.empty, sourceRoots)
    )._1

  /** Incremental reindex: stat-walks semanticdbRoots, reuses slices for files whose
    * mtime+size match the previous state, parses only new/changed files, drops
    * deleted ones. sourceRoots change forces a full re-parse (sourceUri resolution
    * depends on roots). Parse failures store no fingerprint, so they self-heal on
    * the next round. */
  def indexWorkspaceTargetIncremental(
      workspaceRoot: os.Path,
      semanticdbRoots: Set[os.Path],
      sourceRoots: List[os.Path],
      previous: WorkspaceIndexState
  ): (Map[String, SemanticdbFileSlice], WorkspaceIndexState) = {
    val allFiles = semanticdbRoots.flatMap(semanticdbFilesUnder).toList.distinct
    if allFiles.isEmpty then
      return (Map.empty, WorkspaceIndexState(Map.empty, sourceRoots))

    val fullReindex = previous.sourceRoots != sourceRoots
    val (unchanged, toParse) = allFiles.partition { f =>
      !fullReindex && previous.files.get(f).exists { idx =>
        idx.mtime == os.mtime(f) && idx.size == os.stat(f).size
      }
    }

    val parsed: Map[os.Path, IndexedFile] =
      if toParse.isEmpty then Map.empty
      else {
        val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        try {
          val futures = toParse.map { f =>
            executor.submit[java.util.Map.Entry[os.Path, IndexedFile]] { () =>
              parseSemanticdbFile(workspaceRoot, f, sourceRoots)
                .map(s => java.util.Map.entry(f, IndexedFile(os.mtime(f), os.stat(f).size, s)))
                .orNull
            }
          }
          futures.flatMap { f =>
            try Option(f.get(10, TimeUnit.SECONDS)).map(e => e.getKey -> e.getValue)
            catch case _: TimeoutException =>
              logger.warn(s"SemanticDB parse timed out after 10s, skipping")
              None
          }.toMap
        } finally executor.shutdown()
      }

    val kept: Map[os.Path, IndexedFile] =
      unchanged.flatMap(f => previous.files.get(f).map(f -> _)).toMap
    val newFiles = kept ++ parsed
    // preserve walk order for deterministic last-wins on duplicate sourceUris
    val byUri = allFiles
      .flatMap(newFiles.get)
      .map(idx => idx.slice.sourceUri -> idx.slice)
      .toMap
    val dropped = previous.files.keySet -- allFiles.toSet
    logger.debug(
      s"Workspace index diff: reused=${kept.size} parsed=${parsed.size} dropped=${dropped.size}"
    )
    (byUri, WorkspaceIndexState(newFiles, sourceRoots))
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
    // Space-separated forms: flag followed by path in next element
    val space3 = options.sliding(2).collect {
      case Seq("-semanticdb-target", path) if !path.startsWith("-") => os.Path(path)
    }.toList
    val space2 = options.sliding(2).collect {
      case Seq("-P:semanticdb:targetroot", path) if !path.startsWith("-") => os.Path(path)
    }.toList
    scala3 ++ scala2 ++ space3 ++ space2
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
          .filter(_.symbol.nonEmpty)
          .flatMap { occ =>
            val loc = new Location(sourceUri, occ.range)
            Some(occ.symbol -> loc)
          }
          .groupMap(_._1)(_._2)

        val references = occurrences
          .filterNot(_.isDefinition)
          .filter(_.symbol.nonEmpty)
          .flatMap { occ =>
            val loc = new Location(sourceUri, occ.range)
            Some(occ.symbol -> loc)
          }
          .groupMap(_._1)(_._2)

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
