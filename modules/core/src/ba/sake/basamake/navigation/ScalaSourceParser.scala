package ba.sake.basamake.navigation

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.meta.*
import scala.meta.dialects.Scala3Future
import scala.meta.dialects.Scala213
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.{Position, Range, SymbolKind}

// TODO parse OCCURENCES too, like in semanticdb..
// TODO make this class
object ScalaSourceParser extends StrictLogging {

  private val totalParsed = new AtomicLong(0)
  private val parsedScala3 = new AtomicLong(0)
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
        parsedScala3.incrementAndGet()
        extractFromSource(source, fileName)
      case Parsed.Error(_, msg, _) =>
        logger.debug(s"Scala3Future parse failed for $fileName, retrying with Scala213: $msg")
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
            logger.warn(s"Both Scala 3 and Scala 2.13 parse failed for source$fileInfo: $msg2")
            List.empty
  }

  /** Logs a summary of parse success/failure counts. Called after indexing completes. */
  def logSummary(): Unit = {
    val total = totalParsed.get()
    val ok3 = parsedScala3.get()
    val ok213 = parsedScala213.get()
    val failed = parseFailed.get()
    val ok = ok3 + ok213
    if total > 0 then
      logger.info(
        s"ScalaSourceParser summary: $ok/$total parsed ($ok3 Scala 3, $ok213 Scala 2.13, $failed failed)"
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
    extractFromStats(source.stats, SemanticdbSymbol.packageOwner(Nil), None, wrapper)
  }

  /** Like [[extractFromStats]] but for package-scoped definitions where the package
    * provides the owner prefix. */
  private def extractFromStats(
      stats: List[Stat],
      owner: String,
      pkgPrefix: Option[String],
      wrapper: Option[String]
  ): List[SourceDefinition] = {
    val result = List.newBuilder[SourceDefinition]

    // Collect all direct-child method and constructor declarations for overload tagging
    val overloadIndices = computeOverloadIndices(stats)

    stats.foreach {
      case p: Pkg =>
        val relPkg = flattenPackageRef(p.ref)
        val newPkg = pkgPrefix match
          case Some(prefix) =>
            if relPkg.isEmpty then prefix
            else prefix + relPkg.replace('.', '/') + "/"
          case None =>
            if relPkg.isEmpty then ""
            else relPkg.replace('.', '/') + "/"
        val pkgOwner = if newPkg.isEmpty then SemanticdbSymbol.packageOwner(Nil) else newPkg
        result ++= extractFromStats(p.stats, pkgOwner, Some(newPkg), wrapper)

      case d: Pkg.Object =>
        // Package objects define a new package scope. The object name (e.g. "scala")
        // determines the package prefix for children. Build the package owner
        // from the current pkgPrefix + object name, then append "package.".
        val newPkg = pkgPrefix match
          case Some(prefix) =>
            s"$prefix${d.name.value}/"
          case None =>
            s"${d.name.value}/"
        val objOwner = SemanticdbSymbol.termSymbol(newPkg, "package")
        result ++= extractFromStats(d.templ.stats, objOwner, Some(newPkg), wrapper)

      case d: Defn.Class =>
        val defn = typeDef(SymbolKind.Class, d.name, owner)
        result += defn
        // Constructors use the class symbol (type descriptor) as owner
        val ctors = collectConstructors(d, defn.symbol)
        ctors.foreach(result += _)
        // Nested: class children use the class as owner
        val childOwner = SemanticdbSymbol.typeSymbol(owner, d.name.value)
        result ++= extractFromStats(d.templ.stats, childOwner, pkgPrefix, wrapper)

      case d: Defn.Trait =>
        val defn = typeDef(SymbolKind.Interface, d.name, owner)
        result += defn
        val childOwner = SemanticdbSymbol.typeSymbol(owner, d.name.value)
        result ++= extractFromStats(d.templ.stats, childOwner, pkgPrefix, wrapper)

      case d: Defn.Object =>
        val defn = termDef(SymbolKind.Object, d.name, owner)
        result += defn
        val childOwner = SemanticdbSymbol.termSymbol(owner, d.name.value)
        result ++= extractFromStats(d.templ.stats, childOwner, pkgPrefix, wrapper)

      case d: Defn.Enum =>
        val defn = typeDef(SymbolKind.Enum, d.name, owner)
        result += defn
        val childOwner = SemanticdbSymbol.typeSymbol(owner, d.name.value)
        result ++= extractFromStats(d.templ.stats, childOwner, pkgPrefix, wrapper)

      case d: Defn.EnumCase =>
        result += termDef(SymbolKind.EnumMember, d.name, owner)

      case d: Defn.RepeatedEnumCase =>
        d.cases.toList.foreach { name =>
          result += termDef(SymbolKind.EnumMember, name, owner)
        }

      case d: Defn.Given if hasName(d) =>
        val chain = wrapOwner(owner, wrapper)
        val defn = termDef(SymbolKind.Interface, d.name, chain)
        result += defn
        val childOwner = SemanticdbSymbol.termSymbol(chain, d.name.value)
        result ++= extractFromStats(d.templ.stats, childOwner, pkgPrefix, wrapper)

      case d: Defn.GivenAlias if hasName(d) =>
        result += termDef(SymbolKind.Interface, d.name, wrapOwner(owner, wrapper))

      case d: Defn.Def =>
        val idx = overloadIndices.getOrElse(d, 0)
        result += methodDef(SymbolKind.Method, d.name, wrapOwner(owner, wrapper), idx)

      case d: Defn.Macro =>
        val idx = overloadIndices.getOrElse(d, 0)
        result += methodDef(SymbolKind.Method, d.name, wrapOwner(owner, wrapper), idx)

      case d: Defn.Type =>
        result += typeDef(SymbolKind.TypeParameter, d.name, wrapOwner(owner, wrapper))

      case d: Defn.Val =>
        d.pats.collect { case Pat.Var(name) =>
          result += termDef(SymbolKind.Property, name, wrapOwner(owner, wrapper))
        }

      case d: Defn.Var =>
        d.pats.collect { case Pat.Var(name) =>
          result += termDef(SymbolKind.Variable, name, wrapOwner(owner, wrapper))
        }

      case d: Decl.Val =>
        d.pats.collect { case Pat.Var(name) =>
          result += termDef(SymbolKind.Property, name, wrapOwner(owner, wrapper))
        }

      case d: Decl.Var =>
        d.pats.collect { case Pat.Var(name) =>
          result += termDef(SymbolKind.Variable, name, wrapOwner(owner, wrapper))
        }

      case d: Decl.Def =>
        val idx = overloadIndices.getOrElse(d, 0)
        result += methodDef(SymbolKind.Method, d.name, wrapOwner(owner, wrapper), idx)

      case d: Decl.Type =>
        result += typeDef(SymbolKind.TypeParameter, d.name, wrapOwner(owner, wrapper))

      case d: Decl.Given if hasName(d) =>
        result += termDef(SymbolKind.Interface, d.name, wrapOwner(owner, wrapper))

      case _ =>
    }

    result.result()
  }

  /** Compute lexical overload indices for methods within a flat list of stats
    * under the same owner. Methods are grouped by encoded (escaped) name,
    * sorted by source position, and assigned a 0-based index.
    */
  private def computeOverloadIndices(stats: List[Stat]): Map[Stat, Int] = {
    case class MethodEntry(name: String, stat: Stat, posStart: Int)
    val methodEntries = stats.flatMap {
      case d: Defn.Def   => Some(MethodEntry(d.name.value, d, d.pos.start))
      case d: Decl.Def   => Some(MethodEntry(d.name.value, d, d.pos.start))
      case d: Defn.Macro => Some(MethodEntry(d.name.value, d, d.pos.start))
      case _             => None
    }
    methodEntries
      .groupBy(_.name)
      .flatMap { case (_, entries) =>
        val sorted = entries.sortBy(_.posStart)
        sorted.zipWithIndex.map { case (entry, idx) =>
          entry.stat -> idx
        }
      }
  }

  /** Collect constructor definitions from a class: primary + secondary,
    * sorted by position with overload indices assigned. */
  private def collectConstructors(
      d: Defn.Class,
      classOwner: String
  ): List[SourceDefinition] = {
    // Primary constructor uses the class name position
    val primaryProto = ConstructorProto(toLspRange(d.name.pos))
    val secondaryProtos = d.templ.stats.collect {
      case c: Ctor.Secondary =>
        ConstructorProto(toLspRange(c.name.pos))
    }
    // Sort by position and assign overload indices
    val all = (primaryProto :: secondaryProtos).sortBy { p =>
      p.range.getStart.getLine * 100000L + p.range.getStart.getCharacter
    }
    all.zipWithIndex.map { case (proto, idx) =>
      SourceDefinition(
        name = "<init>",
        kind = SymbolKind.Constructor,
        symbol = SemanticdbSymbol.constructorSymbol(classOwner, idx),
        range = proto.range
      )
    }
  }

  private case class ConstructorProto(range: Range)

  private def wrapOwner(owner: String, wrapper: Option[String]): String =
    wrapper match
      case None => owner
      case Some(w) if owner.endsWith("/") =>
        // Owner is a package → def is top-level → apply file-level wrapper
        SemanticdbSymbol.termSymbol(owner, w)
      case _ =>
        // Owner is a class/object → def has explicit owner → no wrapping
        owner

  private def typeDef(kind: SymbolKind, name: meta.Name, owner: String): SourceDefinition =
    SourceDefinition(
      name = name.value,
      kind = kind,
      symbol = SemanticdbSymbol.typeSymbol(owner, name.value),
      range = toLspRange(name.pos)
    )

  private def termDef(kind: SymbolKind, name: meta.Name, owner: String): SourceDefinition =
    SourceDefinition(
      name = name.value,
      kind = kind,
      symbol = SemanticdbSymbol.termSymbol(owner, name.value),
      range = toLspRange(name.pos)
    )

  private def methodDef(kind: SymbolKind, name: meta.Name, owner: String, overloadIndex: Int): SourceDefinition =
    SourceDefinition(
      name = name.value,
      kind = kind,
      symbol = SemanticdbSymbol.methodSymbol(owner, name.value, overloadIndex),
      range = toLspRange(name.pos)
    )

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
