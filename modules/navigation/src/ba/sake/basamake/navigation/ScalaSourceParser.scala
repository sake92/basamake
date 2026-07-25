package ba.sake.basamake.navigation

import java.io.InputStream
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.meta.*
import scala.meta.dialects.{Scala3Future, Scala213}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.SymbolKind
import scala.util.control.NonFatal

class ScalaSourceParser(path: os.Path, is: InputStream) extends StrictLogging {

  private val fileName: String = path.last

  private val topLevelWrapper: Option[String] =
    fileName match
      case ""                      => None
      case "package.scala"         => Some("package")
      case n if n.endsWith(".scala") => Some(n.stripSuffix(".scala") + "$package")
      case _                       => None

  private val defs = Vector.newBuilder[SourceSymbolDefinition]
  private val refs = Vector.newBuilder[SourceSymbolReference]
  private val fileDefs = mutable.Map.empty[String, mutable.Set[Symbol]]
  private var localCounter = 0

  private def nextLocalSym(): Symbol =
    val sym = SymbolUtils.localSymbol(localCounter)
    localCounter += 1
    sym

  private def emitDef(d: SourceSymbolDefinition): Unit =
    defs += d
    if !SymbolUtils.isLocalSymbol(d.symbol.value) then
      fileDefs.getOrElseUpdate(d.name, mutable.Set.empty) += d.symbol

  def parse(): SourceSemanticdb = try parseInternal() catch {
    case NonFatal(e) =>
      logger.warn(s"Failed to parse Scala source ${path.last}: ${e.getMessage}")
      SourceSemanticdb(Vector.empty, Vector.empty)
  }

  private def parseInternal(): SourceSemanticdb = {
    val content = String(is.readAllBytes(), StandardCharsets.UTF_8)
    parseSource(content) match
      case Some(src) =>
        // Pass 1: collect all same-file definitions for forward-reference resolution
        collectFileDefs(src.stats, SymbolUtils.packageOwner(Nil), new OverloadTracker)
        // Pass 2: extract definitions and references
        extractFromStats(src.stats, SymbolUtils.packageOwner(Nil), new OverloadTracker)
        SourceSemanticdb(defs.result(), refs.result())
      case None =>
        SourceSemanticdb(Vector.empty, Vector.empty)
  }

  private def parseSource(content: String): Option[Source] = {
    val parsed3 = { given Dialect = Scala3Future; content.parse[Source] }
    parsed3 match {
      case Parsed.Success(source) => Some(source)
      case Parsed.Error(_, _, _) =>
        val parsed213 = { given Dialect = Scala213; content.parse[Source] }
        parsed213 match
          case Parsed.Success(source) => Some(source)
          case Parsed.Error(_, _, _) => None
    }
  }

