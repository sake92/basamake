package ba.sake.basamake.navigation

import java.io.InputStream
import java.nio.charset.StandardCharsets
import scala.meta.*
import scala.meta.dialects.{Scala3Future, Scala213}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.SymbolKind
import scala.util.control.NonFatal

/** Parses Scala source files to extract SemanticDB-compatible symbol definitions
  * and same-file references. Single-pass traversal over scalameta AST.
  *
  * Constructor takes path (for location metadata) and input stream (for content).
  * The parser instance is throw-away — one parse per file.
  */
class ScalaSourceParser(path: os.Path, is: InputStream) extends StrictLogging {

  // ── Derived constants ────────────────────────────
  private val fileName: String = path.last

  private val topLevelWrapper: Option[String] =
    fileName match
      case ""                      => None
      case "package.scala"         => Some("package")
      case n if n.endsWith(".scala") => Some(n.stripSuffix(".scala") + "$package")
      case _                       => None

  // ── Accumulators (class fields, parser is throw-away) ──
  private val defs = Vector.newBuilder[SourceSymbolDefinition]
  private val refs = Vector.newBuilder[SourceSymbolReference]
  private var localCounter = 0

  private def nextLocalSym(): Symbol =
    val sym = SymbolUtils.localSymbol(localCounter)
    localCounter += 1
    sym

  // ── Public API ────────────────────────────────────

  /** Parse the source and extract definitions + references.
    * Returns empty vectors on parse failure. */
  def parse(): SourceSemanticdb = try parseInternal() catch {
    case NonFatal(e) =>
      logger.warn(s"Failed to parse Scala source ${path.last}: ${e.getMessage}")
      SourceSemanticdb(Vector.empty, Vector.empty)
  }

  private def parseInternal(): SourceSemanticdb = {
    val content = String(is.readAllBytes(), StandardCharsets.UTF_8)
    parseSource(content) match
      case Some(src) =>
        val scope = ScopeTracker.empty
        // Pass 1: collect all definitions (populates scope for forward references)
        collectDefinitions(src.stats, SymbolUtils.packageOwner(Nil), scope, new OverloadTracker)
        // Pass 2: extract definitions and references (body traversal now sees all defs)
        extractFromStats(src.stats, SymbolUtils.packageOwner(Nil), scope, new OverloadTracker)
        SourceSemanticdb(defs.result(), refs.result())
      case None =>
        SourceSemanticdb(Vector.empty, Vector.empty)
  }

  // ── Source parsing ───────────────────────────────

  private def parseSource(content: String): Option[Source] = {
    val parsed3 = {
      given Dialect = Scala3Future
      content.parse[Source]
    }
    parsed3 match {
      case Parsed.Success(source) => Some(source)
      case Parsed.Error(_, _, _) =>
        val parsed213 = {
          given Dialect = Scala213
          content.parse[Source]
        }
        parsed213 match
          case Parsed.Success(source) => Some(source)
          case Parsed.Error(_, _, _) => None
    }
  }

