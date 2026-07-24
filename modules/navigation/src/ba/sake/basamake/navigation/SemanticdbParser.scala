package ba.sake.basamake.navigation

import java.io.InputStream
import scala.meta.internal.semanticdb
import org.eclipse.lsp4j.SymbolKind

/** Parses compiler-produced `.semanticdb` protobuf files into the same
  * `SourceSemanticdb` format as `ScalaSourceParser` and `JavaSourceParser`.
  *
  * Constructor takes path (for location metadata) and input stream (protobuf bytes).
  * The parser instance is throw-away — one parse per file.
  */
class SemanticdbParser(path: os.Path, is: InputStream) {

  private val defs = Vector.newBuilder[SourceSymbolDefinition]
  private val refs = Vector.newBuilder[SourceSymbolReference]

  def parse(): SourceSemanticdb = {
    try {
      val bytes = is.readAllBytes()
      val documents = semanticdb.TextDocuments.parseFrom(bytes)
      documents.documents.foreach(extractDocument)
      SourceSemanticdb(defs.result(), refs.result())
    } catch {
      case _: Exception =>
        SourceSemanticdb(Vector.empty, Vector.empty)
    }
  }

  private def extractDocument(doc: semanticdb.TextDocument): Unit = {
    val symbolInfoById: Map[String, semanticdb.SymbolInformation] =
      doc.symbols.toList.map(si => si.symbol -> si).toMap

    doc.occurrences.foreach { occ =>
      occ.range.foreach { range =>
        val loc = toSymbolLocation(range)
        if occ.symbol.nonEmpty then
          if occ.role.isDefinition then
            val info = symbolInfoById.get(occ.symbol)
            val name = info.flatMap(i => Option(i.displayName).filter(_.nonEmpty))
              .getOrElse(extractName(occ.symbol))
            val kind = info.map(i => mapKind(i.kind)).getOrElse(SymbolKind.Object)
            defs += SourceSymbolDefinition(name, kind, Symbol(occ.symbol), loc)
          else
            refs += SourceSymbolReference(Symbol(occ.symbol), loc)
      }
    }
  }

  /** Extract a human-readable name from a SemanticDB symbol.
    * Strips owner chain and descriptor, returns the bare name.
    * E.g., "com/example/Foo#" → "Foo", "com/example/Foo#run()." → "run" */
  private def extractName(symbol: String): String = {
    // Find the last segment before the descriptor
    val descriptorStart = symbol.lastIndexWhere(c => c == '#' || c == '.' || c == '(' || c == '[' || c == '/')
    if descriptorStart < 0 then symbol
    else {
      // Walk back to find the start of the last name segment
      val beforeDescriptor = symbol.substring(0, descriptorStart)
      val nameStart = beforeDescriptor.lastIndexWhere(c => c == '/' || c == '#' || c == '.')
      val raw = symbol.substring(nameStart + 1, descriptorStart)
      // Strip backticks if present
      if raw.nonEmpty && raw.head == '`' && raw.last == '`' then raw.substring(1, raw.length - 1)
      else raw
    }
  }

  /** Map scalameta SemanticDB Kind to lsp4j SymbolKind. */
  private def mapKind(kind: semanticdb.SymbolInformation.Kind): SymbolKind =
    kind.toString match {
      case "CLASS" | "ENUM"               => SymbolKind.Class
      case "INTERFACE" | "TRAIT"          => SymbolKind.Interface
      case "OBJECT" | "PACKAGE_OBJECT"    => SymbolKind.Object
      case "PACKAGE"                      => SymbolKind.Package
      case "METHOD"                       => SymbolKind.Method
      case "CONSTRUCTOR"                  => SymbolKind.Constructor
      case "FIELD"                        => SymbolKind.Field
      case "VAL"                          => SymbolKind.Property
      case "VAR"                          => SymbolKind.Variable
      case "PARAMETER" | "LOCAL"          => SymbolKind.Variable
      case "TYPE" | "TYPE_PARAMETER"      => SymbolKind.TypeParameter
      case "ENUM_CASE"                    => SymbolKind.EnumMember
      case _                              => SymbolKind.Object
    }

  private def toSymbolLocation(range: semanticdb.Range): SymbolLocation =
    SymbolLocation(
      path = path,
      range = SymbolLocationRange(
        startLine = range.startLine,
        startCharacter = range.startCharacter,
        endLine = range.endLine,
        endCharacter = range.endCharacter
      )
    )
}

object SemanticdbParser {
  def apply(path: os.Path): SemanticdbParser =
    new SemanticdbParser(path, os.read.inputStream(path))
  def apply(bytes: Array[Byte]): SemanticdbParser =
    new SemanticdbParser(os.pwd / "<inmemory>.semanticdb", new java.io.ByteArrayInputStream(bytes))
}
