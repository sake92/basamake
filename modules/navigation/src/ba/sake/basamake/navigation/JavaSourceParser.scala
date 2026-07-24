package ba.sake.basamake.navigation

import java.io.InputStream
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.Node
import org.eclipse.lsp4j.SymbolKind

class JavaSourceParser(path: os.Path, is: InputStream) {

  // No shared JavaParser instance — JavaCC-generated parser is stateful (token, jj_nt).
  // Concurrent extraction corrupts token stream: AssertionError "reference was unexpectedly null".

  def parse(): SourceSemanticdb = {
    val result = new JavaParser().parse(is)
    if !result.isSuccessful || !result.getResult.isPresent then
      return SourceSemanticdb(Vector.empty, Vector.empty)

    val compilationUnit = result.getResult.get
    val pkgDecl = compilationUnit.getPackageDeclaration
    val pkg = if pkgDecl.isPresent then pkgDecl.get.getNameAsString else ""
    val owner = SymbolUtils.packageOwner(
      if pkg.nonEmpty then pkg.split('.').toList else Nil
    )

    val builder = Vector.newBuilder[SourceSymbolDefinition]
    compilationUnit.getTypes.forEach { t => extractTypeDecl(t, owner, builder) }
    // TODO parse references too
    SourceSemanticdb(builder.result(), Vector.empty)
  }

  private def extractTypeDecl(
      t: TypeDeclaration[?],
      owner: Symbol,
      builder: scala.collection.mutable.Builder[SourceSymbolDefinition, Vector[SourceSymbolDefinition]]
  ): Unit = {
    val name = t.getNameAsString
    val (kind, typeOwner) = t match {
      case c: ClassOrInterfaceDeclaration  =>
        val kind = if c.isInterface then SymbolKind.Interface else SymbolKind.Class
        (kind, SymbolUtils.typeSymbol(owner, name))
      case _: EnumDeclaration =>
        (SymbolKind.Enum, SymbolUtils.typeSymbol(owner, name))
    }

    builder += SourceSymbolDefinition(name, kind, typeOwner, nameRange(t.getName))

    // Collect members before emitting to assign overload indices
    val members = t.getMembers
    val memberList = scala.jdk.CollectionConverters.CollectionHasAsScala(members).asScala.toList

    // Compute overload indices for methods and constructors
    val methodIndices = computeMethodOverloads(memberList)
    val ctorIndices = computeConstructorOverloads(memberList)

    // Enum constants
    t match {
      case enumDecl: EnumDeclaration =>
        // TODO enter recursively into enum ..
        enumDecl.getEntries.forEach { entry =>
          val entryName = entry.getNameAsString
          builder += SourceSymbolDefinition(entryName, SymbolKind.EnumMember,
            SymbolUtils.termSymbol(typeOwner, entryName), nameRange(entry.getName))
        }
      case _ =>
    }

    // Members: methods, constructors, fields, nested types
    memberList.foreach { member =>
      member match {
        case m: ConstructorDeclaration =>
          val idx = ctorIndices.getOrElse(m, 0)
          builder += SourceSymbolDefinition("<init>", SymbolKind.Constructor,
            SymbolUtils.constructorSymbol(typeOwner, idx), nameRange(m.getName))

        case m: MethodDeclaration =>
          val mName = m.getNameAsString
          val idx = methodIndices.getOrElse(m, 0)
          builder += SourceSymbolDefinition(mName, SymbolKind.Method,
            SymbolUtils.methodSymbol(typeOwner, mName, idx), nameRange(m.getName))

        case f: FieldDeclaration =>
          f.getVariables.forEach { v =>
            val vName = v.getNameAsString
            builder += SourceSymbolDefinition(vName, SymbolKind.Field,
              SymbolUtils.termSymbol(typeOwner, vName), nameRange(v.getName))
          }

        case nested: TypeDeclaration[?] =>
          extractTypeDecl(nested, typeOwner, builder)

        case _ =>
      }
    }
  }

  /** Compute lexical overload indices for methods within a type declaration.
    * Methods are grouped by name, sorted by source position, and assigned 0-based indices. */
  private def computeMethodOverloads(members: List[com.github.javaparser.ast.body.BodyDeclaration[?]]): Map[MethodDeclaration, Int] = {
    val methods = members.collect { case m: MethodDeclaration => m }
    val grouped = methods.groupBy(_.getNameAsString)
    grouped.flatMap { case (_, ms) =>
      val sorted = ms.sortBy(m => posOf(m))
      sorted.zipWithIndex.map { case (m, idx) => m -> idx }
    }
  }

  /** Compute lexical overload indices for constructors.
    * Constructors are sorted by declaration start position (NOT name position,
    * since all constructors share the same class name) and assigned 0-based indices. */
  private def computeConstructorOverloads(members: List[com.github.javaparser.ast.body.BodyDeclaration[?]]): Map[ConstructorDeclaration, Int] = {
    val ctors = members.collect { case c: ConstructorDeclaration => c }
    val sorted = ctors.sortBy(c => posOf(c))
    sorted.zipWithIndex.map { case (c, idx) => c -> idx }.toMap
  }

  private def posOf(node: com.github.javaparser.ast.Node): Long = {
    val begin = try node.getBegin.orElseThrow()
      catch { case _: Exception => return Long.MaxValue }
    begin.line * 100000L + begin.column
  }

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
  def apply(str: String): JavaSourceParser = 
    new JavaSourceParser(os.pwd / "<inmemory>.java", new java.io.ByteArrayInputStream(str.getBytes))
}