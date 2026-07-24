package ba.sake.basamake.navigation

import java.io.InputStream
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.`type`.{ClassOrInterfaceType, Type}
import com.github.javaparser.ast.expr.{Name, SimpleName}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.SymbolKind
import scala.util.control.NonFatal

/** Parses Java source files to extract SemanticDB-compatible symbol definitions
  * and same-file references. Single-pass traversal over JavaParser AST.
  *
  * Constructor takes path (for location metadata) and input stream (for content).
  * The parser instance is throw-away — one parse per file.
  */
class JavaSourceParser(path: os.Path, is: InputStream) extends StrictLogging {

  // No shared JavaParser instance — JavaCC-generated parser is stateful (token, jj_nt).
  // Concurrent extraction corrupts token stream: AssertionError "reference was unexpectedly null".

  // ── Accumulators (class fields, parser is throw-away) ──
  private val defs = Vector.newBuilder[SourceSymbolDefinition]
  private val refs = Vector.newBuilder[SourceSymbolReference]

  // ── Public API ────────────────────────────────────

  def parse(): SourceSemanticdb = try parseInternal() catch {
    case NonFatal(e) =>
      logger.warn(s"Failed to parse Java source ${path.last}: ${e.getMessage}")
      SourceSemanticdb(Vector.empty, Vector.empty)
  }

  private def parseInternal(): SourceSemanticdb = {
    val result = new JavaParser().parse(is)
    if !result.isSuccessful || !result.getResult.isPresent then
      return SourceSemanticdb(Vector.empty, Vector.empty)

    val compilationUnit = result.getResult.get
    val pkgDecl = compilationUnit.getPackageDeclaration
    val pkg = if pkgDecl.isPresent then pkgDecl.get.getNameAsString else ""
    val owner = SymbolUtils.packageOwner(
      if pkg.nonEmpty then pkg.split('.').toList else Nil
    )

    val scope = ScopeTracker.empty

    // Process imports — extract refs and add to scope
    compilationUnit.getImports.forEach { imp =>
      extractImportRefs(imp, scope)
    }

    // Single-pass: process top-level types
    compilationUnit.getTypes.forEach { t => extractTypeDecl(t, owner, scope) }

    SourceSemanticdb(defs.result(), refs.result())
  }

  // ── Type declaration extraction ───────────────────

  private def extractTypeDecl(
      t: TypeDeclaration[?],
      owner: Symbol,
      scope: ScopeTracker
  ): Unit = {
    val name = t.getNameAsString
    val (kind, typeOwner) = t match {
      case c: ClassOrInterfaceDeclaration =>
        val kind = if c.isInterface then SymbolKind.Interface else SymbolKind.Class
        (kind, SymbolUtils.typeSymbol(owner, name))
      case _: EnumDeclaration =>
        (SymbolKind.Enum, SymbolUtils.typeSymbol(owner, name))
      case _: AnnotationDeclaration =>
        (SymbolKind.Interface, SymbolUtils.typeSymbol(owner, name))
      case _ =>
        (SymbolKind.Class, SymbolUtils.typeSymbol(owner, name))
    }

    defs += SourceSymbolDefinition(name, kind, typeOwner, nameRange(t.getName))
    scope.defineType(name, typeOwner)

    val childScope = scope.child()
    val overloads = new OverloadTracker

    // Enum constants (processed before members for correct ordering)
    t match {
      case enumDecl: EnumDeclaration =>
        enumDecl.getEntries.forEach { entry =>
          val entryName = entry.getNameAsString
          defs += SourceSymbolDefinition(entryName, SymbolKind.EnumMember,
            SymbolUtils.termSymbol(typeOwner, entryName), nameRange(entry.getName))
          scope.defineTerm(entryName, SymbolUtils.termSymbol(typeOwner, entryName))
        }
      case _ =>
    }

    // Extract refs from extends/implements
    t match {
      case c: ClassOrInterfaceDeclaration =>
        c.getExtendedTypes.forEach(et => extractTypeRef(et, childScope))
        c.getImplementedTypes.forEach(it => extractTypeRef(it, childScope))
      case e: EnumDeclaration =>
        e.getImplementedTypes.forEach(it => extractTypeRef(it, childScope))
      case _ =>
    }

    // Single-pass over members: extract definitions and references
    val members = t.getMembers
    members.forEach { member =>
      member match {
        case m: ConstructorDeclaration =>
          val idx = overloads.ctorIdx
          overloads.ctorIdx = idx + 1
          defs += SourceSymbolDefinition("<init>", SymbolKind.Constructor,
            SymbolUtils.constructorSymbol(typeOwner, idx), nameRange(m.getName))
          // Extract refs from constructor parameter types
          m.getParameters.forEach { p => p.getType match
            case refType: ClassOrInterfaceType => extractTypeRef(refType, childScope)
            case _ => ()
          }

        case m: MethodDeclaration =>
          val mName = m.getNameAsString
          val idx = overloads.methodIdx.getOrElse(mName, 0)
          overloads.methodIdx(mName) = idx + 1
          defs += SourceSymbolDefinition(mName, SymbolKind.Method,
            SymbolUtils.methodSymbol(typeOwner, mName, idx), nameRange(m.getName))
          scope.defineTerm(mName, SymbolUtils.methodSymbol(typeOwner, mName, idx))
          // Extract refs from return type and parameter types
          m.getType match
            case refType: ClassOrInterfaceType => extractTypeRef(refType, childScope)
            case _ => ()
          m.getParameters.forEach { p => p.getType match
            case refType: ClassOrInterfaceType => extractTypeRef(refType, childScope)
            case _ => ()
          }

        case f: FieldDeclaration =>
          // Extract ref from common field type
          f.getCommonType match
            case refType: ClassOrInterfaceType => extractTypeRef(refType, childScope)
            case _ => ()
          f.getVariables.forEach { v =>
            val vName = v.getNameAsString
            defs += SourceSymbolDefinition(vName, SymbolKind.Field,
              SymbolUtils.termSymbol(typeOwner, vName), nameRange(v.getName))
          }

        case nested: TypeDeclaration[?] =>
          extractTypeDecl(nested, typeOwner, childScope)

        case _ =>
      }
    }
  }

