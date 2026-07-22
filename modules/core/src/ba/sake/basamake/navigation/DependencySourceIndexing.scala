package ba.sake.basamake.navigation

import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{Location, Position, Range, SymbolInformation, SymbolKind}
import org.eclipse.lsp4j.jsonrpc.messages.Either

object DependencySourceIndexing extends StrictLogging {

  /** Cache key for one dependency source. fingerprint: archives and single files use
    * mtime+size; directories use fileCount+maxMtime (dir mtime is unreliable). */
  final case class DepKey(uri: String, fingerprint: String)

  def fingerprint(workspaceRoot: os.Path, uri: String): Option[DepKey] =
    resolveSourcePath(uri) match {
      case Some(p) if os.isFile(p) && (isArchiveFile(p.last) || isSourceFile(p.last)) =>
        Some(DepKey(uri, s"${os.mtime(p)}:${os.size(p)}"))
      case Some(p) if os.isDir(p) =>
        val files = os.walk(p).filter(os.isFile(_))
        val maxMtime = files.map(os.mtime(_)).foldLeft(0L)(math.max)
        Some(DepKey(uri, s"dir:${files.size}:$maxMtime"))
      case None if uri.contains("!") =>
        val archiveUri = uri.take(uri.indexOf('!')).stripPrefix("jar:")
        resolveSourcePath(archiveUri) match {
          case Some(p) if os.isFile(p) => Some(DepKey(uri, s"${os.mtime(p)}:${os.size(p)}"))
          case _                       => None
        }
      case _ => None
    }

  /** Parallel pipeline: one VT task per dep (fingerprint + extract + cache lookup),
    * parse fanned out per entry on the same executor. Cache misses compute, hits
    * skip parse entirely. */
  def indexDependencySources(
      workspaceRoot: os.Path,
      dependencySourceUris: List[String],
      cache: DependencySliceCache
  ): List[SemanticdbFileSlice] = {
    if dependencySourceUris.isEmpty then return Nil
    val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
    try {
      val futures = dependencySourceUris.map { uri =>
        executor.submit[List[SemanticdbFileSlice]] { () =>
          try {
            fingerprint(workspaceRoot, uri) match {
              case Some(key) =>
                cache.get(key).getOrElse {
                  val parsed = parseEntries(workspaceRoot, uri, executor)
                  if parsed.nonEmpty then cache.put(key, parsed)
                  parsed
                }
              case None =>
                parseEntries(workspaceRoot, uri, executor)
            }
          } catch case NonFatal(e) =>
            logger.warn(s"Failed to index dependency source $uri: ${e.getMessage}")
            Nil
        }
      }
      val result = futures.flatMap(_.get())
      ScalaSourceParser.logSummary()
      result
    } finally executor.shutdown()
  }

  private def parseEntries(
      workspaceRoot: os.Path,
      uri: String,
      executor: java.util.concurrent.ExecutorService
  ): List[SemanticdbFileSlice] = {
    val entries = dependencySourceEntries(workspaceRoot, uri)
    val futures = entries.map { case (entryUri, content) =>
      executor.submit[List[SemanticdbFileSlice]] { () =>
        try indexSourceContent(entryUri, content)
        catch case NonFatal(e) =>
          logger.warn(s"Failed to index dependency source $entryUri: ${e.getMessage}")
          Nil
      }
    }
    futures.flatMap(_.get())
  }

  def dependencySourceEntries(workspaceRoot: os.Path, uri: String): List[(String, String)] = {
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

  def indexSourceContent(
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
        definitions.flatMap { defn =>
          val loc = new Location(sourceUri, defn.range)
          Some(defn.symbol -> loc)
        }.groupMap(_._1)(_._2)
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

  def isSourceFile(name: String): Boolean =
    name.endsWith(".scala") || name.endsWith(".java")

  def isArchiveFile(name: String): Boolean =
    name.endsWith(".jar") || name.endsWith(".zip")

  def readText(path: os.Path): Option[String] =
    try Some(os.read(path))
    catch case _: Exception => None

  def resolveSourcePath(uri: String): Option[os.Path] =
    try Some(os.Path(java.net.URI.create(uri)))
    catch case _: Exception =>
      try
        val stripped = uri.stripPrefix("jar:")
        val archive = NavigationUriUtils.canonicalFileUri(stripped.takeWhile(_ != '!'))
        Some(os.Path(java.net.URI.create(archive)))
      catch case _: Exception =>
        try Some(os.Path(uri))
        catch case _: Exception => None
}
