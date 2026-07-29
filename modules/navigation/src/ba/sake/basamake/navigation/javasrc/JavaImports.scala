package ba.sake.basamake.navigation.javasrc

import com.github.javaparser.ast.ImportDeclaration
import ba.sake.basamake.navigation.{ImportScopeData, SymbolUtils}

/** Parses a single javaparser `ImportDeclaration` into a shared `ImportScopeData`.
  * Java single-type import `import a.b.C` → explicit entry `C -> a/b/C#`.
  * Java on-demand import `import a.b.*` → wildcard prefix `a/b/`.
  */
object JavaImports {

  def parse(imp: ImportDeclaration): ImportScopeData = {
    val nameStr = imp.getNameAsString // e.g. "a.b.C" or "a.b.*"
    if (imp.isAsterisk) {
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
}
