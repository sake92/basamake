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
  def escapedName(name: String): String =
    if name.nonEmpty && name.head == '`' && name.last == '`' then name
    else if isJavaIdentifier(name) then name
    else s"`$name`"

  /** Encodes a package owner prefix from one or more package segments.
    * Example: `packageOwner(List("com", "example"))` → `"com/example/"`
    * Example: `packageOwner(Nil)` → `"_empty_/"`
    */
  def packageOwner(segments: List[String]): String =
    if segments.isEmpty then "_empty_/"
    else segments.mkString("", "/", "/")

  /** Encodes a package owner from a dot-separated string.
    * Example: `packageOwner("com.example")` → `"com/example/"`
    * Example: `packageOwner("")` → `"_empty_/"`
    */
  def packageOwner(dotted: String): String =
    packageOwner(if dotted.isEmpty then Nil else dotted.split('.').toList)

  /** Appends a type descriptor (`#`) to an owner.
    * Example: `typeSymbol("com/example/", "Outer")` → `"com/example/Outer#"`
    */
  def typeSymbol(owner: String, name: String): String =
    s"${owner}${escapedName(name)}#"

  /** Appends a term descriptor (`.`) to an owner.
    * Example: `termSymbol("com/example/Outer#", "field")` → `"com/example/Outer#field."`
    */
  def termSymbol(owner: String, name: String): String =
    s"${owner}${escapedName(name)}."

  /** Appends a method descriptor (`().` or `(+N).`) to an owner.
    * `overloadIndex` 0 → `().`, 1 → `(+1).`, etc.
    * Example: `methodSymbol("com/example/Outer#", "run", 0)` → `"com/example/Outer#run()."`
    * Example: `methodSymbol("com/example/Outer#", "run", 1)` → `"com/example/Outer#run(+1)."`
    */
  def methodSymbol(owner: String, name: String, overloadIndex: Int): String =
    val disambiguator = if overloadIndex == 0 then "" else s"+$overloadIndex"
    s"${owner}${escapedName(name)}($disambiguator)."

  /** Appends a constructor descriptor (`` `<init>`().`` or `` `<init>`(+N).``) to an owner.
    * `overloadIndex` 0 → `` `<init>`().``, 1 → `` `<init>`(+1).``, etc.
    */
  def constructorSymbol(owner: String, overloadIndex: Int): String =
    val disambiguator = if overloadIndex == 0 then "" else s"+$overloadIndex"
    s"${owner}${escapedName("<init>")}($disambiguator)."

  /** Produces a document-scoped local symbol per SemanticDB v4 spec.
    * Format: `local<N>`. Counter resets per file.
    * Example: `localSymbol(0)` → `"local0"`, `localSymbol(42)` → `"local42"`.
    */
  def localSymbol(index: Int): String =
    s"local$index"

  /** Method/constructor parameter symbol. Format: `<methodSymbol>(<name>)`.
    * Example: `parameterSymbol("p/O#f(+1).", "x")` → `"p/O#f(+1).(x)"`.
    * The method symbol already ends with `.`; we just append `(<paramName>)`.
    */
  def parameterSymbol(methodSymbol: String, paramName: String): String =
    s"${methodSymbol}(${escapedName(paramName)})"

  /** Type-parameter symbol. Format: `<ownerTypeSymbol>[<name>]`.
    * Example: `typeParamSymbol("com/example/Show#", "T")` → `"com/example/Show#[T]"`.
    * The owner must be a TYPE symbol (ending with `#`).
    */
  def typeParamSymbol(ownerTypeSymbol: String, name: String): String =
    s"${ownerTypeSymbol}[${escapedName(name)}]"

  /** Returns true for compiler-produced local symbols (`local0`, `local2+1`).
    * Rejects global symbols that happen to start with "local" (e.g. `localDate#`).
    * Regex: `^local\d+(\+\d+)?$` per SemanticDB v4 spec. */
  def isLocalSymbol(symbol: String): Boolean =
    symbol.matches("^local\\d+(\\+\\d+)?$$")

  def isTypeSymbol(symbol: String): Boolean =
    symbol.endsWith("#")

  /** Dotted package of a global symbol, or None for the default (_empty_) package
    * and local symbols.
    * Example: `packageOf("org/apache/commons/net/FTPClient#")` → `Some("org.apache.commons.net")`
    * Example: `packageOf("Foo#")` → `None`
    * Example: `packageOf("_empty_/Foo#")` → `None`
    */
  def packageOf(symbol: String): Option[String] = {
    val withoutPkg = symbol.startsWith("_empty_/") || !symbol.contains('/')
    if withoutPkg then None
    else {
      val pkg = symbol.substring(0, symbol.lastIndexOf('/')).replace('/', '.')
      Some(pkg)
    }
  }

  /** Derives the display short name from a SemanticDB symbol: strips the owner
    * prefix and all descriptor suffixes, keeping just the member name.
    * Examples:
    *   `com/example/Outer#run().`            → `run`
    *   `com/example/Outer#run(+1).`          → `run`
    *   `com/example/Outer#field.`            → `field`
    *   `com/example/Outer#`                  → `Outer`
    *   `com/example/Outer#run().(x)`         → `x`
    *   `` com/example/Outer#`<init>`(). ``   → `` `<init>` ``
    *   `_empty_/bla$package.`                → `bla$package`
    *   `com/example/Foo#bar[T]().`           → `bar`
    *   `java/lang/String#substring(II).`     → `substring`
    *   `utils.` (short/best-effort symbol)   → `utils`
    */
  def shortNameOf(symbol: String): String = {
    val afterOwner = symbol.drop(symbol.lastIndexOf('/') + 1)
    // parameter symbol: `<method>.(name)`
    val paramRe = """^.*\.\(([^()]*)\)$""".r
    afterOwner match {
      case paramRe(name) => return name
      case _             => ()
    }
    // strip descriptor suffixes from the right: `().`, `(+1).`, `[T].`, `#`, trailing `.`
    var cur = afterOwner
    var changed = true
    while (changed) {
      changed = false
      if (cur.endsWith(".")) { cur = cur.dropRight(1); changed = true }
      else if (cur.endsWith("#")) { cur = cur.dropRight(1); changed = true }
      else if (cur.endsWith(")")) {
        val open = cur.lastIndexOf('(')
        if (open > 0) { cur = cur.take(open); changed = true }
      } else if (cur.endsWith("]")) {
        val open = cur.lastIndexOf('[')
        if (open > 0) { cur = cur.take(open); changed = true }
      }
    }
    // take the last name segment (after the type `#` or term `.` separator)
    val lastSep = math.max(cur.lastIndexOf('#'), cur.lastIndexOf('.'))
    if (lastSep >= 0) cur.drop(lastSep + 1) else cur
  }

}
