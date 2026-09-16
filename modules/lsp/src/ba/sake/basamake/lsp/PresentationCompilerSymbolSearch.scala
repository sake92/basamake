package ba.sake.basamake.lsp

import java.net.URI
import java.net.URLClassLoader
import java.util.Optional
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import org.eclipse.lsp4j.{Location, Position, Range}
import scala.meta.pc.{ContentType, ParentSymbols, SymbolDocumentation, SymbolSearch, SymbolSearchVisitor}
import coursierapi.{Dependency, Fetch}
import ba.sake.basamake.index.{SymbolDefinition, SymbolUtils}
import ba.sake.basamake.index.indexing.WorkspaceIndex

/** The presentation compiler's source-aware view of one BSP target. It supplies
  * definition locations and documentation for workspace symbols and for the
  * target-scoped third-party source jars already managed by `IndexedSymbolTable`.
  * Candidate scoping is deliberate: a symbol search must not leak a same-named
  * class from another target into this compiler's hover result. */
private[lsp] final class PresentationCompilerSymbolSearch(
    workspaceIndex: WorkspaceIndex,
    dependencySources: List[os.Path],
    renderScaladoc: String => String = identity
) extends SymbolSearch {
  override def documentation(symbol: String, parents: ParentSymbols): Optional[SymbolDocumentation] =
    workspaceIndex.getSymbol(symbol, dependencySources).flatMap(documentationFor).toJava

  override def documentation(
      symbol: String,
      parents: ParentSymbols,
      contentType: ContentType
  ): Optional[SymbolDocumentation] = {
    documentation(symbol, parents).toScala.map { documentation =>
      if (contentType == ContentType.MARKDOWN)
        documentation.asInstanceOf[WorkspaceSymbolDocumentation].copy(docstring = renderScaladoc(documentation.docstring()))
      else documentation
    }.toJava
  }

  override def definition(symbol: String, source: URI): java.util.List[Location] =
    workspaceIndex.getSymbol(symbol, dependencySources).map(locationFor).toList.asJava

  override def definitionSourceToplevels(symbol: String, source: URI): java.util.List[String] =
    java.util.List.of()

  override def search(query: String, buildTarget: String, visitor: SymbolSearchVisitor): SymbolSearch.Result =
    SymbolSearch.Result.COMPLETE

  override def searchMethods(query: String, buildTarget: String, visitor: SymbolSearchVisitor): SymbolSearch.Result =
    SymbolSearch.Result.COMPLETE

  private def documentationFor(definition: SymbolDefinition): Option[SymbolDocumentation] = {
    try scaladocBefore(os.read(definition.path), definition.range.startLine).map { docstring =>
      WorkspaceSymbolDocumentation(definition.symbol, SymbolUtils.shortNameOf(definition.symbol), docstring)
    }
    catch { case _: Exception => None }
  }

  private def locationFor(definition: SymbolDefinition): Location = {
    val range = new Range(
      new Position(definition.range.startLine, definition.range.startCharacter),
      new Position(definition.range.endLine, definition.range.endCharacter)
    )
    new Location(definition.path.toNIO.toUri.toString, range)
  }

  private def scaladocBefore(text: String, definitionLine: Int): Option[String] = {
    val before = text.linesIterator.take(definitionLine).mkString("\n")
    val start = before.lastIndexOf("/**")
    val end = before.lastIndexOf("*/")
    if (start < 0 || end < start || !before.substring(end + 2).trim.isEmpty) None
    else {
      val content = before.substring(start + 3, end)
        .linesIterator
        // Strip only the Scaladoc decoration. Leading whitespace after `*` is
        // meaningful inside `{{{ ... }}}` examples and must reach the Markdown
        // renderer intact.
        .map(_.replaceFirst("""^\s*\* ?""", ""))
        .mkString("\n")
        .trim
      Option.when(content.nonEmpty)(content)
    }
  }
}

private[lsp] final case class WorkspaceSymbolDocumentation(
    symbol: String,
    displayName: String,
    docstring: String
) extends SymbolDocumentation {
  override def defaultValue(): String = ""
  override def typeParameters(): java.util.List[SymbolDocumentation] = java.util.List.of()
  override def parameters(): java.util.List[SymbolDocumentation] = java.util.List.of()
}

/** Reflective POC over Metals' internal mtags renderer in its own loader. */
private[lsp] final class MtagsScaladocMarkdown private (
    render: String => String,
    loader: Option[URLClassLoader]
) extends (String => String) {
  override def apply(raw: String): String = render(raw)
  def close(): Unit = loader.foreach(_.close())
}

private[lsp] object MtagsScaladocMarkdown {
  private val MtagsVersion = "1.6.2"
  private val CodeBlock = """(?s)\{\{\{\R?(.*?)\R?\}\}\}""".r

  def apply(): MtagsScaladocMarkdown = {
    try {
      val urls = Fetch.create()
        .addDependencies(Dependency.of("org.scalameta", "mtags_2.13.16", MtagsVersion))
        .fetch()
        .asScala
        .map(_.toURI.toURL)
        .toArray
      val loader = new URLClassLoader(urls, null)
      val generator = Class.forName("scala.meta.internal.docstrings.printers.MarkdownGenerator", true, loader)
      val mapModule = Class.forName("scala.collection.immutable.Map$", true, loader)
      val emptyMap = mapModule.getMethod("empty").invoke(mapModule.getField("MODULE$").get(null))
      val scalaMap = Class.forName("scala.collection.Map", true, loader)
      val fromDocstring = generator.getMethod("fromDocstring", classOf[String], scalaMap)
      val render = (raw: String) => fromDocstring.invoke(null, raw, emptyMap).asInstanceOf[String]
      new MtagsScaladocMarkdown(raw => renderWithCodeBlocks(raw, render), Some(loader))
    } catch {
      case _: Throwable => new MtagsScaladocMarkdown(identity, None)
    }
  }

  /** Mtags renders ScalaDoc examples as Markdown fences but drops indentation
    * inside their bodies. Protect the bodies while it renders the surrounding
    * ScalaDoc, then put back a normal dedented Markdown code block. */
  private def renderWithCodeBlocks(raw: String, render: String => String): String = {
    val blocks = ArrayBuffer.empty[(String, String)]
    val protectedDoc = CodeBlock.replaceAllIn(raw, m => {
      val marker = s"BASAMAKECODEBLOCK${blocks.size}"
      blocks.append(marker -> fencedCode(m.group(1)))
      marker
    })
    blocks.foldLeft(render(protectedDoc)) { case (markdown, (marker, block)) =>
      markdown.replace(marker, block)
    }
  }

  private def fencedCode(raw: String): String = {
    val lines = raw.linesIterator.toList
    val indentation = lines.iterator
      .filter(_.trim.nonEmpty)
      .map(_.takeWhile(_.isWhitespace).length)
      .minOption
      .getOrElse(0)
    val code = lines.map(_.drop(indentation)).mkString("\n").trim
    s"```\n$code\n```"
  }

}
