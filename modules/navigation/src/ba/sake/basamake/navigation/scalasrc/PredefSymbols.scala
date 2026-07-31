package ba.sake.basamake.navigation.scalasrc

/** Static map of names available via Scala's implicit `Predef` + default imports
  * (`scala.*`, `scala.Predef.*`) to their canonical SemanticDB symbol strings.
  *
  * Used as a fallback layer in reference resolution when local scope, owner
  * chain, and explicit/wildcard imports all miss. Pure data, no JVM reflection,
  * no `Class.forName` — fast and side-effect-free.
  *
  * Extend the map as more default-imported names are needed.
  */
object PredefSymbols {

  /** Type-shaped entries — used when isType=true in context (e.g. `val x: List[Int]`).
    * Keys are source-level names, values end with `#`. */
  private val typeMap: Map[String, String] = Map(
    // ── scala.collection.immutable types ────────────────────────────
    "List"   -> "scala/collection/immutable/List#",
    "Map"    -> "scala/collection/immutable/Map#",
    "Set"    -> "scala/collection/immutable/Set#",
    "Seq"    -> "scala/collection/immutable/Seq#",
    "Vector" -> "scala/collection/immutable/Vector#",
    // ── scala (top-level) types ────────────────────────────────────
    "Option" -> "scala/Option#",
    "Some"   -> "scala/Some#",
    "Either" -> "scala/Either#",
    "Left"   -> "scala/Left#",
    "Right"  -> "scala/Right#",
    // ── scala value types / primitives ─────────────────────────────
    "Int"     -> "scala/Int#",
    "Long"    -> "scala/Long#",
    "Double"  -> "scala/Double#",
    "Float"   -> "scala/Float#",
    "Byte"    -> "scala/Byte#",
    "Short"   -> "scala/Short#",
    "Char"    -> "scala/Char#",
    "Boolean" -> "scala/Boolean#",
    "Unit"    -> "scala/Unit#",
    "Array"   -> "scala/Array#",
    // ── java.lang types ────────────────────────────────────────────
    "String" -> "java/lang/String#",
    "Object" -> "java/lang/Object#"
  )

  /** Term-shaped entries — used when isType=false (companion objects, vals, methods).
    * Keys are source-level names, values end with `.` or `).` / `(+N).`. */
  private val termMap: Map[String, String] = Map(
    // ── scala.collection.immutable companion objects ────────────────
    "List"   -> "scala/collection/immutable/List.",
    "Map"    -> "scala/collection/immutable/Map.",
    "Set"    -> "scala/collection/immutable/Set.",
    "Seq"    -> "scala/collection/immutable/Seq.",
    "Vector" -> "scala/collection/immutable/Vector.",
    "Nil"    -> "scala/collection/immutable/Nil.",
    // ── scala (top-level) term objects ─────────────────────────────
    "None"    -> "scala/None.",
    "Console" -> "scala/Console.",
    "Predef"  -> "scala/Predef.",
    // ── scala.Predef methods (term, method descriptor) ─────────────
    "println"  -> "scala/Predef.println().",
    "print"    -> "scala/Predef.print().",
    "identity" -> "scala/Predef.identity().",
    "assert"   -> "scala/Predef.assert().",
    "require"  -> "scala/Predef.require().",
    "???"      -> "scala/Predef.`???`()."
  )

  /** Look up a name in the Predef/default-import table.
    *
    * When `isType` is true, only the type map is consulted.
    * When `isType` is false (term context), the term map is consulted.
    */
  def lookup(name: String, isType: Boolean): Option[String] =
    if isType then typeMap.get(name)
    else termMap.get(name)

  /** Shape-agnostic raw lookup. Returns the stored symbol string for `name`
    * regardless of type/term shape. Checks both maps. Useful when the caller
    * wants to inspect the entry and branch on its suffix.
    */
  def rawLookup(name: String): Option[String] =
    termMap.get(name).orElse(typeMap.get(name))
}