  // ── Reference extraction ──────────────────────────

  /** Extract a type reference from a JavaParser type node.
    * For simple names like `List`, resolves against scope.
    * For qualified names like `java.util.List`, constructs symbol from segments. */
  private def extractTypeRef(tpe: ClassOrInterfaceType, scope: ScopeTracker): Unit = {
    val scopeOpt = tpe.getScope
    val symbol = if scopeOpt.isPresent then
      // Qualified name: build full symbol from scope chain
      val parts = collectScopeChain(tpe)
      val name = parts.last
      val pkg = parts.init
      SymbolUtils.typeSymbol(SymbolUtils.packageOwner(pkg), name)
    else
      // Simple name: resolve against scope (imports or same-file defs)
      val name = tpe.getNameAsString
      scope.resolve(name).headOption.getOrElse {
        // Unresolved external ref — still emit with best-guess symbol
        SymbolUtils.typeSymbol(Symbol(""), name)
      }
    refs += SourceSymbolReference(symbol, nameRange(tpe.getName))
  }

  /** Collect the full qualified name chain from a ClassOrInterfaceType scope chain.
    * E.g., `java.util.Map.Entry` → List("java", "util", "Map", "Entry") */
  private def collectScopeChain(tpe: ClassOrInterfaceType): List[String] = {
    val parts = List.newBuilder[String]
    def collect(t: ClassOrInterfaceType): Unit = {
      t.getScope.ifPresent(collect)
      parts += t.getNameAsString
    }
    collect(tpe)
    parts.result()
  }

  /** Extract references from import statements.
    * Explicit imports (not wildcard) provide known fully-qualified symbols.
    * Adds to scope for future resolution. */
  private def extractImportRefs(imp: com.github.javaparser.ast.ImportDeclaration, scope: ScopeTracker): Unit = {
    if imp.isAsterisk then return // wildcard — can't resolve statically
    val qualifiedName = imp.getNameAsString // e.g., "java.util.List"
    val parts = qualifiedName.split('.').toList
    if parts.isEmpty then return
    val name = parts.last
    val pkg = parts.init
    val pkgOwner = SymbolUtils.packageOwner(pkg)
    val sym = SymbolUtils.typeSymbol(pkgOwner, name)
    refs += SourceSymbolReference(sym, nameRange(imp.getName))
    scope.defineImport(name, pkgOwner.value)
  }

  // ── Position helpers ──────────────────────────────

  private def nameRange(name: Node): SymbolLocation = {
    val begin = name.getBegin.orElseThrow()
    val end = name.getEnd.orElseThrow()
    // javaparser positions are 1-based, LSP is 0-based
    SymbolLocation(
      path = path,
      SymbolLocationRange(
        startLine = begin.line - 1,
        startCharacter = begin.column - 1,
        endLine = end.line - 1,
        endCharacter = end.column - 1
      )
    )
  }
}

object JavaSourceParser {
  def apply(path: os.Path): JavaSourceParser =
    new JavaSourceParser(path, os.read.inputStream(path))
  def apply(str: String, fileName: String = "<inmemory>.java"): JavaSourceParser =
    new JavaSourceParser(os.pwd / fileName, new java.io.ByteArrayInputStream(str.getBytes))
}
