package ba.sake.basamake.navigation

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

  /** Keys are source-level names. Values are canonical SemanticDB symbol strings,
    * matching what the Scala 3 compiler would emit for that default name.
    *
    * Type-shaped values end with `#` (class/trait); term-shaped values end with
    * `.` (object/val) or `).` / `(+N).` (method).
    */
  private val map: Map[String, String] = Map(
    // ── scala.collection.immutable ─────────────────────────────────
    "List"   -> "scala/collection/immutable/List.",     // companion object (call site)
    "Map"    -> "scala/collection/immutable/Map.",
    "Set"    -> "scala/collection/immutable/Set.",
    "Seq"    -> "scala/collection/immutable/Seq.",
    "Vector" -> "scala/collection/immutable/Vector.",
    "Nil"    -> "scala/collection/immutable/Nil.",
    // ── scala (top-level) types ────────────────────────────────────
    "Option" -> "scala/Option#",
    "Some"   -> "scala/Some#",
    "None"   -> "scala/None.",   // object — term only
    "Either" -> "scala/Either#",
    "Left"   -> "scala/Left#",
    "Right"  -> "scala/Right#",
    // ── scala (top-level) value types / primitives ─────────────────
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
    // ── java.lang ──────────────────────────────────────────────────
    "String" -> "java/lang/String#",
    "Object" -> "java/lang/Object#",
    // ── scala.Console / Predef objects ─────────────────────────────
    "Console" -> "scala/Console.",
    "Predef"  -> "scala/Predef.",
    // ── scala.Predef methods (term, method descriptor) ─────────────
    "println"  -> "scala/Predef.println().",
    "print"    -> "scala/Predef.print().",
    "identity" -> "scala/Predef.identity().",
    "assert"   -> "scala/Predef.assert().",
    "require"  -> "scala/Predef.require().",
    "???"      -> "scala/Predef.`???`()."   // backtick-escaped operator name
  )

  /** Look up a name in the Predef/default-import table.
    *
    * When `isType` is true, only type-shaped symbols (ending `#`) are returned.
    * When `isType` is false (term context), term-shaped symbols (ending `.` or
    * `).` / `(+N).`) are returned.
    *
    * Note: the same source name can be both a type and a term (e.g. `List` is the
    * trait `scala.collection.immutable.List#` AND the companion object `…List.`).
    * `PredefSymbols` stores one entry per source name — the form most likely to
    * appear at a reference. For ambiguous cases the caller should try the `isType`
    * path first, then fall back to the opposite shape; this `lookup` enforces
    * shape via the `isType` parameter but exposes [[rawLookup]] for shape-agnostic
    * checks.
    */
  def lookup(name: String, isType: Boolean): Option[String] =
    map.get(name).filter { sym =>
      if isType then sym.endsWith("#")
      else !sym.endsWith("#") // term-shaped: ends with . ) or (+N).
    }

  /** Shape-agnostic raw lookup. Returns the stored symbol string for `name`
    * regardless of type/term shape. Useful when the caller wants to inspect the
    * entry and branch on its suffix.
    */
  def rawLookup(name: String): Option[String] = map.get(name)
}