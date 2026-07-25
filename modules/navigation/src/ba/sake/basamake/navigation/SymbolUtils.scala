package ba.sake.basamake.navigation

/** Pure SemanticDB symbol encoder. Produces canonical global symbols as specified by
  * the SemanticDB v4 specification:
  *   https://github.com/scalameta/scalameta/blob/main/docs/semanticdb/specification.md
  *
  * This encoder is used only by dependency-source parsers (ScalaSourceParser,
  * JavaSourceParser) to synthesize definition keys that match compiler-produced
  * SemanticDB symbols byte-for-byte.
  *
  * Names that are not valid Java identifiers are backtick-escaped per the spec.
  * For example `<init>` becomes `` `<init>` `` and operator names like `::` become `` `::` ``.
  */
object SymbolUtils {

  /** Returns true if `name` can appear unescaped in a SemanticDB symbol.
    * SemanticDB only backtick-escapes names containing non-identifier characters
    * (e.g. `<init>`, `::`). Names made of identifier characters, including Java
    * keywords like `package`, are NOT escaped — they are valid symbol names.
    *
    * Rule: first character must be a Java identifier start (letter, _, $),
    * remaining characters must be Java identifier parts (letter, digit, _, $).
    */
  def isJavaIdentifier(name: String): Boolean =
    if name.isEmpty then false
    else
      Character.isJavaIdentifierStart(name.codePointAt(0))
      && name.codePoints().allMatch(Character.isJavaIdentifierPart(_))

  /** Escapes a source-level name for use within a SemanticDB descriptor.
    * If the name is a valid Java identifier, returns it unchanged.
    * Otherwise wraps it in backticks. Does not double-wrap names that
    * already start and end with backticks.
    */
  def escapedName(name: String): Symbol =
    Symbol(if name.nonEmpty && name.head == '`' && name.last == '`' then name
    else if isJavaIdentifier(name) then name
    else s"`$name`")

  /** Encodes a package owner prefix from one or more package segments.
    * Example: `packageOwner(List("com", "example"))` → `"com/example/"`
    * Example: `packageOwner(Nil)` → `"_empty_/"`
    */
  def packageOwner(segments: List[String]): Symbol =
    Symbol(if segments.isEmpty then "_empty_/"
    else segments.mkString("", "/", "/"))

  /** Encodes a package owner from a dot-separated string.
    * Example: `packageOwner("com.example")` → `"com/example/"`
    * Example: `packageOwner("")` → `"_empty_/"`
    */
  def packageOwner(dotted: String): Symbol =
    packageOwner(if dotted.isEmpty then Nil else dotted.split('.').toList)

  /** Appends a type descriptor (`#`) to an owner.
    * Example: `typeSymbol("com/example/", "Outer")` → `"com/example/Outer#"`
    */
  def typeSymbol(owner: Symbol, name: String): Symbol =
    Symbol(s"${owner.value}${escapedName(name)}#")

  /** Appends a term descriptor (`.`) to an owner.
    * Example: `termSymbol("com/example/Outer#", "field")` → `"com/example/Outer#field."`
    */
  def termSymbol(owner: Symbol, name: String): Symbol =
    Symbol(s"${owner.value}${escapedName(name)}.")

  /** Appends a method descriptor (`().` or `(+N).`) to an owner.
    * `overloadIndex` 0 → `().`, 1 → `(+1).`, etc.
    * Example: `methodSymbol("com/example/Outer#", "run", 0)` → `"com/example/Outer#run()."`
    * Example: `methodSymbol("com/example/Outer#", "run", 1)` → `"com/example/Outer#run(+1)."`
    */
  def methodSymbol(owner: Symbol, name: String, overloadIndex: Int): Symbol =
    val disambiguator = if overloadIndex == 0 then "" else s"+$overloadIndex"
    Symbol(s"${owner.value}${escapedName(name)}($disambiguator).")

  /** Appends a constructor descriptor (`` `<init>`().`` or `` `<init>`(+N).``) to an owner.
    * `overloadIndex` 0 → `` `<init>`().``, 1 → `` `<init>`(+1).``, etc.
    */
  def constructorSymbol(owner: Symbol, overloadIndex: Int): Symbol =
    val disambiguator = if overloadIndex == 0 then "" else s"+$overloadIndex"
    Symbol(s"${owner.value}${escapedName("<init>")}($disambiguator).")

  /** Produces a document-scoped local symbol per SemanticDB v4 spec.
    * Format: `local<N>`. Counter resets per file.
    * Example: `localSymbol(0)` → `"local0"`, `localSymbol(42)` → `"local42"`.
    */
  def localSymbol(index: Int): Symbol =
    Symbol(s"local$index")

  /** Returns true for compiler-produced local symbols (`local0`, `local2+1`).
    * Rejects global symbols that happen to start with "local" (e.g. `localDate#`).
    * Regex: `^local\d+(\+\d+)?$` per SemanticDB v4 spec. */
  def isLocalSymbol(symbol: String): Boolean =
    symbol.matches("^local\\d+(\\+\\d+)?$$")


}
