package ba.sake.basamake.lsp

import java.net.URI
import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import org.eclipse.lsp4j.{Location, Position, Range}
import scala.meta.pc.{ParentSymbols, SymbolDocumentation, SymbolSearch, SymbolSearchVisitor}
import ba.sake.basamake.index.{SymbolDefinition, SymbolUtils}
import ba.sake.basamake.index.indexing.WorkspaceIndex

/** The presentation compiler's smallest useful view of Basamake's workspace.
  * It supplies definition locations and Scaladoc for indexed workspace symbols;
  * dependency-source and Javadoc lookup remain responsibilities of a future
  * source-archive search. */
private[lsp] final class PresentationCompilerSymbolSearch(workspaceIndex: WorkspaceIndex) extends SymbolSearch {
  override def documentation(symbol: String, parents: ParentSymbols): Optional[SymbolDocumentation] =
    workspaceIndex.getSymbol(symbol, Nil).flatMap(documentationFor).toJava

  override def definition(symbol: String, source: URI): java.util.List[Location] =
    workspaceIndex.getSymbol(symbol, Nil).map(locationFor).toList.asJava

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
        .map(_.trim.stripPrefix("*").stripPrefix(" "))
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