  /** Pass 1: walk stats shallowly to populate scope with all definitions.
    * No reference extraction, no body traversal. Enables forward references
    * during pass 2. */
  private def collectDefinitions(
      stats: List[Stat],
      owner: Symbol,
      scope: ScopeTracker,
      overloads: OverloadTracker
  ): Unit =
    stats.foreach {
      case p: Pkg =>
        val newOwner = pkgOwner(p.ref, owner)
        val childScope = scope.child()
        collectDefinitions(p.stats, newOwner, childScope, new OverloadTracker)

      case d: Pkg.Object =>
        val newPkg = pkgPath(owner) + d.name.value + "/"
        val objOwner = SymbolUtils.termSymbol(Symbol(newPkg), "package")
        scope.defineTerm(d.name.value, objOwner)
        val childScope = scope.child()
        collectDefinitions(d.templ.stats, objOwner, childScope, new OverloadTracker)

      case d: Defn.Class =>
        val classOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        scope.defineType(d.name.value, classOwner)
        val childScope = scope.child()
        collectDefinitions(d.templ.stats, classOwner, childScope, new OverloadTracker)

      case d: Defn.Trait =>
        val traitOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        scope.defineType(d.name.value, traitOwner)
        val childScope = scope.child()
        collectDefinitions(d.templ.stats, traitOwner, childScope, new OverloadTracker)

      case d: Defn.Object =>
        val objOwner = SymbolUtils.termSymbol(owner, d.name.value)
        scope.defineTerm(d.name.value, objOwner)
        val childScope = scope.child()
        collectDefinitions(d.templ.stats, objOwner, childScope, new OverloadTracker)

      case d: Defn.Enum =>
        val enumOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        scope.defineType(d.name.value, enumOwner)
        val childScope = scope.child()
        collectDefinitions(d.templ.stats, enumOwner, childScope, new OverloadTracker)

      case d: Defn.EnumCase =>
        val sym = SymbolUtils.termSymbol(owner, d.name.value)
        scope.defineTerm(d.name.value, sym)

      case d: Defn.RepeatedEnumCase =>
        d.cases.toList.foreach { name =>
          val sym = SymbolUtils.termSymbol(owner, name.value)
          scope.defineTerm(name.value, sym)
        }

      case d: Defn.Given if hasName(d) =>
        scope.defineTerm(d.name.value, SymbolUtils.termSymbol(effectiveOwner(owner), d.name.value))
        val childScope = scope.child()
        collectDefinitions(d.templ.stats, SymbolUtils.termSymbol(effectiveOwner(owner), d.name.value), childScope, new OverloadTracker)

      case d: Defn.GivenAlias if hasName(d) =>
        scope.defineTerm(d.name.value, SymbolUtils.termSymbol(effectiveOwner(owner), d.name.value))

      case d: Defn.Def =>
        scope.defineTerm(d.name.value, SymbolUtils.methodSymbol(effectiveOwner(owner), d.name.value, 0))

      case d: Defn.Macro =>
        scope.defineTerm(d.name.value, SymbolUtils.methodSymbol(effectiveOwner(owner), d.name.value, 0))

      case d: Defn.Type =>
        scope.defineType(d.name.value, SymbolUtils.typeSymbol(effectiveOwner(owner), d.name.value))

      case d: Defn.Val =>
        d.pats.collect { case Pat.Var(name) =>
          scope.defineTerm(name.value, SymbolUtils.termSymbol(effectiveOwner(owner), name.value))
        }

      case d: Defn.Var =>
        d.pats.collect { case Pat.Var(name) =>
          scope.defineTerm(name.value, SymbolUtils.termSymbol(effectiveOwner(owner), name.value))
        }

      case d: Decl.Val =>
        d.pats.collect { case Pat.Var(name) =>
          scope.defineTerm(name.value, SymbolUtils.termSymbol(effectiveOwner(owner), name.value))
        }

      case d: Decl.Var =>
        d.pats.collect { case Pat.Var(name) =>
          scope.defineTerm(name.value, SymbolUtils.termSymbol(effectiveOwner(owner), name.value))
        }

      case d: Decl.Def =>
        scope.defineTerm(d.name.value, SymbolUtils.methodSymbol(effectiveOwner(owner), d.name.value, 0))

      case d: Decl.Type =>
        scope.defineType(d.name.value, SymbolUtils.typeSymbol(effectiveOwner(owner), d.name.value))

      case d: Decl.Given if hasName(d) =>
        scope.defineTerm(d.name.value, SymbolUtils.termSymbol(effectiveOwner(owner), d.name.value))

      case _: Import => ()  // imports handled in pass 2
      case _ => ()
    }

  // ── Core traversal ───────────────────────────────

  /** Walk a list of stats, emitting definitions and references.
    *
    * @param owner     SemanticDB owner symbol for this scope (package or definition)
    * @param scope     Scope tracker for reference resolution
    * @param overloads Per-scope overload counter (methods and constructors)
    */
  private def extractFromStats(
      stats: List[Stat],
      owner: Symbol,
      scope: ScopeTracker,
      overloads: OverloadTracker
  ): Unit =
    stats.foreach {
      case p: Pkg =>
        val newOwner = pkgOwner(p.ref, owner)
        val childScope = scope.child()
        // Package is a scope boundary — reset overloads for new package
        extractFromStats(p.stats, newOwner, childScope, new OverloadTracker)

      case d: Pkg.Object =>
        val newPkg = pkgPath(owner) + d.name.value + "/"
        val objOwner = SymbolUtils.termSymbol(Symbol(newPkg), "package")
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Object, objOwner, toSymbolLocation(d.name.pos))
        scope.defineTerm(d.name.value, objOwner)
        extractRefsFromInits(d.templ.inits, scope)
        val childScope = scope.child()
        extractFromStats(d.templ.stats, objOwner, childScope, new OverloadTracker)

