package ba.sake.basamake.navigation

import java.util.concurrent.atomic.AtomicLong
import scala.meta.*
import scala.meta.dialects.Scala3Future
import scala.meta.dialects.Scala213
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{Position, Range, SymbolKind}

object ScalaSourceParser extends StrictLogging {

  private val totalParsed = new AtomicLong(0)
  private val parsedScala3Future = new AtomicLong(0)
  private val parsedScala213 = new AtomicLong(0)
  private val parseFailed = new AtomicLong(0)

  def extractDefinitions(content: String, fileName: String = ""): List[SourceDefinition] = {
    totalParsed.incrementAndGet()
    val parsed3 = {
      given Dialect = Scala3Future
      content.parse[Source]
    }
    parsed3 match
      case Parsed.Success(source) =>
        parsedScala3Future.incrementAndGet()
        extractFromSource(source, fileName)
      case Parsed.Error(_, msg, _) =>
        logger.debug(s"Scala 3 parse failed for $fileName, retrying with Scala 2.13: $msg")
        val parsed213 = {
          given Dialect = Scala213
          content.parse[Source]
        }
        parsed213 match
          case Parsed.Success(source) =>
            parsedScala213.incrementAndGet()
            extractFromSource(source, fileName)
          case Parsed.Error(_, msg2, _) =>
            parseFailed.incrementAndGet()
            val fileInfo = if fileName.nonEmpty then s" [$fileName]" else ""
            val preview = if content.length > 80 then content.take(80) + "..." else content
            // Capture checking (^) handled via Scala3Future; debug-level log for genuinely unparsable files
            logger.debug(s"Both Scala3Future and Scala 2.13 parse failed for source$fileInfo: $msg2")
            List.empty
  }

  /** Logs a summary of parse success/failure counts. Called after indexing completes. */
  def logSummary(): Unit = {
    val total = totalParsed.get()
    val ok3 = parsedScala3Future.get()
    val ok213 = parsedScala213.get()
    val failed = parseFailed.get()
    val ok = ok3 + ok213
    if total > 0 then
      logger.info(
        s"ScalaSourceParser summary: $ok/$total parsed ($ok3 Scala3Future, $ok213 Scala 2.13, $failed failed)"
      )
  }

  private def topLevelOwnerWrapper(fileName: String): Option[String] =
    fileName match
      case ""                     => None // tests that omit fileName keep old behavior
      case "package.scala"        => Some("package")
      case n if n.endsWith(".scala") => Some(n.stripSuffix(".scala") + "$package")
      case _                      => None

  private def extractFromSource(source: Source, fileName: String): List[SourceDefinition] = {
    val wrapper = topLevelOwnerWrapper(fileName)
    extractFromStats(source.stats, "", Nil, wrapper)
  }

  private def extractFromStats(
      stats: List[Stat],
      pkgPrefix: String,
      ownerChain: List[String],
      wrapper: Option[String]
  ): List[SourceDefinition] = {
    stats.flatMap {
      case p: Pkg =>
        val relPkg = flattenPackageRef(p.ref)
        val newPkg =
          if relPkg.isEmpty then pkgPrefix
          else if pkgPrefix.nonEmpty then pkgPrefix + relPkg.replace('.', '/') + "/"
          else relPkg.replace('.', '/') + "/"
        extractFromStats(p.stats, newPkg, ownerChain, wrapper)

      case d: Pkg.Object =>
        val newPkg = pkgPrefix + d.name.value + "/"
        val childChain = ownerChain :+ "package"
        extractFromStats(d.templ.stats, newPkg, childChain, wrapper)

      case d: Defn.Class =>
        val defn = makeDef(SymbolKind.Class, d.name, pkgPrefix, ownerChain)
        val childChain = ownerChain :+ d.name.value
        defn :: extractFromStats(d.templ.stats, pkgPrefix, childChain, wrapper)

      case d: Defn.Trait =>
        val defn = makeDef(SymbolKind.Interface, d.name, pkgPrefix, ownerChain)
        val childChain = ownerChain :+ d.name.value
        defn :: extractFromStats(d.templ.stats, pkgPrefix, childChain, wrapper)

      case d: Defn.Object =>
        val defn = makeDef(SymbolKind.Object, d.name, pkgPrefix, ownerChain)
        val childChain = ownerChain :+ d.name.value
        defn :: extractFromStats(d.templ.stats, pkgPrefix, childChain, wrapper)

      case d: Defn.Enum =>
        val defn = makeDef(SymbolKind.Enum, d.name, pkgPrefix, ownerChain)
        val childChain = ownerChain :+ d.name.value
        val inner = extractFromStats(d.templ.stats, pkgPrefix, childChain, wrapper)
        defn :: inner

      case d: Defn.EnumCase =>
        List(makeDef(SymbolKind.EnumMember, d.name, pkgPrefix, ownerChain))

      case d: Defn.RepeatedEnumCase =>
        // case Red, Blue — multiple cases in one declaration
        d.cases.toList.map { name =>
          makeDef(SymbolKind.EnumMember, name, pkgPrefix, ownerChain)
        }

      case d: Defn.Given if hasName(d) =>
        val chain = wrapOwner(ownerChain, wrapper)
        val defn = makeDef(SymbolKind.Interface, d.name, pkgPrefix, chain)
        val childChain = chain :+ d.name.value
        defn :: extractFromStats(d.templ.stats, pkgPrefix, childChain, wrapper)

      case d: Defn.GivenAlias if hasName(d) =>
        List(makeDef(SymbolKind.Interface, d.name, pkgPrefix, wrapOwner(ownerChain, wrapper)))

      case d: Defn.Def =>
        List(makeDef(SymbolKind.Method, d.name, pkgPrefix, wrapOwner(ownerChain, wrapper)))

      case d: Defn.Macro =>
        List(makeDef(SymbolKind.Method, d.name, pkgPrefix, wrapOwner(ownerChain, wrapper)))

      case d: Defn.Type =>
        List(makeDef(SymbolKind.TypeParameter, d.name, pkgPrefix, wrapOwner(ownerChain, wrapper)))

      case d: Defn.Val =>
        d.pats.collect { case Pat.Var(name) => makeDef(SymbolKind.Property, name, pkgPrefix, wrapOwner(ownerChain, wrapper)) }

      case d: Defn.Var =>
        d.pats.collect { case Pat.Var(name) => makeDef(SymbolKind.Variable, name, pkgPrefix, wrapOwner(ownerChain, wrapper)) }

      case d: Decl.Val =>
        d.pats.collect { case Pat.Var(name) => makeDef(SymbolKind.Property, name, pkgPrefix, wrapOwner(ownerChain, wrapper)) }

      case d: Decl.Var =>
        d.pats.collect { case Pat.Var(name) => makeDef(SymbolKind.Variable, name, pkgPrefix, wrapOwner(ownerChain, wrapper)) }

      case d: Decl.Def =>
        List(makeDef(SymbolKind.Method, d.name, pkgPrefix, wrapOwner(ownerChain, wrapper)))

      case d: Decl.Type =>
        List(makeDef(SymbolKind.TypeParameter, d.name, pkgPrefix, wrapOwner(ownerChain, wrapper)))

      case d: Decl.Given if hasName(d) =>
        List(makeDef(SymbolKind.Interface, d.name, pkgPrefix, wrapOwner(ownerChain, wrapper)))

      case _ =>
        Nil
    }
  }

  private def wrapOwner(ownerChain: List[String], wrapper: Option[String]): List[String] =
    if ownerChain.isEmpty then wrapper.toList else ownerChain

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
