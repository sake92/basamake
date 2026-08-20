package ba.sake.basamake.index.javasrc

import com.github.javaparser.Range as JpRange
import com.github.javaparser.Position
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.expr.Name
import ba.sake.basamake.index.{ImportScopeData, ScopeStack, SymbolTable, SymbolUtils}

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

  def parse(imp: ImportDeclaration, table: SymbolTable): ImportScopeData = {
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
        val termSym = SymbolUtils.termSymbol(owner, memberName)
        // dep member: record the candidate too (verified at request time)
        val cands = if (table.get(termSym).isEmpty) Map(memberName -> List(termSym)) else Map.empty
        ImportScopeData(
          explicit = Map(memberName -> termSym),
          wildcards = Nil,
          unimports = Set.empty,
          methodImports = Map(memberName -> owner),
          candidates = cands
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
      // dep type: the table probe misses at parse time — record the candidate
      // so body usages (extends/implements/field types/new) emit it and
      // request-time verification resolves it against the file's dep candidates
      val cands = if (table.get(typeSym).isEmpty) Map(typeName -> List(typeSym)) else Map.empty
      ImportScopeData(explicit = Map(typeName -> typeSym), wildcards = Nil, unimports = Set.empty, candidates = cands)
    }
  }

  /** Type symbol of a dotted type path, e.g. [a, b, C] → `a/b/C#`. */
  private def typeOwnerOf(segments: List[String]): String = {
    val pkgSegs = segments.init
    val typeName = segments.last
    SymbolUtils.typeSymbol(SymbolUtils.packageOwner(pkgSegs), typeName)
  }

  /** Emit reference occurrences for an import statement (per-segment):
    *   - single-type `a.b.C`       → `C`      → a/b/C#
    *   - static single `a.b.C.m`   → `C` + `m` → a/b/C# + (method via overload probe, else term)
    *   - static wildcard `a.b.C.*` → `C`      → a/b/C#
    *   - wildcard `a.b.*`          → nothing
    * Package segments are SKIPPED — Java has no package objects (Scala emits
    * package symbols there instead). Symbols are deterministic from the
    * statement itself; the cursor-time lookup decides whether they resolve
    * (workspace table, owning-jar dep candidates, or the implicit JDK). */
  def emitRefs(
      imp: ImportDeclaration,
      symbolTable: SymbolTable,
      emit: (java.util.Optional[JpRange], String) => Unit
  ): Unit = {
    val name = imp.getName
    // full dotted path, `*` stripped (same normalization as `parse` above —
    // `Name.getIdentifier` alone returns only the LAST segment)
    val segments = imp.getNameAsString.split('.').toList.filter(_ != "*")
    if (!imp.isAsterisk) {
      if (imp.isStatic) {
        val typeIdx = segments.length - 2
        val memberIdx = segments.length - 1
        rangeOfSegment(name, segments, typeIdx).foreach { r =>
          emit(java.util.Optional.of(r), typeOwnerOf(segments.take(typeIdx + 1)))
        }
        val owner = typeOwnerOf(segments.take(memberIdx))
        val member = segments(memberIdx)
        val sym = ScopeStack.findMethodOverload(owner, member, symbolTable)
          .getOrElse(SymbolUtils.termSymbol(owner, member))
        rangeOfSegment(name, segments, memberIdx).foreach { r =>
          emit(java.util.Optional.of(r), sym)
        }
      } else {
        val idx = segments.length - 1
        val sym = SymbolUtils.typeSymbol(SymbolUtils.packageOwner(segments.init), segments(idx))
        rangeOfSegment(name, segments, idx).foreach { r =>
          emit(java.util.Optional.of(r), sym)
        }
      }
    } else if (imp.isStatic) {
      // `import static a.b.C.*` — the `*` is not part of the Name node, so all
      // segments are the type path; the wildcard owner is the TYPE symbol.
      val idx = segments.length - 1
      rangeOfSegment(name, segments, idx).foreach { r =>
        emit(java.util.Optional.of(r), typeOwnerOf(segments))
      }
    }
    // non-static wildcard: no individual refs
  }

  /** Range of the idx-th dotted segment inside `name` (Java imports are
    * single-line). javaparser ranges are 1-based with END INCLUSIVE. */
  private def rangeOfSegment(name: Name, segments: List[String], idx: Int): Option[JpRange] =
    if (idx < 0 || idx >= segments.length) None
    else
      Option(name.getRange.orElse(null)).map { full =>
        val prefixLen = segments.take(idx).map(_.length + 1).sum
        val segLen = segments(idx).length
        val begin = full.begin
        new JpRange(
          new Position(begin.line, begin.column + prefixLen),
          new Position(begin.line, begin.column + prefixLen + segLen - 1)
        )
      }
}