      case d: Defn.Class =>
        val classOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        // Class definition
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Class, classOwner, toSymbolLocation(d.name.pos))
        scope.defineType(d.name.value, classOwner)

        // Primary constructor (always emitted, idx 0)
        val primaryCtorSym = SymbolUtils.constructorSymbol(classOwner, 0)
        defs += SourceSymbolDefinition("<init>", SymbolKind.Constructor, primaryCtorSym, toSymbolLocation(d.name.pos))

        // Extract refs from primary ctor params, extends inits, self type
        extractRefsFromTermParams(d.ctor.paramClauses, scope)
        extractRefsFromInits(d.templ.inits, scope)
        d.templ.self.decltpe.foreach(extractTypeRefs(_, scope))

        // Enter body scope with ctor tracking starting at 1
        val childScope = scope.child()
        val childOverloads = new OverloadTracker
        childOverloads.ctorIdx = 1 // primary consumed 0
        extractFromStats(d.templ.stats, classOwner, childScope, childOverloads)

      case d: Defn.Trait =>
        val traitOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Interface, traitOwner, toSymbolLocation(d.name.pos))
        scope.defineType(d.name.value, traitOwner)
        extractRefsFromInits(d.templ.inits, scope)
        d.templ.self.decltpe.foreach(extractTypeRefs(_, scope))
        val childScope = scope.child()
        extractFromStats(d.templ.stats, traitOwner, childScope, new OverloadTracker)

      case d: Defn.Object =>
        val objOwner = SymbolUtils.termSymbol(owner, d.name.value)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Object, objOwner, toSymbolLocation(d.name.pos))
        scope.defineTerm(d.name.value, objOwner)
        extractRefsFromInits(d.templ.inits, scope)
        val childScope = scope.child()
        extractFromStats(d.templ.stats, objOwner, childScope, new OverloadTracker)

      case d: Defn.Enum =>
        val enumOwner = SymbolUtils.typeSymbol(owner, d.name.value)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Enum, enumOwner, toSymbolLocation(d.name.pos))
        scope.defineType(d.name.value, enumOwner)
        // Primary constructor (always emitted for enum, same as class)
        val primaryCtorSym = SymbolUtils.constructorSymbol(enumOwner, 0)
        defs += SourceSymbolDefinition("<init>", SymbolKind.Constructor, primaryCtorSym, toSymbolLocation(d.name.pos))
        extractRefsFromTermParams(d.ctor.paramClauses, scope)
        extractRefsFromInits(d.templ.inits, scope)
        val childScope = scope.child()
        extractFromStats(d.templ.stats, enumOwner, childScope, new OverloadTracker)

      case d: Defn.EnumCase =>
        val sym = SymbolUtils.termSymbol(owner, d.name.value)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.EnumMember, sym, toSymbolLocation(d.name.pos))
        scope.defineTerm(d.name.value, sym)
        extractRefsFromInits(d.inits, scope)

      case d: Defn.RepeatedEnumCase =>
        d.cases.toList.foreach { name =>
          val sym = SymbolUtils.termSymbol(owner, name.value)
          defs += SourceSymbolDefinition(name.value, SymbolKind.EnumMember, sym, toSymbolLocation(name.pos))
          scope.defineTerm(name.value, sym)
        }

      case d: Defn.Given if hasName(d) =>
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.termSymbol(effOwner, d.name.value)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Interface, sym, toSymbolLocation(d.name.pos))
        scope.defineTerm(d.name.value, sym)
        d.templ.inits.foreach(init => extractTypeRefs(init.tpe, scope))
        val childScope = scope.child()
        extractFromStats(d.templ.stats, sym, childScope, new OverloadTracker)

      case d: Defn.GivenAlias if hasName(d) =>
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.termSymbol(effOwner, d.name.value)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Interface, sym, toSymbolLocation(d.name.pos))
        scope.defineTerm(d.name.value, sym)

      case d: Defn.Def =>
        val idx = overloads.methodIdx.getOrElse(d.name.value, 0)
        overloads.methodIdx(d.name.value) = idx + 1
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.methodSymbol(effOwner, d.name.value, idx)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Method, sym, toSymbolLocation(d.name.pos))
        scope.defineTerm(d.name.value, sym)
        extractRefsFromTermParams(d.paramClauses, scope)
        d.decltpe.foreach(extractTypeRefs(_, scope))
        val bodyScope = scope.child()
        d.paramClauses.foreach { clause =>
          clause.values.foreach { param =>
            param.name match
              case meta.Name(value) => bodyScope.defineTerm(value, nextLocalSym())
              case _ => ()
          }
        }
        extractRefsFromStat(d.body, bodyScope)

      case d: Defn.Macro =>
        val idx = overloads.methodIdx.getOrElse(d.name.value, 0)
        overloads.methodIdx(d.name.value) = idx + 1
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.methodSymbol(effOwner, d.name.value, idx)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Method, sym, toSymbolLocation(d.name.pos))
        scope.defineTerm(d.name.value, sym)
        extractRefsFromTermParams(d.paramClauses, scope)
        d.decltpe.foreach(extractTypeRefs(_, scope))
        val bodyScope = scope.child()
        d.paramClauses.foreach { clause =>
          clause.values.foreach { param =>
            param.name match
              case meta.Name(value) => bodyScope.defineTerm(value, nextLocalSym())
              case _ => ()
          }
        }
        extractRefsFromStat(d.body, bodyScope)

      case d: Defn.Type =>
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.typeSymbol(effOwner, d.name.value)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.TypeParameter, sym, toSymbolLocation(d.name.pos))
        scope.defineType(d.name.value, sym)
        extractTypeRefs(d.body, scope)

      case d: Defn.Val =>
        d.pats.collect { case Pat.Var(name) =>
          val effOwner = effectiveOwner(owner)
          val sym = SymbolUtils.termSymbol(effOwner, name.value)
          defs += SourceSymbolDefinition(name.value, SymbolKind.Property, sym, toSymbolLocation(name.pos))
          scope.defineTerm(name.value, sym)
        }
        d.decltpe.foreach(extractTypeRefs(_, scope))
        extractTermRefs(d.rhs, scope)

      case d: Defn.Var =>
        d.pats.collect { case Pat.Var(name) =>
          val effOwner = effectiveOwner(owner)
          val sym = SymbolUtils.termSymbol(effOwner, name.value)
          defs += SourceSymbolDefinition(name.value, SymbolKind.Variable, sym, toSymbolLocation(name.pos))
          scope.defineTerm(name.value, sym)
        }
        d.decltpe.foreach(extractTypeRefs(_, scope))
        d.rhs.foreach(extractTermRefs(_, scope))

      case d: Decl.Val =>
        d.pats.collect { case Pat.Var(name) =>
          val effOwner = effectiveOwner(owner)
          val sym = SymbolUtils.termSymbol(effOwner, name.value)
          defs += SourceSymbolDefinition(name.value, SymbolKind.Property, sym, toSymbolLocation(name.pos))
          scope.defineTerm(name.value, sym)
        }
        extractTypeRefs(d.decltpe, scope)

      case d: Decl.Var =>
        d.pats.collect { case Pat.Var(name) =>
          val effOwner = effectiveOwner(owner)
          val sym = SymbolUtils.termSymbol(effOwner, name.value)
          defs += SourceSymbolDefinition(name.value, SymbolKind.Variable, sym, toSymbolLocation(name.pos))
          scope.defineTerm(name.value, sym)
        }
        extractTypeRefs(d.decltpe, scope)

      case d: Decl.Def =>
        val idx = overloads.methodIdx.getOrElse(d.name.value, 0)
        overloads.methodIdx(d.name.value) = idx + 1
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.methodSymbol(effOwner, d.name.value, idx)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Method, sym, toSymbolLocation(d.name.pos))
        scope.defineTerm(d.name.value, sym)
        extractRefsFromTermParams(d.paramClauses, scope)
        extractTypeRefs(d.decltpe, scope)

      case d: Decl.Type =>
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.typeSymbol(effOwner, d.name.value)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.TypeParameter, sym, toSymbolLocation(d.name.pos))
        scope.defineTerm(d.name.value, sym)

      case d: Decl.Given if hasName(d) =>
        val effOwner = effectiveOwner(owner)
        val sym = SymbolUtils.termSymbol(effOwner, d.name.value)
        defs += SourceSymbolDefinition(d.name.value, SymbolKind.Interface, sym, toSymbolLocation(d.name.pos))
        scope.defineTerm(d.name.value, sym)

      case c: Ctor.Secondary =>
        val idx = overloads.ctorIdx
        overloads.ctorIdx = idx + 1
        val sym = SymbolUtils.constructorSymbol(owner, idx)
        defs += SourceSymbolDefinition("<init>", SymbolKind.Constructor, sym, toSymbolLocation(c.name.pos))
        extractRefsFromTermParams(c.paramClauses, scope)

      case imp: Import =>
        extractImportRefs(imp, scope)

      case d: Defn.ExtensionGroup =>
        // Extension group wraps methods; extract param refs, then recurse into body
        extractRefsFromTermParams(d.paramClauses, scope)
        d.body match
          case block: Term.Block => extractFromStats(block.stats, owner, scope, overloads)
          case stat: Stat        => extractFromStats(List(stat), owner, scope, overloads)
          case _                 => ()

      case _: Export          => ()
      case _: Term            => ()
      case _: Defn.Given      => ()  // anonymous (named matched by guarded case above)
      case _: Defn.GivenAlias => ()  // anonymous
      case _: Decl.Given      => ()  // anonymous
    }

  // ── Owner helpers ─────────────────────────────────

  /** Compute the effective owner for a term-level definition.
    * If we're at package level and this file has a top-level wrapper,
    * wrap the definition under the wrapper (e.g. `Foo$package.`).
    * Type definitions (class/trait/object) call their owner directly,
    * bypassing this method. */
  private def effectiveOwner(owner: Symbol): Symbol =
    topLevelWrapper match
      case Some(w) if owner.value.endsWith("/") || owner.value == "_empty_/" =>
        SymbolUtils.termSymbol(owner, w)
      case _ => owner

  /** Compute a new package owner for a nested package declaration.
    * Accumulates the full package path from the current owner + the Pkg ref. */
  private def pkgOwner(pkgRef: Term, currentOwner: Symbol): Symbol = {
    val relPkg = flattenPackageRef(pkgRef)
    val curSegments = currentOwner.value match
      case "_empty_/" => Nil
      case s          => s.stripSuffix("/").split('/').toList
    val relSegments = if relPkg.isEmpty then Nil else relPkg.split('.').toList
    SymbolUtils.packageOwner(curSegments ++ relSegments)
  }

  /** Extract the raw package path from an owner symbol.
    * Package owners end with `/`; returns the path portion.
    * E.g. "com/example/" → "com/example/", "_empty_/" → "" */
  private def pkgPath(owner: Symbol): String =
    if owner.value == "_empty_/" then "" else owner.value

  /** Flatten a package Term reference (nested Select) to "a.b.c". */
  private def flattenPackageRef(ref: Term): String = {
    val parts = List.newBuilder[String]
    def collect(ref: Term): Unit = ref match
      case Term.Name(name)         => parts += name
      case Term.Select(qual, name) => collect(qual); parts += name.value
      case _                       =>
    collect(ref)
    parts.result().mkString(".")
  }

  /** Flatten a Term reference to a slash-separated path for imports.
    * E.g. Term.Select(Term.Name("scala"), Term.Name("collection")) → "scala/collection/" */
  private def flattenTermRef(ref: Term): String = {
    val parts = List.newBuilder[String]
    def collect(ref: Term): Unit = ref match
      case Term.Name(name)         => parts += name
      case Term.Select(qual, name) => collect(qual); parts += name.value
      case _                       =>
    collect(ref)
    parts.result().mkString("", "/", "/")
  }

  /** Check if a given definition has an explicit name. */
  private def hasName(d: Defn.Given): Boolean = d.name.value.nonEmpty
  private def hasName(d: Defn.GivenAlias): Boolean = d.name.value.nonEmpty
  private def hasName(d: Decl.Given): Boolean = d.name.value.nonEmpty

  // ── Position helpers ──────────────────────────────

  private def toSymbolLocation(pos: meta.Position): SymbolLocation =
    SymbolLocation(
      path = path,
      range = SymbolLocationRange(
        startLine = pos.startLine,
        startCharacter = pos.startColumn,
        endLine = pos.endLine,
        endCharacter = pos.endColumn
      )
    )

  // ── Reference extraction ──────────────────────────

  /** Walk type tree recursively and emit references for resolved names. */
  private def extractTypeRefs(tpe: Type, scope: ScopeTracker): Unit = tpe match
    case Type.Name(name) =>
      resolveAndEmit(name, scope, tpe.pos)
    case Type.Select(_, _) =>
      // qual is a Term.Ref (package path), which we can't resolve as a Type.ref
      // The full Type.Select path represents a qualified type — skip for now
      ()
    case Type.Apply(tpe, args) =>
      extractTypeRefs(tpe, scope)
      args.foreach(extractTypeRefs(_, scope))
    case Type.ApplyInfix(lhs, _, rhs) =>
      extractTypeRefs(lhs, scope)
      extractTypeRefs(rhs, scope)
    case Type.Tuple(args) =>
      args.foreach(extractTypeRefs(_, scope))
    case Type.Function(params, res) =>
      params.foreach(extractTypeRefs(_, scope))
      extractTypeRefs(res, scope)
    case Type.With(lhs, rhs) =>
      extractTypeRefs(lhs, scope)
      extractTypeRefs(rhs, scope)
    case Type.Refine(tpe, _) =>
      tpe.foreach(extractTypeRefs(_, scope))
    case Type.Repeated(tpe) =>
      extractTypeRefs(tpe, scope)
    case Type.Annotate(tpe, _) =>
      extractTypeRefs(tpe, scope)
    case Type.ByName(tpe) =>
      extractTypeRefs(tpe, scope)
    case Type.Match(_, cases) =>
      cases.foreach(c => extractTypeRefs(c.body, scope))
    case Type.Lambda(_, tpe) =>
      extractTypeRefs(tpe, scope)
    case Type.PolyFunction(_, tpe) =>
      extractTypeRefs(tpe, scope)
    case _ => ()

  /** Extract type references from extends / new Init clauses. */
  private def extractRefsFromInits(inits: List[Init], scope: ScopeTracker): Unit =
    inits.foreach { init => extractTypeRefs(init.tpe, scope) }

  /** Extract type references from parameter clauses (constructor params). */
  private def extractRefsFromTermParams(
      paramClauses: Seq[Term.ParamClause],
      scope: ScopeTracker
  ): Unit =
    paramClauses.foreach { clause =>
      clause.values.foreach { param =>
        param.decltpe.foreach(extractTypeRefs(_, scope))
      }
    }

  /** Extract references from import statements.
    * For explicit imports, we know the full symbol path.
    * Wildcard imports are skipped (can't resolve statically). */
  private def extractImportRefs(imp: Import, scope: ScopeTracker): Unit =
    imp.importers.foreach { importer =>
      val basePath = flattenTermRef(importer.ref)
      importer.importees.foreach {
        case Importee.Name(name) =>
          // Emit type ref (best guess — could also be term, but type is more common)
          val importSym = SymbolUtils.typeSymbol(Symbol(basePath), name.value)
          refs += SourceSymbolReference(importSym, toSymbolLocation(name.pos))
          // Add to scope as import entry (stores base path for future resolution)
          scope.defineImport(name.value, basePath)

        case Importee.Rename(from, to) =>
          val importSym = SymbolUtils.typeSymbol(Symbol(basePath), from.value)
          refs += SourceSymbolReference(importSym, toSymbolLocation(from.pos))
          scope.defineImport(to.value, basePath)

        case _: Importee.Wildcard =>
          () // can't resolve statically

        case _ => ()
      }
    }

  // ── Body-level reference extraction ──────────────

  /** Extract references (no definitions) from a single stat inside a function body.
    * Defines local vals/vars/defs in the body scope so later usages resolve. */
  private def extractRefsFromStat(stat: Stat, scope: ScopeTracker): Unit = stat match
    case d: Defn.Val =>
      d.pats.collect { case Pat.Var(name) =>
        scope.defineTerm(name.value, nextLocalSym())
      }
      d.decltpe.foreach(extractTypeRefs(_, scope))
      extractTermRefs(d.rhs, scope)

    case d: Defn.Var =>
      d.pats.collect { case Pat.Var(name) =>
        scope.defineTerm(name.value, nextLocalSym())
      }
      d.decltpe.foreach(extractTypeRefs(_, scope))
      d.rhs.foreach(extractTermRefs(_, scope))

    case d: Defn.Def =>
      scope.defineTerm(d.name.value, nextLocalSym())
      extractRefsFromTermParams(d.paramClauses, scope)
      d.decltpe.foreach(extractTypeRefs(_, scope))
      val bodyScope = scope.child()
      d.paramClauses.foreach { clause =>
        clause.values.foreach { param =>
          param.name match
            case meta.Name(value) => bodyScope.defineTerm(value, nextLocalSym())
            case _ => ()
        }
      }
      extractTermRefs(d.body, bodyScope)

    case d: Defn.Macro =>
      scope.defineTerm(d.name.value, nextLocalSym())
      extractRefsFromTermParams(d.paramClauses, scope)
      d.decltpe.foreach(extractTypeRefs(_, scope))
      val bodyScope = scope.child()
      d.paramClauses.foreach { clause =>
        clause.values.foreach { param =>
          param.name match
            case meta.Name(value) => bodyScope.defineTerm(value, nextLocalSym())
            case _ => ()
        }
      }
      extractTermRefs(d.body, bodyScope)

    case d: Defn.Type =>
      scope.defineType(d.name.value, nextLocalSym())
      extractTypeRefs(d.body, scope)

    case imp: Import =>
      extractImportRefs(imp, scope)

    case t: Term =>
      extractTermRefs(t, scope)

    case _ => ()

  /** Walk a Term AST recursively, emitting references for resolved names.
    * Block expressions create child scopes for their contents. */
  private def extractTermRefs(term: Term, scope: ScopeTracker): Unit = term match
    case t: Term.Name =>
      scope.resolve(t.value).foreach { sym =>
        refs += SourceSymbolReference(sym, toSymbolLocation(t.pos))
      }

    case t: Term.Select =>
      extractTermRefs(t.qual, scope)
      // Emit member name candidates: both val and def descriptors.
      // Lookup filters by existence — only real definitions reach LSP.
      t.qual match
        case Term.Name(qualName) =>
          scope.resolve(qualName).foreach { baseSym =>
            val member = SymbolUtils.escapedName(t.name.value)
            val valSym = Symbol(s"${baseSym.value}$member.")
            val defSym = Symbol(s"${baseSym.value}$member().")
            refs += SourceSymbolReference(valSym, toSymbolLocation(t.name.pos))
            refs += SourceSymbolReference(defSym, toSymbolLocation(t.name.pos))
          }
        case _ => ()

    case t: Term.Apply =>
      extractTermRefs(t.fun, scope)
      t.args.foreach(extractTermRefs(_, scope))

    case t: Term.ApplyType =>
      extractTermRefs(t.fun, scope)
      t.targs.foreach(extractTypeRefs(_, scope))

    case t: Term.ApplyInfix =>
      extractTermRefs(t.lhs, scope)
      extractTermRefs(t.op, scope)
      t.targs.foreach(extractTypeRefs(_, scope))
      t.args.foreach(extractTermRefs(_, scope))

    case t: Term.ApplyUnary =>
      extractTermRefs(t.arg, scope)

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
          e.pat.collect { case Pat.Var(name) => scope.defineTerm(name.value, nextLocalSym()) }
          extractTermRefs(e.rhs, scope)
        case e: Enumerator.Val =>
          e.pat.collect { case Pat.Var(name) => scope.defineTerm(name.value, nextLocalSym()) }
          extractTermRefs(e.rhs, scope)
        case _: Enumerator.Guard => ()
        case _ => ()
      }
      extractTermRefs(t.body, scope)

    case t: Term.ForYield =>
      t.enums.foreach {
        case e: Enumerator.Generator =>
          e.pat.collect { case Pat.Var(name) => scope.defineTerm(name.value, nextLocalSym()) }
          extractTermRefs(e.rhs, scope)
        case e: Enumerator.Val =>
          e.pat.collect { case Pat.Var(name) => scope.defineTerm(name.value, nextLocalSym()) }
          extractTermRefs(e.rhs, scope)
        case _: Enumerator.Guard => ()
        case _ => ()
      }
      extractTermRefs(t.body, scope)

    case t: Term.Match =>
      extractTermRefs(t.expr, scope)
      t.cases.foreach { c =>
        val caseScope = scope.child()
        c.pat.collect { case Pat.Var(name) => caseScope.defineTerm(name.value, nextLocalSym()) }
        extractTermRefs(c.body, caseScope)
        c.cond.foreach(extractTermRefs(_, caseScope))
      }

    case t: Term.New =>
      extractRefsFromInits(List(t.init), scope)

    case t: Term.NewAnonymous =>
      extractRefsFromInits(t.templ.inits, scope)
      val bodyScope = scope.child()
      t.templ.stats.foreach(extractRefsFromStat(_, bodyScope))

    case t: Term.Function =>
      val fnScope = scope.child()
      t.params.foreach { param =>
        param.name match
          case meta.Name(value) => fnScope.defineTerm(value, nextLocalSym())
          case _ => ()
        param.decltpe.foreach(extractTypeRefs(_, fnScope))
      }
      extractTermRefs(t.body, fnScope)

    case t: Term.PartialFunction =>
      t.cases.foreach { c =>
        val caseScope = scope.child()
        c.pat.collect { case Pat.Var(name) => caseScope.defineTerm(name.value, nextLocalSym()) }
        extractTermRefs(c.body, caseScope)
        c.cond.foreach(extractTermRefs(_, caseScope))
      }

    case t: Term.Return =>
      extractTermRefs(t.expr, scope)

    case t: Term.Throw =>
      extractTermRefs(t.expr, scope)

    case t: Term.Try =>
      extractTermRefs(t.expr, scope)
      t.catchp.foreach { catchBlock =>
        val caseScope = scope.child()  // exception binding scope
        catchBlock.pat.collect { case Pat.Var(name) => caseScope.defineTerm(name.value, nextLocalSym()) }
        extractTermRefs(catchBlock.body, caseScope)
        catchBlock.cond.foreach(extractTermRefs(_, caseScope))
      }
      t.finallyp.foreach(extractTermRefs(_, scope))

    case t: Term.Tuple =>
      t.args.foreach(extractTermRefs(_, scope))

    case t: Term.Interpolate =>
      t.args.foreach(extractTermRefs(_, scope))

    case t: Term.Ascribe =>
      extractTermRefs(t.expr, scope)
      extractTypeRefs(t.tpe, scope)

    case t: Term.Annotate =>
      extractTermRefs(t.expr, scope)

    case t: Term.Repeated =>
      extractTermRefs(t.expr, scope)

    case t: Term.Eta =>
      extractTermRefs(t.expr, scope)

    // No references to extract
    case _: Lit | _: Term.This | _: Term.Super | _: Term.Placeholder |
         _: Term.QuotedMacroExpr | _: Term.SplicedMacroExpr | _: Term.Xml =>
      ()

  /** Try to resolve a name in scope and emit a reference occurrence. */
  private def resolveAndEmit(name: String, scope: ScopeTracker, pos: meta.Position): Unit =
    scope.resolve(name).foreach { sym =>
      refs += SourceSymbolReference(sym, toSymbolLocation(pos))
    }
}

object ScalaSourceParser {
  def apply(path: os.Path): ScalaSourceParser =
    new ScalaSourceParser(path, os.read.inputStream(path))

  def apply(str: String, fileName: String = "<inmemory>.scala"): ScalaSourceParser =
    new ScalaSourceParser(os.pwd / fileName, new java.io.ByteArrayInputStream(str.getBytes))
}
