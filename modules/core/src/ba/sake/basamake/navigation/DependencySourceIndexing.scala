package ba.sake.basamake.navigation

import java.util.zip.ZipFile
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{Location, Position, Range, SymbolInformation, SymbolKind}
import org.eclipse.lsp4j.jsonrpc.messages.Either

object DependencySourceIndexing extends StrictLogging {

  def indexDependencySources(
      workspaceRoot: os.Path,
      dependencySourceUris: List[String],
      cache: mutable.Map[Set[String], List[SemanticdbFileSlice]]
  ): List[SemanticdbFileSlice] = {
    val depUriSet = dependencySourceUris.toSet
    if depUriSet.nonEmpty then
      cache.synchronized {
        cache.getOrElseUpdate(depUriSet, dependencySourceUris.flatMap(indexDependencySourceUri(workspaceRoot, _)))
      }
    else Nil
  }

  private def indexDependencySourceUri(workspaceRoot: os.Path, uri: String): List[SemanticdbFileSlice] =
    dependencySourceEntries(workspaceRoot, uri).flatMap { case (entryUri, content) =>
      indexSourceContent(entryUri, content)
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