  /** Pass 1: collect all same-file symbol definitions for forward-reference resolution.
    * Only populates fileDefs — no reference extraction, no body traversal. */
  private def collectFileDefs(stats: List[Stat], owner: Symbol, overloads: OverloadTracker): Unit =
    stats.foreach {
      case p: Pkg =>
        val newOwner = pkgOwner(p.ref, owner)
        collectFileDefs(p.stats, newOwner, new OverloadTracker)
      case d: Pkg.Object =>
        val newPkg = pkgPath(owner) + d.name.value + "/"
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += SymbolUtils.termSymbol(Symbol(newPkg), "package")
        collectFileDefs(d.templ.stats, SymbolUtils.termSymbol(Symbol(newPkg), "package"), new OverloadTracker)
      case d: Defn.Class =>
        val classOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += classOwner
        fileDefs.getOrElseUpdate("<init>", mutable.Set.empty) += SymbolUtils.constructorSymbol(classOwner, 0)
        d.ctor.paramClauses.foreach { clause =>
          clause.values.foreach { param => param.name match
            case meta.Name(value) =>
              if param.mods.exists(m => m.is[Mod.ValParam] || m.is[Mod.VarParam]) then
                fileDefs.getOrElseUpdate(value, mutable.Set.empty) += SymbolUtils.termSymbol(classOwner, value)
            case _ => ()
          }
        }
        val childOvl = new OverloadTracker; childOvl.ctorIdx = 1
        collectFileDefs(d.templ.stats, classOwner, childOvl)
      case d: Defn.Trait =>
        val traitOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += traitOwner
        collectFileDefs(d.templ.stats, traitOwner, new OverloadTracker)
      case d: Defn.Object =>
        val objOwner = SymbolUtils.termSymbol(owner, d.name.value)
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += objOwner
        collectFileDefs(d.templ.stats, objOwner, new OverloadTracker)
      case d: Defn.Enum =>
        val enumOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += enumOwner
        collectFileDefs(d.templ.stats, enumOwner, new OverloadTracker)
      case d: Defn.EnumCase =>
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += SymbolUtils.termSymbol(owner, d.name.value)
      case d: Defn.RepeatedEnumCase =>
        d.cases.toList.foreach { name =>
          fileDefs.getOrElseUpdate(name.value, mutable.Set.empty) += SymbolUtils.termSymbol(owner, name.value)
        }
      case d: Defn.Given if hasName(d) =>
        val sym = SymbolUtils.termSymbol(effectiveOwner(owner), d.name.value)
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += sym
        collectFileDefs(d.templ.stats, sym, new OverloadTracker)
      case d: Defn.GivenAlias if hasName(d) =>
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += SymbolUtils.termSymbol(effectiveOwner(owner), d.name.value)
      case d: Defn.Def =>
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += SymbolUtils.methodSymbol(effectiveOwner(owner), d.name.value, 0)
      case d: Defn.Macro =>
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += SymbolUtils.methodSymbol(effectiveOwner(owner), d.name.value, 0)
      case d: Defn.Type =>
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += SymbolUtils.typeSymbol(effectiveOwner(owner), d.name.value)
      case d: Defn.Val =>
        d.pats.collect { case Pat.Var(name) =>
          fileDefs.getOrElseUpdate(name.value, mutable.Set.empty) += SymbolUtils.termSymbol(effectiveOwner(owner), name.value)
        }
      case d: Defn.Var =>
        d.pats.collect { case Pat.Var(name) =>
          fileDefs.getOrElseUpdate(name.value, mutable.Set.empty) += SymbolUtils.termSymbol(effectiveOwner(owner), name.value)
        }
      case d: Decl.Val =>
        d.pats.collect { case Pat.Var(name) =>
          fileDefs.getOrElseUpdate(name.value, mutable.Set.empty) += SymbolUtils.termSymbol(effectiveOwner(owner), name.value)
        }
      case d: Decl.Var =>
        d.pats.collect { case Pat.Var(name) =>
          fileDefs.getOrElseUpdate(name.value, mutable.Set.empty) += SymbolUtils.termSymbol(effectiveOwner(owner), name.value)
        }
      case d: Decl.Def =>
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += SymbolUtils.methodSymbol(effectiveOwner(owner), d.name.value, 0)
      case d: Decl.Type =>
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += SymbolUtils.typeSymbol(effectiveOwner(owner), d.name.value)
      case d: Decl.Given if hasName(d) =>
        fileDefs.getOrElseUpdate(d.name.value, mutable.Set.empty) += SymbolUtils.termSymbol(effectiveOwner(owner), d.name.value)
      case d: Defn.ExtensionGroup =>
        d.body match
          case block: Term.Block => collectFileDefs(block.stats, owner, overloads)
          case stat: Stat        => collectFileDefs(List(stat), owner, overloads)
          case _ => ()
      case _ => ()
    }

