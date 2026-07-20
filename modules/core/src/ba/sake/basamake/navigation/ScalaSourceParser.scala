package ba.sake.basamake.navigation

import scala.meta.*
import scala.meta.dialects.Scala3
import scala.meta.dialects.Scala213
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{Position, Range, SymbolKind}

object ScalaSourceParser extends StrictLogging {

  def extractDefinitions(content: String): List[SourceDefinition] = {
    val parsed3 = {
      given Dialect = Scala3
      content.parse[Source]
    }
    parsed3 match
      case Parsed.Success(source) =>
        extractFromSource(source)
      case Parsed.Error(_, msg, _) =>
        logger.debug(s"Scala 3 parse failed, retrying with Scala 2.13: $msg")
        val parsed213 = {
          given Dialect = Scala213
          content.parse[Source]
        }
        parsed213 match
          case Parsed.Success(source) =>
            extractFromSource(source)
          case Parsed.Error(_, msg2, _) =>
            val preview = if content.length > 80 then content.take(80) + "..." else content
            logger.warn(s"Both Scala 3 and Scala 2.13 parse failed for source [$preview]: $msg2")
            List.empty
  }

  private def extractFromSource(source: Source): List[SourceDefinition] = {
    extractFromStats(source.stats, "", Nil)
  }

  private def extractFromStats(
      stats: List[Stat],
      pkgPrefix: String,
      ownerChain: List[String]
  ): List[SourceDefinition] = {
    stats.flatMap {
      case p: Pkg =>
        val relPkg = flattenPackageRef(p.ref)
        val newPkg =
          if relPkg.isEmpty then pkgPrefix
          else if pkgPrefix.nonEmpty then pkgPrefix + relPkg.replace('.', '/') + "/"
          else relPkg.replace('.', '/') + "/"
        extractFromStats(p.stats, newPkg, ownerChain)

      case d: Defn.Class =>
        val defn = makeDef(SymbolKind.Class, d.name, pkgPrefix, ownerChain)
        val childChain = ownerChain :+ d.name.value
        defn :: extractFromStats(d.templ.stats, pkgPrefix, childChain)

      case d: Defn.Trait =>
        val defn = makeDef(SymbolKind.Interface, d.name, pkgPrefix, ownerChain)
        val childChain = ownerChain :+ d.name.value
        defn :: extractFromStats(d.templ.stats, pkgPrefix, childChain)

      case d: Defn.Object =>
        val defn = makeDef(SymbolKind.Object, d.name, pkgPrefix, ownerChain)
        val childChain = ownerChain :+ d.name.value
        defn :: extractFromStats(d.templ.stats, pkgPrefix, childChain)

      case d: Defn.Enum =>
        val defn = makeDef(SymbolKind.Enum, d.name, pkgPrefix, ownerChain)
        val childChain = ownerChain :+ d.name.value
        val inner = extractFromStats(d.templ.stats, pkgPrefix, childChain)
        defn :: inner

      case d: Defn.EnumCase =>
        List(makeDef(SymbolKind.EnumMember, d.name, pkgPrefix, ownerChain))

      case d: Defn.RepeatedEnumCase =>
        // case Red, Blue — multiple cases in one declaration
        d.cases.toList.map { name =>
          makeDef(SymbolKind.EnumMember, name, pkgPrefix, ownerChain)
        }

      case d: Defn.Given if hasName(d) =>
        val defn = makeDef(SymbolKind.Interface, d.name, pkgPrefix, ownerChain)
        val childChain = ownerChain :+ d.name.value
        defn :: extractFromStats(d.templ.stats, pkgPrefix, childChain)

      case d: Defn.GivenAlias if hasName(d) =>
        List(makeDef(SymbolKind.Interface, d.name, pkgPrefix, ownerChain))

      case d: Defn.Def =>
        List(makeDef(SymbolKind.Method, d.name, pkgPrefix, ownerChain))

      case d: Defn.Macro =>
        List(makeDef(SymbolKind.Method, d.name, pkgPrefix, ownerChain))

      case d: Defn.Type =>
        List(makeDef(SymbolKind.TypeParameter, d.name, pkgPrefix, ownerChain))

      case d: Defn.Val =>
        d.pats.collect { case Pat.Var(name) => makeDef(SymbolKind.Property, name, pkgPrefix, ownerChain) }

      case d: Defn.Var =>
        d.pats.collect { case Pat.Var(name) => makeDef(SymbolKind.Variable, name, pkgPrefix, ownerChain) }

      case d: Decl.Val =>
        d.pats.collect { case Pat.Var(name) => makeDef(SymbolKind.Property, name, pkgPrefix, ownerChain) }

      case d: Decl.Var =>
        d.pats.collect { case Pat.Var(name) => makeDef(SymbolKind.Variable, name, pkgPrefix, ownerChain) }

      case d: Decl.Def =>
        List(makeDef(SymbolKind.Method, d.name, pkgPrefix, ownerChain))

      case d: Decl.Type =>
        List(makeDef(SymbolKind.TypeParameter, d.name, pkgPrefix, ownerChain))

      case d: Decl.Given if hasName(d) =>
        List(makeDef(SymbolKind.Interface, d.name, pkgPrefix, ownerChain))

      case _ =>
        Nil
    }
  }

  private def makeDef(
      kind: SymbolKind,
      name: meta.Name,
      pkgPrefix: String,
      ownerChain: List[String]
  ): SourceDefinition = {
    val ownerPrefix = ownerChain.mkString(".")
    val symbol =
      if ownerPrefix.nonEmpty then s"$pkgPrefix$ownerPrefix.${name.value}"
      else s"$pkgPrefix${name.value}"
    val ownerName =
      if ownerPrefix.nonEmpty then s"$ownerPrefix.${name.value}"
      else name.value
    SourceDefinition(
      name = name.value,
      kind = kind,
      symbol = symbol,
      ownerName = ownerName,
      range = toLspRange(name.pos)
    )
  }

  private def flattenPackageRef(ref: Term): String = {
    val parts = List.newBuilder[String]
    def collect(ref: Term): Unit = ref match
      case Term.Name(name)            => parts += name
      case Term.Select(qual, name)    => collect(qual); parts += name.value
      case _                          =>
    collect(ref)
    parts.result().mkString(".")
  }

  private def toLspRange(pos: meta.Position): Range =
    new Range(
      new Position(pos.startLine, pos.startColumn),
      new Position(pos.endLine, pos.endColumn)
    )

  private def hasName(d: Decl.Given): Boolean = d.name.value.nonEmpty
  private def hasName(d: Defn.Given): Boolean = d.name.value.nonEmpty
  private def hasName(d: Defn.GivenAlias): Boolean = d.name.value.nonEmpty
}
