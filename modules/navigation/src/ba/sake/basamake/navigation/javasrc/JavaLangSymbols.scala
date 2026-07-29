package ba.sake.basamake.navigation.javasrc

/** Static map of names available via Java's implicit `java.lang.*` import
  * plus common `java.util.*` types, to their canonical SemanticDB symbol strings.
  *
  * Used as a fallback layer in reference resolution when local scope, owner
  * chain, and explicit/wildcard imports all miss. Pure data, no JVM reflection.
  *
  * Differs from `PredefSymbols` (Scala-specific) — this table holds Java
  * default-imported types only.
  */
object JavaLangSymbols {

  /** Keys are source-level names. Values are canonical SemanticDB symbol strings.
    * Type-shaped values end with `#` (class/interface/enum). Term-shaped values
    * end with `.` (static field/method are resolved via SymbolTable, not here).
    */
  private val map: Map[String, String] = Map(
    // ── java.lang types ───────────────────────────────────────────
    "String" -> "java/lang/String#",
    "Object" -> "java/lang/Object#",
    "Integer" -> "java/lang/Integer#",
    "Long"    -> "java/lang/Long#",
    "Double"  -> "java/lang/Double#",
    "Float"   -> "java/lang/Float#",
    "Boolean" -> "java/lang/Boolean#",
    "Byte"    -> "java/lang/Byte#",
    "Short"   -> "java/lang/Short#",
    "Character" -> "java/lang/Character#",
    "Void"      -> "java/lang/Void#",
    "Number"    -> "java/lang/Number#",
    "Class"     -> "java/lang/Class#",
    "ClassLoader" -> "java/lang/ClassLoader#",
    "Thread"    -> "java/lang/Thread#",
    "Math"      -> "java/lang/Math#",
    "System"    -> "java/lang/System#",
    "Throwable" -> "java/lang/Throwable#",
    "Exception" -> "java/lang/Exception#",
    "RuntimeException" -> "java/lang/RuntimeException#",
    "Error"     -> "java/lang/Error#",
    "StringBuilder"   -> "java/lang/StringBuilder#",
    "StringBuffer"    -> "java/lang/StringBuffer#",
    "Iterable"  -> "java/lang/Iterable#",
    "Runnable"  -> "java/lang/Runnable#",
    "Comparable" -> "java/lang/Comparable#",
    "Appendable" -> "java/lang/Appendable#",
    "CharSequence" -> "java/lang/CharSequence#",
    // ── java.util ─────────────────────────────────────────────────
    "List"        -> "java/util/List#",
    "Map"         -> "java/util/Map#",
    "Set"         -> "java/util/Set#",
    "Collection"  -> "java/util/Collection#",
    "ArrayList"   -> "java/util/ArrayList#",
    "HashMap"     -> "java/util/HashMap#",
    "HashSet"     -> "java/util/HashSet#",
    "Iterator"    -> "java/util/Iterator#",
    "Optional"    -> "java/util/Optional#",
    // ── java.lang annotations ─────────────────────────────────────
    "Override"            -> "java/lang/Override#",
    "Deprecated"          -> "java/lang/Deprecated#",
    "SuppressWarnings"    -> "java/lang/SuppressWarnings#",
    "FunctionalInterface"  -> "java/lang/FunctionalInterface#"
  )

  /** Look up a name in the Java default-import table.
    *
    * When `isType` is true, only type-shaped symbols (ending `#`) are returned.
    * When `isType` is false (term context), term-shaped symbols (ending `.` or `).`)
    * are returned. Since this table stores only type symbols (`#`), term-context
    * lookups always return `None`.
    */
  def lookup(name: String, isType: Boolean): Option[String] =
    map.get(name).filter { sym =>
      if isType then sym.endsWith("#")
      else !sym.endsWith("#")
    }

  /** Shape-agnostic raw lookup. Returns the stored symbol string for `name`
    * regardless of type/term shape. Useful for owner resolution.
    */
  def rawLookup(name: String): Option[String] = map.get(name)
}
