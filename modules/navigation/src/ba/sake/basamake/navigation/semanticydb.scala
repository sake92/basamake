package ba.sake.basamake.navigation

// Scalameta/Javaparser generate this "Semanticydb" for definitions and references.
// We cache them for source JARs and JDKs.
/** Unified index for one JAR or JDK */
/*
case class Semanticydb(
    definitions: Vector[SemanticydbSymbolDefinition],
    references: Vector[SemanticydbSymbolReference]
)

case class SemanticydbSymbolDefinition(symbol: Symbol, location: SymbolLocation)

case class SemanticydbSymbolReference(symbol: Symbol, location: SymbolLocation)
*/

// TODO serialize as lmdb file
// TODO optimize for lmdb, pack the uris separately and index into it??
class BasamakeIndex(
    values: Map[Symbol, SymbolLocation],
   // uris: Array[String],
)

case class BasamakeIndexMetadata(
    path: os.Path,
    lastModified: Long,
    packages: Set[String]
) //derives JsonRW