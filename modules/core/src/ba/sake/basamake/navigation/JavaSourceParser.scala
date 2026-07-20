package ba.sake.basamake.navigation

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.Node
import org.eclipse.lsp4j.{Position, Range, SymbolKind}

object JavaSourceParser {

  private val parser = new JavaParser()

  def extractDefinitions(content: String): List[SourceDefinition] = {
    val result = parser.parse(content)
    if !result.isSuccessful || !result.getResult.isPresent then
      return List.empty

    val cu = result.getResult.get
    val pkgDecl = cu.getPackageDeclaration
    val pkg = if pkgDecl.isPresent then pkgDecl.get.getNameAsString else ""
    val pkgPrefix = if pkg.nonEmpty then pkg.replace('.', '/') + "/" else ""

    val builder = List.newBuilder[SourceDefinition]

    // Top-level and nested types
    cu.findAll(classOf[TypeDeclaration[?]]).forEach { t =>
      val name = t.getNameAsString
      val kind = t match
        case _: EnumDeclaration            => SymbolKind.Enum
        case c: ClassOrInterfaceDeclaration if c.isInterface => SymbolKind.Interface
        case _                             => SymbolKind.Class
      val pos = nameRange(t.getName)
      builder += SourceDefinition(name, kind, s"$pkgPrefix$name", pos)

      // Enum constants
      t match {
        case enumDecl: EnumDeclaration =>
          enumDecl.getEntries.forEach { entry =>
            val entryName = entry.getNameAsString
            val entryPos = nameRange(entry.getName)
            builder += SourceDefinition(entryName, SymbolKind.EnumMember, s"$pkgPrefix$name.$entryName", entryPos)
          }
        case _ =>
      }
    }

    // Methods
    cu.findAll(classOf[MethodDeclaration]).forEach { m =>
      val name = m.getNameAsString
      val pos = nameRange(m.getName)
      builder += SourceDefinition(name, SymbolKind.Method, s"$pkgPrefix$name", pos)
    }

    // Fields
    cu.findAll(classOf[FieldDeclaration]).forEach { f =>
      f.getVariables.forEach { v =>
        val name = v.getNameAsString
        val pos = nameRange(v.getName)
        builder += SourceDefinition(name, SymbolKind.Field, s"$pkgPrefix$name", pos)
      }
    }

    builder.result()
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
