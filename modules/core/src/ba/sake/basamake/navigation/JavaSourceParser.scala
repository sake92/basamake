package ba.sake.basamake.navigation

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.Node
import org.eclipse.lsp4j.{Position, Range, SymbolKind}

object JavaSourceParser {

  private val parser = new JavaParser()

  def extractDefinitions(content: String, fileName: String = ""): List[SourceDefinition] = {
    val result = parser.parse(content)
    if !result.isSuccessful || !result.getResult.isPresent then
      return List.empty

    val cu = result.getResult.get
    val pkgDecl = cu.getPackageDeclaration
    val pkg = if pkgDecl.isPresent then pkgDecl.get.getNameAsString else ""
    val pkgPrefix = if pkg.nonEmpty then pkg.replace('.', '/') + "/" else ""

    val builder = List.newBuilder[SourceDefinition]
    cu.getTypes.forEach { t => extractTypeDecl(t, pkgPrefix, Nil, builder) }
    builder.result()
  }

  private def extractTypeDecl(
      t: TypeDeclaration[?],
      pkgPrefix: String,
      ownerChain: List[String],
      builder: scala.collection.mutable.Builder[SourceDefinition, List[SourceDefinition]]
  ): Unit = {
    val name = t.getNameAsString
    val kind = t match
      case _: EnumDeclaration                         => SymbolKind.Enum
      case c: ClassOrInterfaceDeclaration if c.isInterface => SymbolKind.Interface
      case _                                          => SymbolKind.Class

    val ownerPrefix = ownerChain.mkString(".")
    val symbol =
      if ownerPrefix.nonEmpty then s"$pkgPrefix$ownerPrefix.$name"
      else s"$pkgPrefix$name"
    val ownerName =
      if ownerPrefix.nonEmpty then s"$ownerPrefix.$name"
      else name

    builder += SourceDefinition(name, kind, symbol, ownerName, nameRange(t.getName))

    val childChain = ownerChain :+ name

    // Enum constants
    t match {
      case enumDecl: EnumDeclaration =>
        enumDecl.getEntries.forEach { entry =>
          val entryName = entry.getNameAsString
          val entrySymbol = s"${symbol}.$entryName"
          val entryOwnerName = s"${ownerName}.$entryName"
          builder += SourceDefinition(entryName, SymbolKind.EnumMember, entrySymbol, entryOwnerName, nameRange(entry.getName))
        }
      case _ =>
    }

    // Members: methods, fields, nested types
    t.getMembers.forEach { member =>
      member match {
        case m: MethodDeclaration =>
          val mName = m.getNameAsString
          val mSymbol = s"${symbol}.$mName"
          val mOwnerName = s"${ownerName}.$mName"
          builder += SourceDefinition(mName, SymbolKind.Method, mSymbol, mOwnerName, nameRange(m.getName))

        case f: FieldDeclaration =>
          f.getVariables.forEach { v =>
            val vName = v.getNameAsString
            val vSymbol = s"${symbol}.$vName"
            val vOwnerName = s"${ownerName}.$vName"
            builder += SourceDefinition(vName, SymbolKind.Field, vSymbol, vOwnerName, nameRange(v.getName))
          }

        case nested: TypeDeclaration[?] =>
          extractTypeDecl(nested, pkgPrefix, childChain, builder)

        case _ =>
      }
    }
  }

  private def nameRange(name: Node): Range = {
    val begin = name.getBegin.orElseThrow()
    val end = name.getEnd.orElseThrow()
    // javaparser positions are 1-based, LSP is 0-based
    new Range(
      new Position(begin.line - 1, begin.column - 1),
      new Position(end.line - 1, end.column - 1)
    )
  }
}
