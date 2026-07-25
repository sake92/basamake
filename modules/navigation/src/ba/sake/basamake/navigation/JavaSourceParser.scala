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
  * and bare-name references. Single-pass traversal over JavaParser AST.
  *
  * No global resolution at parse time. Type references and imports are recorded
  * as SourceSymbolReference with bare names (e.g. `Symbol("List")`).
  * Lookup bridges bare names to global definitions at query time.
  *
  * Constructor takes path (for location metadata) and input stream (for content).
  * The parser instance is throw-away — one parse per file.
  */
class JavaSourceParser(path: os.Path, is: InputStream) extends StrictLogging {

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

    val pkg = if compilationUnit.getPackageDeclaration.isPresent then
      compilationUnit.getPackageDeclaration.get.getNameAsString
    else ""
    val owner = SymbolUtils.packageOwner(
      if pkg.nonEmpty then pkg.split('.').toList else Nil
    )

    // Record import names
    compilationUnit.getImports.forEach { imp =>
      extractImportRefs(imp)
    }

    // Process top-level types
    compilationUnit.getTypes.forEach { t => extractTypeDecl(t, owner) }

    SourceSemanticdb(defs.result(), refs.result())
  }

  // ── Type declaration extraction ───────────────────

  private def extractTypeDecl(t: TypeDeclaration[?], owner: Symbol): Unit = {
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

    val overloads = new OverloadTracker

    // Enum constants
    t match {
      case enumDecl: EnumDeclaration =>
        enumDecl.getEntries.forEach { entry =>
          val entryName = entry.getNameAsString
          defs += SourceSymbolDefinition(entryName, SymbolKind.EnumMember,
            SymbolUtils.termSymbol(typeOwner, entryName), nameRange(entry.getName))
        }
      case _ =>
    }

    // Extract type refs from extends/implements (record bare names)
    t match {
      case c: ClassOrInterfaceDeclaration =>
        c.getExtendedTypes.forEach(et => extractTypeRef(et))
        c.getImplementedTypes.forEach(it => extractTypeRef(it))
      case e: EnumDeclaration =>
        e.getImplementedTypes.forEach(it => extractTypeRef(it))
      case _ =>
    }

    // Process members
    t.getMembers.forEach { member =>
      member match {
        case m: ConstructorDeclaration =>
          val idx = overloads.ctorIdx
          overloads.ctorIdx = idx + 1
          defs += SourceSymbolDefinition("<init>", SymbolKind.Constructor,
            SymbolUtils.constructorSymbol(typeOwner, idx), nameRange(m.getName))
          m.getParameters.forEach { p => p.getType match
            case refType: ClassOrInterfaceType => extractTypeRef(refType)
            case _ => ()
          }

        case m: MethodDeclaration =>
          val mName = m.getNameAsString
          val idx = overloads.methodIdx.getOrElse(mName, 0)
          overloads.methodIdx(mName) = idx + 1
          defs += SourceSymbolDefinition(mName, SymbolKind.Method,
            SymbolUtils.methodSymbol(typeOwner, mName, idx), nameRange(m.getName))
          m.getType match
            case refType: ClassOrInterfaceType => extractTypeRef(refType)
            case _ => ()
          m.getParameters.forEach { p => p.getType match
            case refType: ClassOrInterfaceType => extractTypeRef(refType)
            case _ => ()
          }

        case f: FieldDeclaration =>
          f.getCommonType match
            case refType: ClassOrInterfaceType => extractTypeRef(refType)
            case _ => ()
          f.getVariables.forEach { v =>
            val vName = v.getNameAsString
            defs += SourceSymbolDefinition(vName, SymbolKind.Field,
              SymbolUtils.termSymbol(typeOwner, vName), nameRange(v.getName))
          }

        case nested: TypeDeclaration[?] =>
          extractTypeDecl(nested, typeOwner)

        case _ =>
      }
    }
  }

  // ── Reference extraction ──────────────────────────

  /** Record a type reference as a bare name. */
  private def extractTypeRef(tpe: ClassOrInterfaceType): Unit = {
    val scopeOpt = tpe.getScope
    val symbol = if scopeOpt.isPresent then
      // Qualified name: record only the last segment
      Symbol(tpe.getNameAsString)
    else
      // Simple name: record bare name
      Symbol(tpe.getNameAsString)
    refs += SourceSymbolReference(symbol, nameRange(tpe.getName))
  }

  /** Record an import as a bare-name reference. */
  private def extractImportRefs(imp: com.github.javaparser.ast.ImportDeclaration): Unit = {
    if imp.isAsterisk then return
    val name = imp.getNameAsString
    if name.isEmpty then return
    val simpleName = name.split('.').lastOption.getOrElse(name)
    refs += SourceSymbolReference(Symbol(simpleName), nameRange(imp.getName))
  }

  // ── Position helpers ──────────────────────────────

  private def nameRange(name: Node): SymbolLocation = {
    val begin = name.getBegin.orElseThrow()
    val end = name.getEnd.orElseThrow()
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