  private def extractFromStats(stats: List[Stat], owner: Symbol, overloads: OverloadTracker): Unit =
    stats.foreach {
      case p: Pkg =>
        val newOwner = pkgOwner(p.ref, owner)
        extractFromStats(p.stats, newOwner, new OverloadTracker)

      case d: Pkg.Object =>
        val newPkg = pkgPath(owner) + d.name.value + "/"
        val objOwner = SymbolUtils.termSymbol(Symbol(newPkg), "package")
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Object, objOwner, toSymbolLocation(d.name.pos)))
        extractFromStats(d.templ.stats, objOwner, new OverloadTracker)

      case d: Defn.Class =>
        val classOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Class, classOwner, toSymbolLocation(d.name.pos)))
        val primaryCtorSym = SymbolUtils.constructorSymbol(classOwner, 0)
        emitDef(SourceSymbolDefinition("<init>", SymbolKind.Constructor, primaryCtorSym, toSymbolLocation(d.name.pos)))
        d.ctor.paramClauses.foreach { clause =>
          clause.values.foreach { param =>
            param.name match
              case meta.Name(value) =>
                if param.mods.exists(m => m.is[Mod.ValParam] || m.is[Mod.VarParam]) then
                  val sym = SymbolUtils.termSymbol(classOwner, value)
                  emitDef(SourceSymbolDefinition(value, SymbolKind.Variable, sym, toSymbolLocation(param.name.pos)))
              case _ => ()
          }
        }
        extractRefsFromInits(d.templ.inits)
        val childOverloads = new OverloadTracker
        childOverloads.ctorIdx = 1
        extractFromStats(d.templ.stats, classOwner, childOverloads)

      case d: Defn.Trait =>
        val traitOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Interface, traitOwner, toSymbolLocation(d.name.pos)))
        extractRefsFromInits(d.templ.inits)
        extractFromStats(d.templ.stats, traitOwner, new OverloadTracker)

      case d: Defn.Object =>
        val objOwner = SymbolUtils.termSymbol(owner, d.name.value)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Object, objOwner, toSymbolLocation(d.name.pos)))
        extractRefsFromInits(d.templ.inits)
        extractFromStats(d.templ.stats, objOwner, new OverloadTracker)

      case d: Defn.Enum =>
        val enumOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Enum, enumOwner, toSymbolLocation(d.name.pos)))
        val primaryCtorSym = SymbolUtils.constructorSymbol(enumOwner, 0)
        emitDef(SourceSymbolDefinition("<init>", SymbolKind.Constructor, primaryCtorSym, toSymbolLocation(d.name.pos)))
        extractRefsFromInits(d.templ.inits)
        extractFromStats(d.templ.stats, enumOwner, new OverloadTracker)

      case d: Defn.EnumCase =>
        val sym = SymbolUtils.termSymbol(owner, d.name.value)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.EnumMember, sym, toSymbolLocation(d.name.pos)))

      case d: Defn.RepeatedEnumCase =>
        d.cases.toList.foreach { name =>
          val sym = SymbolUtils.termSymbol(owner, name.value)
          emitDef(SourceSymbolDefinition(name.value, SymbolKind.EnumMember, sym, toSymbolLocation(name.pos)))
        }

      case d: Defn.Given if hasName(d) =>
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.termSymbol(effOwner, d.name.value)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Interface, sym, toSymbolLocation(d.name.pos)))
        extractFromStats(d.templ.stats, sym, new OverloadTracker)

      case d: Defn.GivenAlias if hasName(d) =>
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.termSymbol(effOwner, d.name.value)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Interface, sym, toSymbolLocation(d.name.pos)))

      case d: Defn.Def =>
        val idx = overloads.methodIdx.getOrElse(d.name.value, 0)
        overloads.methodIdx(d.name.value) = idx + 1
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.methodSymbol(effOwner, d.name.value, idx)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Method, sym, toSymbolLocation(d.name.pos)))
        d.decltpe.foreach(extractTypeRefs)
        val bodyScope = LocalScope.empty
        d.paramClauses.foreach { clause =>
          clause.values.foreach { param =>
            param.decltpe.foreach(extractTypeRefs)
            param.name match
              case meta.Name(value) =>
                val paramSym = nextLocalSym()
                bodyScope.define(value, paramSym)
                emitDef(SourceSymbolDefinition(value, SymbolKind.Variable, paramSym, toSymbolLocation(param.name.pos)))
              case _ => ()
          }
        }
        extractRefsFromStat(d.body, bodyScope)

      case d: Defn.Macro =>
        val idx = overloads.methodIdx.getOrElse(d.name.value, 0)
        overloads.methodIdx(d.name.value) = idx + 1
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.methodSymbol(effOwner, d.name.value, idx)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Method, sym, toSymbolLocation(d.name.pos)))
        d.decltpe.foreach(extractTypeRefs)
        val bodyScope = LocalScope.empty
        d.paramClauses.foreach { clause =>
          clause.values.foreach { param =>
            param.decltpe.foreach(extractTypeRefs)
            param.name match
              case meta.Name(value) =>
                val paramSym = nextLocalSym()
                bodyScope.define(value, paramSym)
                emitDef(SourceSymbolDefinition(value, SymbolKind.Variable, paramSym, toSymbolLocation(param.name.pos)))
              case _ => ()
          }
        }
        extractRefsFromStat(d.body, bodyScope)

      case d: Defn.Type =>
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.typeSymbol(effOwner, d.name.value)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.TypeParameter, sym, toSymbolLocation(d.name.pos)))

      case d: Defn.Val =>
        d.pats.collect { case Pat.Var(name) =>
          val effOwner = effectiveOwner(owner)
          val sym = SymbolUtils.termSymbol(effOwner, name.value)
          emitDef(SourceSymbolDefinition(name.value, SymbolKind.Property, sym, toSymbolLocation(name.pos)))
        }
        extractTermRefs(d.rhs, LocalScope.empty)

      case d: Defn.Var =>
        d.pats.collect { case Pat.Var(name) =>
          val effOwner = effectiveOwner(owner)
          val sym = SymbolUtils.termSymbol(effOwner, name.value)
          emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, sym, toSymbolLocation(name.pos)))
        }
        d.rhs.foreach(extractTermRefs(_, LocalScope.empty))

      case d: Decl.Val =>
        d.pats.collect { case Pat.Var(name) =>
          val effOwner = effectiveOwner(owner)
          val sym = SymbolUtils.termSymbol(effOwner, name.value)
          emitDef(SourceSymbolDefinition(name.value, SymbolKind.Property, sym, toSymbolLocation(name.pos)))
        }

      case d: Decl.Var =>
        d.pats.collect { case Pat.Var(name) =>
          val effOwner = effectiveOwner(owner)
          val sym = SymbolUtils.termSymbol(effOwner, name.value)
          emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, sym, toSymbolLocation(name.pos)))
        }

      case d: Decl.Def =>
        val idx = overloads.methodIdx.getOrElse(d.name.value, 0)
        overloads.methodIdx(d.name.value) = idx + 1
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.methodSymbol(effOwner, d.name.value, idx)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Method, sym, toSymbolLocation(d.name.pos)))

      case d: Decl.Type =>
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.typeSymbol(effOwner, d.name.value)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.TypeParameter, sym, toSymbolLocation(d.name.pos)))

      case d: Decl.Given if hasName(d) =>
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.termSymbol(effOwner, d.name.value)
        emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Interface, sym, toSymbolLocation(d.name.pos)))

      case c: Ctor.Secondary =>
        val idx = overloads.ctorIdx
        overloads.ctorIdx = idx + 1
        val sym = SymbolUtils.constructorSymbol(owner, idx)
        emitDef(SourceSymbolDefinition("<init>", SymbolKind.Constructor, sym, toSymbolLocation(c.name.pos)))

      case d: Defn.ExtensionGroup =>
        d.body match
          case block: Term.Block => extractFromStats(block.stats, owner, overloads)
          case stat: Stat        => extractFromStats(List(stat), owner, overloads)
          case _                 => ()

      case imp: Import =>
        imp.importers.foreach { importer =>
          importer.importees.foreach {
            case Importee.Name(name) =>
              refs += SourceSymbolReference(Symbol(s"_empty_/${name.value}."), toSymbolLocation(name.pos))
              refs += SourceSymbolReference(Symbol(s"_empty_/${name.value}#"), toSymbolLocation(name.pos))
            case Importee.Rename(from, _) =>
              refs += SourceSymbolReference(Symbol(s"_empty_/${from.value}."), toSymbolLocation(from.pos))
              refs += SourceSymbolReference(Symbol(s"_empty_/${from.value}#"), toSymbolLocation(from.pos))
            case _ => ()
          }
        }

      case _: Export | _: Term | _: Defn.Given | _: Defn.GivenAlias | _: Decl.Given => ()
    }

  private def effectiveOwner(owner: Symbol): Symbol =
    topLevelWrapper match
      case Some(w) if owner.value.endsWith("/") || owner.value == "_empty_/" =>
        SymbolUtils.termSymbol(owner, w)
      case _ => owner

  private def pkgOwner(pkgRef: Term, currentOwner: Symbol): Symbol = {
    val relPkg = flattenPackageRef(pkgRef)
    val curSegments = currentOwner.value match
      case "_empty_/" => Nil
      case s          => s.stripSuffix("/").split('/').toList
    val relSegments = if relPkg.isEmpty then Nil else relPkg.split('.').toList
    SymbolUtils.packageOwner(curSegments ++ relSegments)
  }

  private def pkgPath(owner: Symbol): String =
    if owner.value == "_empty_/" then "" else owner.value

  private def flattenPackageRef(ref: Term): String = {
    val parts = List.newBuilder[String]
    def collect(ref: Term): Unit = ref match
      case Term.Name(name)         => parts += name
      case Term.Select(qual, name) => collect(qual); parts += name.value
      case _                       =>
    collect(ref)
    parts.result().mkString(".")
  }

  private def hasName(d: Defn.Given): Boolean = d.name.value.nonEmpty
  private def hasName(d: Defn.GivenAlias): Boolean = d.name.value.nonEmpty
  private def hasName(d: Decl.Given): Boolean = d.name.value.nonEmpty

  private def toSymbolLocation(pos: meta.Position): SymbolLocation =
    SymbolLocation(path = path, range = SymbolLocationRange(
      startLine = pos.startLine, startCharacter = pos.startColumn,
      endLine = pos.endLine, endCharacter = pos.endColumn))

  private def extractRefsFromStat(stat: Stat, scope: LocalScope): Unit = stat match
    case d: Defn.Val =>
      d.pats.collect { case Pat.Var(name) =>
        val localSym = nextLocalSym()
        scope.define(name.value, localSym)
        emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, localSym, toSymbolLocation(name.pos)))
      }
      d.decltpe.foreach(extractTypeRefs)
      extractTermRefs(d.rhs, scope)

    case d: Defn.Var =>
      d.pats.collect { case Pat.Var(name) =>
        val localSym = nextLocalSym()
        scope.define(name.value, localSym)
        emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, localSym, toSymbolLocation(name.pos)))
      }
      d.decltpe.foreach(extractTypeRefs)
      d.rhs.foreach(extractTermRefs(_, scope))

    case d: Defn.Def =>
      val localSym = nextLocalSym()
      scope.define(d.name.value, localSym)
      emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Method, localSym, toSymbolLocation(d.name.pos)))
      d.decltpe.foreach(extractTypeRefs)
      val bodyScope = scope.child()
      d.paramClauses.foreach { clause =>
        clause.values.foreach { param =>
          param.decltpe.foreach(extractTypeRefs)
          param.name match
            case meta.Name(value) =>
              val paramSym = nextLocalSym()
              bodyScope.define(value, paramSym)
              emitDef(SourceSymbolDefinition(value, SymbolKind.Variable, paramSym, toSymbolLocation(param.name.pos)))
            case _ => ()
        }
      }
      extractTermRefs(d.body, bodyScope)

    case d: Defn.Macro =>
      val localSym = nextLocalSym()
      scope.define(d.name.value, localSym)
      emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.Method, localSym, toSymbolLocation(d.name.pos)))
      d.decltpe.foreach(extractTypeRefs)
      val bodyScope = scope.child()
      d.paramClauses.foreach { clause =>
        clause.values.foreach { param =>
          param.decltpe.foreach(extractTypeRefs)
          param.name match
            case meta.Name(value) =>
              val paramSym = nextLocalSym()
              bodyScope.define(value, paramSym)
              emitDef(SourceSymbolDefinition(value, SymbolKind.Variable, paramSym, toSymbolLocation(param.name.pos)))
            case _ => ()
        }
      }
      extractTermRefs(d.body, bodyScope)

    case d: Defn.Type =>
      val localSym = nextLocalSym()
      scope.define(d.name.value, localSym)
      emitDef(SourceSymbolDefinition(d.name.value, SymbolKind.TypeParameter, localSym, toSymbolLocation(d.name.pos)))

    case t: Term => extractTermRefs(t, scope)
    case _ => ()

  private def extractTermRefs(term: Term, scope: LocalScope): Unit = term match
    case t: Term.Name =>
      scope.lookup(t.value) match
        case Some(localSym) =>
          refs += SourceSymbolReference(localSym, toSymbolLocation(t.pos))
        case None =>
          fileDefs.get(t.value) match
            case Some(syms) =>
              syms.foreach(sym => refs += SourceSymbolReference(sym, toSymbolLocation(t.pos)))
            case None =>
              refs += SourceSymbolReference(Symbol(s"_empty_/${t.value}."), toSymbolLocation(t.pos))
              refs += SourceSymbolReference(Symbol(s"_empty_/${t.value}#"), toSymbolLocation(t.pos))

    case t: Term.Select =>
      extractTermRefs(t.qual, scope)
      t.qual match
        case Term.Name(qualName) =>
          val member = SymbolUtils.escapedName(t.name.value)
          refs += SourceSymbolReference(Symbol(s"_empty_/$qualName.$member."), toSymbolLocation(t.name.pos))
          refs += SourceSymbolReference(Symbol(s"_empty_/$qualName.$member()."), toSymbolLocation(t.name.pos))
        case _ => ()

    case t: Term.Apply =>
      extractTermRefs(t.fun, scope)
      t.args.foreach(extractTermRefs(_, scope))

    case t: Term.ApplyType =>
      extractTermRefs(t.fun, scope)

    case t: Term.ApplyInfix =>
      extractTermRefs(t.lhs, scope)
      extractTermRefs(t.op, scope)
      t.args.foreach(extractTermRefs(_, scope))

    case t: Term.ApplyUnary => extractTermRefs(t.arg, scope)

    case t: Term.Block =>
      val blockScope = scope.child()
      t.stats.foreach(extractRefsFromStat(_, blockScope))

    case t: Term.Assign =>
      extractTermRefs(t.lhs, scope)
      extractTermRefs(t.rhs, scope)

    case t: Term.If =>
      extractTermRefs(t.cond, scope)
      extractTermRefs(t.thenp, scope)
      extractTermRefs(t.elsep, scope)

    case t: Term.While =>
      extractTermRefs(t.expr, scope)
      extractTermRefs(t.body, scope)

    case t: Term.Do =>
      extractTermRefs(t.body, scope)
      extractTermRefs(t.expr, scope)

    case t: Term.For =>
      t.enums.foreach {
        case e: Enumerator.Generator =>
          e.pat.collect { case Pat.Var(name) =>
            val localSym = nextLocalSym()
            scope.define(name.value, localSym)
            emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, localSym, toSymbolLocation(name.pos)))
          }
          extractTermRefs(e.rhs, scope)
        case e: Enumerator.Val =>
          e.pat.collect { case Pat.Var(name) =>
            val localSym = nextLocalSym()
            scope.define(name.value, localSym)
            emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, localSym, toSymbolLocation(name.pos)))
          }
          extractTermRefs(e.rhs, scope)
        case _ => ()
      }
      extractTermRefs(t.body, scope)

    case t: Term.ForYield =>
      t.enums.foreach {
        case e: Enumerator.Generator =>
          e.pat.collect { case Pat.Var(name) =>
            val localSym = nextLocalSym()
            scope.define(name.value, localSym)
            emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, localSym, toSymbolLocation(name.pos)))
          }
          extractTermRefs(e.rhs, scope)
        case e: Enumerator.Val =>
          e.pat.collect { case Pat.Var(name) =>
            val localSym = nextLocalSym()
            scope.define(name.value, localSym)
            emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, localSym, toSymbolLocation(name.pos)))
          }
          extractTermRefs(e.rhs, scope)
        case _ => ()
      }
      extractTermRefs(t.body, scope)

    case t: Term.Match =>
      extractTermRefs(t.expr, scope)
      t.cases.foreach { c =>
        val caseScope = scope.child()
        c.pat.collect { case Pat.Var(name) =>
          val localSym = nextLocalSym()
          caseScope.define(name.value, localSym)
          emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, localSym, toSymbolLocation(name.pos)))
        }
        extractTermRefs(c.body, caseScope)
        c.cond.foreach(extractTermRefs(_, caseScope))
      }

    case t: Term.New => extractRefsFromInits(List(t.init))
    case t: Term.NewAnonymous =>
      extractRefsFromInits(t.templ.inits)
      val bodyScope = scope.child()
      t.templ.stats.foreach(extractRefsFromStat(_, bodyScope))

    case t: Term.Function =>
      val fnScope = scope.child()
      t.params.foreach { param =>
        param.decltpe.foreach(extractTypeRefs)
        param.name match
          case meta.Name(value) =>
            val localSym = nextLocalSym()
            fnScope.define(value, localSym)
            emitDef(SourceSymbolDefinition(value, SymbolKind.Variable, localSym, toSymbolLocation(param.name.pos)))
          case _ => ()
      }
      extractTermRefs(t.body, fnScope)

    case t: Term.PartialFunction =>
      t.cases.foreach { c =>
        val caseScope = scope.child()
        c.pat.collect { case Pat.Var(name) =>
          val localSym = nextLocalSym()
          caseScope.define(name.value, localSym)
          emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, localSym, toSymbolLocation(name.pos)))
        }
        extractTermRefs(c.body, caseScope)
        c.cond.foreach(extractTermRefs(_, caseScope))
      }

    case t: Term.Return => extractTermRefs(t.expr, scope)
    case t: Term.Throw => extractTermRefs(t.expr, scope)

    case t: Term.Try =>
      extractTermRefs(t.expr, scope)
      t.catchp.foreach { catchBlock =>
        val caseScope = scope.child()
        catchBlock.pat.collect { case Pat.Var(name) =>
          val localSym = nextLocalSym()
          caseScope.define(name.value, localSym)
          emitDef(SourceSymbolDefinition(name.value, SymbolKind.Variable, localSym, toSymbolLocation(name.pos)))
        }
        extractTermRefs(catchBlock.body, caseScope)
        catchBlock.cond.foreach(extractTermRefs(_, caseScope))
      }
      t.finallyp.foreach(extractTermRefs(_, scope))

    case t: Term.Tuple => t.args.foreach(extractTermRefs(_, scope))
    case t: Term.Interpolate => t.args.foreach(extractTermRefs(_, scope))
    case t: Term.Ascribe => extractTermRefs(t.expr, scope)
    case t: Term.Annotate => extractTermRefs(t.expr, scope)
    case t: Term.Repeated => extractTermRefs(t.expr, scope)
    case t: Term.Eta => extractTermRefs(t.expr, scope)
    case _: Lit | _: Term.This | _: Term.Super | _: Term.Placeholder |
         _: Term.QuotedMacroExpr | _: Term.SplicedMacroExpr | _: Term.Xml => ()

  private def extractRefsFromInits(inits: List[Init]): Unit =
    inits.foreach(init => extractTypeRefs(init.tpe))

  private def extractTypeRefs(tpe: Type): Unit = tpe match
    case Type.Name(name) =>
      refs += SourceSymbolReference(Symbol(s"_empty_/$name#"), toSymbolLocation(tpe.pos))
      refs += SourceSymbolReference(Symbol(s"_empty_/$name."), toSymbolLocation(tpe.pos))
    case Type.Select(_, _) => ()
    case Type.Apply(tpe, args) => extractTypeRefs(tpe); args.foreach(extractTypeRefs)
    case Type.ApplyInfix(lhs, _, rhs) => extractTypeRefs(lhs); extractTypeRefs(rhs)
    case Type.Tuple(args) => args.foreach(extractTypeRefs)
    case Type.Function(params, res) => params.foreach(extractTypeRefs); extractTypeRefs(res)
    case Type.With(lhs, rhs) => extractTypeRefs(lhs); extractTypeRefs(rhs)
    case Type.Refine(tpe, _) => tpe.foreach(extractTypeRefs)
    case Type.Repeated(tpe) => extractTypeRefs(tpe)
    case Type.Annotate(tpe, _) => extractTypeRefs(tpe)
    case Type.ByName(tpe) => extractTypeRefs(tpe)
    case Type.Match(_, cases) => cases.foreach(c => extractTypeRefs(c.body))
    case Type.Lambda(_, tpe) => extractTypeRefs(tpe)
    case Type.PolyFunction(_, tpe) => extractTypeRefs(tpe)
    case _ => ()
}

object ScalaSourceParser {
  def apply(path: os.Path): ScalaSourceParser =
    new ScalaSourceParser(path, os.read.inputStream(path))
  def apply(str: String, fileName: String = "<inmemory>.scala"): ScalaSourceParser =
    new ScalaSourceParser(os.pwd / fileName, new java.io.ByteArrayInputStream(str.getBytes))
}
