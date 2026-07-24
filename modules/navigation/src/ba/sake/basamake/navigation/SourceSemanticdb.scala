package ba.sake.basamake.navigation

import org.eclipse.lsp4j.SymbolKind

// results of parsing: source files (Scala, Java) to extract definitions and references

final case class SourceSemanticdb(
    definitions: Vector[SourceSymbolDefinition],
    references: Vector[SourceSymbolReference]
)

final case class SourceSymbolDefinition(
    name: String,
    kind: SymbolKind,
    symbol: Symbol,
    location: SymbolLocation,
)

final case class SourceSymbolReference(
    symbol: Symbol,
    location: SymbolLocation
)
