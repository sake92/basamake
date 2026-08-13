package ba.sake.basamake.navigation.javasrc

import com.github.javaparser.ast.ImportDeclaration
import ba.sake.basamake.navigation.{ImportScopeData, SymbolUtils}

/** Parses a single javaparser `ImportDeclaration` into a shared `ImportScopeData`.
  *
  * Non-static:
  *   `import a.b.C`      → explicit `C -> a/b/C#`
  *   `import a.b.*`      → wildcard prefix `a/b/`
  *
  * Static (the name refers to a MEMBER of a type, not a package):
  *   `import static a.b.C.member` → explicit `member -> a/b/C#member.` PLUS
  *     methodImports `member -> a/b/C#` — the member may be a method, and a
  *     call site then binds via the owner's overload scan (a method symbol
  *     `a/b/C#member().` is never matched by the plain term symbol).
  *   `import static a.b.C.*`      → wildcard owner `a/b/C#` — a TYPE, not a
  *     package: members probe term/method symbols of the type directly.
  */
object JavaImports {

  def parse(imp: ImportDeclaration): ImportScopeData = {
    val nameStr = imp.getNameAsString // e.g. "a.b.C", "a.b.*", "a.b.C.member", "a.b.C.*"
    if (imp.isStatic) {
      val segments = nameStr.split('.').toList.filter(_ != "*")
      if (imp.isAsterisk) {
        // `import static a.b.C.*` — javaparser's getNameAsString DROPS the
        // trailing `*` (unlike non-static on-demand imports), so ALL segments
        // are the type path. The wildcard owner is the TYPE symbol: members
        // probe term/method symbols of `a/b/C#`, not of a package.
        val owner = typeOwnerOf(segments)
        ImportScopeData(explicit = Map.empty, wildcards = List(owner), unimports = Set.empty)
      } else {
        // `import static a.b.C.member` — the member may be a field (term) or
        // a method; store both the term symbol (explicit) and the owner for
        // overload probing (methodImports).
        val memberName = segments.last
        val owner = typeOwnerOf(segments.init)
        ImportScopeData(
          explicit = Map(memberName -> SymbolUtils.termSymbol(owner, memberName)),
          wildcards = Nil,
          unimports = Set.empty,
          methodImports = Map(memberName -> owner)
        )
      }
    } else if (imp.isAsterisk) {
      // On-demand import: prefix is package path of import
      val segments = nameStr.split('.').toList.filter(_ != "*")
      val prefix = SymbolUtils.packageOwner(segments)
      ImportScopeData(explicit = Map.empty, wildcards = List(prefix), unimports = Set.empty)
    } else {
      // Single-type import: store as type symbol under prefix
      val segments = nameStr.split('.').toList
      val typeName = segments.last
      val pkgSegs = segments.init
      val prefix = SymbolUtils.packageOwner(pkgSegs)
      val typeSym = SymbolUtils.typeSymbol(prefix, typeName)
      ImportScopeData(explicit = Map(typeName -> typeSym), wildcards = Nil, unimports = Set.empty)
    }
  }

  /** Type symbol of a dotted type path, e.g. [a, b, C] → `a/b/C#`. */
  private def typeOwnerOf(segments: List[String]): String = {
    val pkgSegs = segments.init
    val typeName = segments.last
    SymbolUtils.typeSymbol(SymbolUtils.packageOwner(pkgSegs), typeName)
  }
}
