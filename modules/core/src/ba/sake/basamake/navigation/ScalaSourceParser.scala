package ba.sake.basamake.navigation

import scala.meta.*
import scala.meta.dialects.Scala3
import org.eclipse.lsp4j.{Position, Range, SymbolKind}

object ScalaSourceParser {

  private given dialect: Dialect = Scala3

  def extractDefinitions(content: String): List[SourceDefinition] = {
    content.parse[Source] match
      case Parsed.Success(source) =>
        val pkg = extractPackage(source)
        val pkgPrefix = if pkg.nonEmpty then pkg.replace('.', '/') + "/" else ""
        source.collect {
          case d: Defn.Class   => singleDefList(SymbolKind.Class, d.name, pkgPrefix, isMethod = false)
          case d: Defn.Trait   => singleDefList(SymbolKind.Interface, d.name, pkgPrefix, isMethod = false)
          case d: Defn.Object  => singleDefList(SymbolKind.Object, d.name, pkgPrefix, isMethod = false)
          case d: Defn.Enum    => singleDefList(SymbolKind.Enum, d.name, pkgPrefix, isMethod = false)
          case d: Defn.Def     => singleDefList(SymbolKind.Method, d.name, pkgPrefix, isMethod = true)
          case d: Defn.Macro   => singleDefList(SymbolKind.Method, d.name, pkgPrefix, isMethod = true)
          case d: Defn.Type    => singleDefList(SymbolKind.TypeParameter, d.name, pkgPrefix, isMethod = false)
          case d: Defn.Given if hasName(d)      => singleDefList(SymbolKind.Interface, d.name, pkgPrefix, isMethod = false)
          case d: Defn.GivenAlias if hasName(d) => singleDefList(SymbolKind.Interface, d.name, pkgPrefix, isMethod = false)
          case d: Defn.EnumCase => singleDefList(SymbolKind.EnumMember, d.name, pkgPrefix, isMethod = false)
          case d: Defn.Val     => d.pats.collect { case Pat.Var(name) => singleDef(SymbolKind.Property, name, pkgPrefix, isMethod = false) }
          case d: Defn.Var     => d.pats.collect { case Pat.Var(name) => singleDef(SymbolKind.Variable, name, pkgPrefix, isMethod = false) }
          case d: Decl.Val     => d.pats.collect { case Pat.Var(name) => singleDef(SymbolKind.Property, name, pkgPrefix, isMethod = false) }
          case d: Decl.Var     => d.pats.collect { case Pat.Var(name) => singleDef(SymbolKind.Variable, name, pkgPrefix, isMethod = false) }
          case d: Decl.Def     => singleDefList(SymbolKind.Method, d.name, pkgPrefix, isMethod = true)
          case d: Decl.Type    => singleDefList(SymbolKind.TypeParameter, d.name, pkgPrefix, isMethod = false)
          case d: Decl.Given if hasName(d) => singleDefList(SymbolKind.Interface, d.name, pkgPrefix, isMethod = false)
        }.flatten.distinct
      case Parsed.Error(_, _, _) =>
        List.empty
  }

  private def extractPackage(source: Source): String = source.stats.headOption match
    case Some(pkg: Pkg) => flattenPackageRef(pkg.ref)
    case _              => ""

  private def flattenPackageRef(ref: Term): String = {
    val parts = List.newBuilder[String]
    def collect(ref: Term): Unit = ref match
      case Term.Name(name)            => parts += name
      case Term.Select(qual, name)    => collect(qual); parts += name.value
      case _                          =>
    collect(ref)
    parts.result().mkString(".")
  }

  private def singleDef(kind: SymbolKind, name: meta.Name, pkgPrefix: String, isMethod: Boolean): SourceDefinition =
    SourceDefinition(
      name = name.value,
      kind = kind,
      symbol = s"$pkgPrefix${name.value}",
      range = toLspRange(name.pos)
    )

  private def singleDefList(kind: SymbolKind, name: meta.Name, pkgPrefix: String, isMethod: Boolean): List[SourceDefinition] =
    List(singleDef(kind, name, pkgPrefix, isMethod))

  private def toLspRange(pos: meta.Position): Range =
    new Range(
      new Position(pos.startLine, pos.startColumn),
      new Position(pos.endLine, pos.endColumn)
    )

  private def hasName(d: Decl.Given): Boolean = d.name.value.nonEmpty
  private def hasName(d: Defn.Given): Boolean = d.name.value.nonEmpty
  private def hasName(d: Defn.GivenAlias): Boolean = d.name.value.nonEmpty
}
