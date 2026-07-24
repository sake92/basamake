package ba.sake.basamake.navigation

// TODO just a WIP of a global index.

// TODO perfy?
// val sharedUri = doc.uri.intern()

class GlobalSymbolIndex {
  // Globalne brze lookup mape u RAM-u
  val definitions = collection.mutable.Map[Symbol, SymbolLocation]()
  val references  = collection.mutable.Map[Symbol, Vector[SymbolLocation]]()

  // 1. Učitavanje keširanih JAR-ova i JDK-a sa diska (brzo kao munja)
 /* def loadJarIndex(jarBaseUri: String, index: Semanticydb): Unit =
    for defn <- index.definitions do
      // Spajamo bazni URI JAR-a i relativnu putanju iz keša
      val fullLocation = defn.location.copy(uri = s"$jarBaseUri!/${defn.location.uri}")
      definitions(defn.symbol) = fullLocation

    for ref <- index.references do
      val fullLocation = ref.location.copy(uri = s"$jarBaseUri!/${ref.location.uri}")
      val current = references.getOrElse(ref.symbol, Vector.empty)
      references(ref.symbol) = current :+ fullLocation*/

  // 2. Parsiranje otvorenih/lokalnih fajlova na živo (pri kucanju)
  def updateLocalFile(fileUri: String, fileDefinitions: Vector[SymbolDefinition], fileReferences: Vector[SymbolReference]): Unit =
    ()
    // Osvježi samo podatke za taj lokalni fajl...
}