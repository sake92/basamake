package ba.sake.basamake.index.scalasrc

import java.io.InputStream
import scala.compiletime.uninitialized
import scala.meta.*
import com.typesafe.scalalogging.StrictLogging
import scala.util.control.NonFatal
import scala.collection.mutable
import ba.sake.basamake.index.{SymbolTable, SymbolDefinition, SymbolUtils, ResolvedFile, ReferenceOccurrence, ScopeStack, LocalScope, OwnerScope, ImportScopeData}

/** Second pass over a parsed Scala source AST that emits reference occurrences.
  * Operates against an already-populated `SymbolTable` of workspace globals.
  * Together with `ScalaDefinitionsExtractor`, yields a full per-file
  * `SemanticdbFileSlice`-shaped result from `.scala` text alone, no compiler needed.
  *
  * Non-goals (v1): infix operator resolution, package-qualified refs, anonymous
  * given resolution, static overload picking, `this`/`super`/self-parameter.
  */
class ScalaReferencesResolver(symbolTable: SymbolTable) extends StrictLogging {

  /** Entry point from file-system scan: filename + InputStream (parsed as a
    * stream — no intermediate String of the file content). */
  def resolve(name: String, is: InputStream, path: os.Path): ResolvedFile =
    resolveParsed(name, path)(ScalaParseUtils.parseSourceStream(name, is))

  /** Test-friendly entry point: filename + source string. */
  def resolveFromContent(fileName: String, content: String, path: os.Path): ResolvedFile =
    resolveParsed(fileName, path)(ScalaParseUtils.parseSource(fileName, content))

  private def resolveParsed(fileName: String, path: os.Path)(parse: => Either[String, Source]): ResolvedFile =
    try {
      currentPath = path
      require(fileName.nonEmpty, "fileName must be non-empty")
      parse match {
        case Right(src) =>
          resolveInternal(fileName, src)
        case Left(err) =>
          logger.error(s"Failed to parse Scala source '${path}': ${err}")
          ResolvedFile.empty
      }
    } catch {
      case NonFatal(e) =>
        // One unhandled tree shape must never abort workspace indexing — log
        // (MatchError's message names the tree class) and degrade to empty.
        logger.warn(s"Failed to resolve references in ${path}: ${e.getClass.getSimpleName}: ${e.getMessage}")
        ResolvedFile.empty
    }

  // ── mutable state (cleared per resolve call) ──────────────────

  private val scopeStack = ScopeStack(symbolTable)
  private val occurrences = mutable.ArrayBuffer.empty[ReferenceOccurrence]
  private val locals = mutable.ArrayBuffer.empty[SymbolDefinition]
  private var localIdx: Int = 0
  private var topLevelPkgOwner: String = "_empty_/"
  private var wrapper: Option[String] = None
  private var currentOwner: String = "_empty_/"
  private var currentOwnerIsType: Boolean = false
  private var methodDepth: Int = 0
  private var currentPath: os.Path = uninitialized
  /** name → declared-type candidate symbols (dep types) of a val/var/param —
    * member calls on typed locals fall back to them (`io.flatMap` → IO#flatMap).
    * Cleared per resolve; a shadowed name keeps the OUTER type (rare, and the
    * candidates only fire on a member miss). */
  private val localTypeCandidates = mutable.Map.empty[String, List[String]]
  /** type-param name → context-bound candidate symbols (`[F[_]: Monad]` → F → [cats/Monad#]). */
  private val tparamBounds = mutable.Map.empty[String, List[String]]

  // ── main traversal ───────────────────────────────────────────

  private def resolveInternal(fileName: String, src: Source): ResolvedFile = {
    occurrences.clear()
    locals.clear()
    localIdx = 0
    methodDepth = 0
    localTypeCandidates.clear()
    tparamBounds.clear()
    topLevelPkgOwner = ExtractorShared.extractPackageOwner(src.stats)
    wrapper = ExtractorShared.computeWrapper(fileName, topLevelPkgOwner)
    // start from the empty package like the extractor — nested `package` statements
    // accumulate onto the ENCLOSING owner in resolvePkg, so the initial owner must
    // be neutral (_empty_/), not the top-level package (which would double it)
    currentOwner = "_empty_/"
    currentOwnerIsType = false

    val topLevelOwner = wrapper.getOrElse(topLevelPkgOwner)
    // sbt's implicit imports (`import sbt._` + `import Keys._`, Keys shadowing
    // sbt) — only for `.sbt` files. Pushed FIRST so the file's own top-level
    // owner scope and locals stay above and shadow them, and explicit `import`
    // statements in the file (pushed later on top) shadow them too.
    val sbtImplicitImports =
      if (ScalaFileStyle.fromFileName(fileName) == ScalaFileStyle.Sbt)
        Some(ImportScopeData(explicit = Map.empty, wildcards = List("sbt/Keys.", "sbt/"), unimports = Set.empty))
      else None
    sbtImplicitImports.foreach(scopeStack.push)
    scopeStack.push(OwnerScope(topLevelOwner))
    scopeStack.push(LocalScope(collection.mutable.Map.empty[String, String]))
    try resolveStats(src.stats)
    finally {
      scopeStack.pop()
      scopeStack.pop()
      sbtImplicitImports.foreach(_ => scopeStack.pop())
    }

    ResolvedFile(occurrences.toVector, locals.toVector)
  }

  // ── stat resolution ──────────────────────────────────────────

  private def resolveStats(stats: List[Stat]): Unit = {
    stats.foreach(resolveStat)
  }

  private def resolveStat(stat: Stat): Unit = {
    stat match {
      case p: Pkg =>
        resolvePkg(p)
      case po: Pkg.Object =>
        resolvePkgObject(po)
      case c: Defn.Class =>
        resolveClass(c)
      case t: Defn.Trait =>
        resolveTrait(t)
      case o: Defn.Object =>
        resolveObjectDef(o)
      case e: Defn.Enum =>
        resolveEnum(e)
      case d: Defn.Def =>
        resolveDef(d)
      case dd: Decl.Def =>
        resolveDeclDef(dd)
      case v: Defn.Val =>
        resolveVal(v)
      case dv: Decl.Val =>
        resolveDeclVal(dv)
      case vr: Defn.Var =>
        resolveVar(vr)
      case dvr: Decl.Var =>
        resolveDeclVar(dvr)
      case g: Defn.Given =>
        resolveGiven(g)
      case eg: Defn.ExtensionGroup =>
        resolveExtensionGroup(eg)
      case b: Term.Block =>
        resolveBlock(b)
      case t: Term =>
        resolveTerm(t, inCallContext = false)
      case imp: Import =>
        resolveImport(imp)
      case _ => ()
    }
  }

  // ── emit helpers ─────────────────────────────────────────────
  // NOTE (Task 0): definition occurrences are NOT emitted here. Global defs
  // live in SymbolTable (populated by ScalaDefinitionsExtractor); local defs
  // are recorded via `addLocal` into the `locals` Vector. This pass emits
  // REFERENCE occurrences only. WorkspaceIndex queries look up def sites via
  // SymbolTable / openFilesLocalDefinitions.

  private def emitRef(pos: Position, symbol: String): Unit = {
    val range = PositionUtils.toRange(pos)
    occurrences += ReferenceOccurrence(symbol, range)
  }

  private def emitRefUnresolved(pos: Position): Unit = {
    val range = PositionUtils.toRange(pos)
    occurrences += ReferenceOccurrence("", range)
  }

  /** Emit dep candidates for an unresolved name; empty-symbol ref when none. */
  private def emitCandidatesOrUnresolved(pos: Position, name: String, isType: Boolean): Unit = {
    val cands = scopeStack.lookupCandidates(name, isType)
    if (cands.nonEmpty) cands.foreach(sym => emitRef(pos, sym))
    else emitRefUnresolved(pos)
  }

  private def addLocal(pos: Position, symbol: String, shortName: String, isType: Boolean): Unit = {
    val range = PositionUtils.toRange(pos)
    locals += SymbolDefinition(symbol, shortName, isType, range, currentPath)
  }

  private def nextLocalSymbol(): String = {
    val sym = SymbolUtils.localSymbol(localIdx)
    localIdx += 1
    sym
  }

  // ── effective owner (wrapper-aware) ──────────────────────────

  private def effectiveOwner: String =
    ExtractorShared.ifWrapperOwner(currentOwner, wrapper, topLevelPkgOwner)

  private def isInsideMethod: Boolean = methodDepth > 0

  // ── lookup convenience ───────────────────────────────────────

  private def lookup(name: String, isType: Boolean, inCallContext: Boolean): Option[String] = {
    scopeStack.lookup(name, isType, inCallContext)
      .orElse(PredefSymbols.lookup(name, isType))
      .orElse {
        // Last resort: _empty_/ package
        if (isType) {
          val sym = SymbolUtils.typeSymbol("_empty_/", name)
          if (symbolTable.get(sym).isDefined) Some(sym) else None
        } else {
          val sym = SymbolUtils.termSymbol("_empty_/", name)
          if (symbolTable.get(sym).isDefined) Some(sym) else None
        }
      }
      .orElse(wrapperScan(topLevelPkgOwner, name, isType))
  }

  /** Cross-file top-level wrapper divergence: another file's top-level def lives
    * under `pkg/Other$package.<name>...`, not under this file's wrapper.
    * When the scope walk misses, look up the symbols of THIS package with that
    * short name (O(1) index — never a full-table scan) and regex-filter them.
    * Only runs on miss — cheap.
    */
  private def wrapperScan(pkgOwner: String, name: String, isType: Boolean): Option[String] = {
    val escName = java.util.regex.Pattern.quote(SymbolUtils.escapedName(name))
    val pkg     = java.util.regex.Pattern.quote(pkgOwner)
    // Regex: <pkg>/<anything>$package.<escName>(...).   (methods)
    //     or: <pkg>/<anything>$package.<escName>.         (vals/objects)
    //     or: <pkg>/<anything>$package.<escName>#          (types)
    val methodPat = s"^${pkg}.*\\$$package\\.$escName\\(.*\\)\\.$$".r.pattern
    val termPat   = s"^${pkg}.*\\$$package\\.$escName\\.$$".r.pattern
    val typePat   = s"^${pkg}.*\\$$package\\.$escName#$$".r.pattern
    symbolTable.symbolsIn(pkgOwner, name).find { k =>
      if isType then typePat.matcher(k).matches()
      else methodPat.matcher(k).matches() || termPat.matcher(k).matches()
    }
  }

  // ── package ──────────────────────────────────────────────────

  private def resolvePkg(p: Pkg): Unit = {
    val segs = p.ref.toString.split('.').toList
    // accumulate onto the enclosing package owner — nested package statements
    // (`package scala` + `package collection`) must NOT drop the outer segments
    val pkgOwner = mkPackageOwner(currentOwner, segs)

    val oldOwner = currentOwner
    val oldIsType = currentOwnerIsType
    currentOwner = pkgOwner
    currentOwnerIsType = false

    val isFilesTopPackage = pkgOwner == topLevelPkgOwner
    val pushedWrapper = isFilesTopPackage && wrapper.isDefined
    scopeStack.push(OwnerScope(pkgOwner))
    if (pushedWrapper) scopeStack.push(OwnerScope(wrapper.get))
    try resolveStats(p.body)
    finally {
      if (pushedWrapper) scopeStack.pop()
      scopeStack.pop()
    }

    currentOwner = oldOwner
    currentOwnerIsType = oldIsType
  }

  // ── package object ───────────────────────────────────────────

  private def resolvePkgObject(po: Pkg.Object): Unit = {
    val pkgOwner = mkPackageOwnerForPkgObj(currentOwner, po.name.value)
    val pkgObjOwner = SymbolUtils.termSymbol(pkgOwner, "package")

    val oldOwner = currentOwner
    val oldIsType = currentOwnerIsType
    currentOwner = pkgObjOwner
    currentOwnerIsType = false

    scopeStack.push(OwnerScope(pkgObjOwner))
    resolveTypeTpeOpt(po.templ.inits)
    resolveStats(po.templ.stats)
    scopeStack.pop()

    currentOwner = oldOwner
    currentOwnerIsType = oldIsType
  }

  // ── class ────────────────────────────────────────────────────

  private def resolveClass(c: Defn.Class): Unit = {
    val effOwner = currentOwner
    val classSym = SymbolUtils.typeSymbol(effOwner, c.name.value)

    if (isInsideMethod) {
      // Local class → emit as local<N>
      val localSym = nextLocalSymbol()
      addLocal(c.name.pos, localSym, c.name.value, isType = true)

      // Also emit extractor-style key for parity (pragmatic compromise)
      val globalSym = classSym
      // emit type params for the global key too
      c.tparams.foreach { tp =>
        val tpSym = SymbolUtils.typeParamSymbol(globalSym, tp.name.value)
        val localTpSym = nextLocalSymbol()
        addLocal(tp.name.pos, localTpSym, tp.name.value, isType = false)
      }

      c.ctor.paramss.flatten.foreach(p => p.decltpe.foreach(resolveType))

      // Push owner scope and local scope
      val oldOwner = currentOwner
      val oldIsType = currentOwnerIsType
      currentOwner = globalSym
      currentOwnerIsType = true

      scopeStack.push(OwnerScope(globalSym))
      scopeStack.push(LocalScope(collection.mutable.Map(c.name.value -> localSym)))
      // Bind type params in local scope
      c.tparams.zipWithIndex.foreach { (tp, i) =>
        val tpLocal = locals(locals.length - c.tparams.length + i).symbol
        scopeStack.push(LocalScope(collection.mutable.Map(tp.name.value -> tpLocal)))
      }
      resolveTypeTpeOpt(c.templ.inits)
      resolveStats(c.templ.stats)
      // Pop type-param scopes + local scope + owner scope
      (0 until c.tparams.length + 2).foreach(_ => scopeStack.pop())

      currentOwner = oldOwner
      currentOwnerIsType = oldIsType
    } else {
      // Global class
      c.ctor.paramss.flatten.foreach(p => p.decltpe.foreach(resolveType))

      val oldOwner = currentOwner
      val oldIsType = currentOwnerIsType
      currentOwner = classSym
      currentOwnerIsType = true

      // Emit type param locals and resolve context/view bounds (cbounds).
      // Each resolveTparam pushes a LocalScope; pop them after the body.
      c.tparams.foreach(tp => resolveTparam(tp))
      val tparamScopeCount = c.tparams.count(_.name.value.nonEmpty)

      scopeStack.push(OwnerScope(classSym))
      scopeStack.push(LocalScope(collection.mutable.Map(c.name.value -> classSym)))
      resolveTypeTpeOpt(c.templ.inits)
      resolveStats(c.templ.stats)
      scopeStack.pop()
      scopeStack.pop()

      // Pop type param scopes pushed by resolveTparam
      (0 until tparamScopeCount).foreach(_ => scopeStack.pop())

      currentOwner = oldOwner
      currentOwnerIsType = oldIsType
    }
  }

  // ── trait ────────────────────────────────────────────────────

  private def resolveTrait(t: Defn.Trait): Unit = {
    val effOwner = currentOwner
    val traitSym = SymbolUtils.typeSymbol(effOwner, t.name.value)

    val oldOwner = currentOwner
    val oldIsType = currentOwnerIsType
    currentOwner = traitSym
    currentOwnerIsType = true
    // NOTE: local traits inside methods are not yet emitted as local<N> — v1 limitation

    // Emit type param locals and resolve context/view bounds (cbounds).
    t.tparams.foreach(tp => resolveTparam(tp))
    val tparamScopeCount = t.tparams.count(_.name.value.nonEmpty)

    scopeStack.push(OwnerScope(traitSym))
    scopeStack.push(LocalScope(collection.mutable.Map(t.name.value -> traitSym)))
    resolveTypeTpeOpt(t.templ.inits)
    resolveStats(t.templ.stats)
    scopeStack.pop()
    scopeStack.pop()

    // Pop type param scopes pushed by resolveTparam
    (0 until tparamScopeCount).foreach(_ => scopeStack.pop())

    currentOwner = oldOwner
    currentOwnerIsType = oldIsType
  }

  // ── object ───────────────────────────────────────────────────

  private def resolveObjectDef(o: Defn.Object): Unit = {
    val effOwner = currentOwner
    val objSym = SymbolUtils.termSymbol(effOwner, o.name.value)

    if (isInsideMethod) {
      val localSym = nextLocalSymbol()
      addLocal(o.name.pos, localSym, o.name.value, isType = false)

      val oldOwner = currentOwner
      val oldIsType = currentOwnerIsType
      currentOwner = objSym
      currentOwnerIsType = false

      scopeStack.push(OwnerScope(objSym))
      scopeStack.push(LocalScope(collection.mutable.Map(o.name.value -> localSym)))
      resolveTypeTpeOpt(o.templ.inits)
      resolveStats(o.templ.stats)
      scopeStack.pop()
      scopeStack.pop()

      currentOwner = oldOwner
      currentOwnerIsType = oldIsType
    } else {

      val oldOwner = currentOwner
      val oldIsType = currentOwnerIsType
      currentOwner = objSym
      currentOwnerIsType = false

      scopeStack.push(OwnerScope(objSym))
      scopeStack.push(LocalScope(collection.mutable.Map(o.name.value -> objSym)))
      resolveTypeTpeOpt(o.templ.inits)
      resolveStats(o.templ.stats)
      scopeStack.pop()
      scopeStack.pop()

      currentOwner = oldOwner
      currentOwnerIsType = oldIsType
    }
  }

  // ── enum ─────────────────────────────────────────────────────

  private def resolveEnum(e: Defn.Enum): Unit = {
    val effOwner = currentOwner
    val typeSym = SymbolUtils.typeSymbol(effOwner, e.name.value)
    val termSym = SymbolUtils.termSymbol(effOwner, e.name.value)

    val oldOwner = currentOwner
    val oldIsType = currentOwnerIsType
    currentOwner = termSym
    currentOwnerIsType = false
    // NOTE: local enums inside methods are not yet emitted as local<N> — v1 limitation

    scopeStack.push(OwnerScope(termSym))
    scopeStack.push(LocalScope(collection.mutable.Map(e.name.value -> termSym)))
    resolveTypeTpeOpt(e.templ.inits)
    resolveStats(e.templ.stats)
    scopeStack.pop()
    scopeStack.pop()

    currentOwner = oldOwner
    currentOwnerIsType = oldIsType
  }

  // ── def ──────────────────────────────────────────────────────

  private def resolveDef(d: Defn.Def): Unit = {
    val effOwner = effectiveOwner
    val methodSym = SymbolUtils.methodSymbol(effOwner, d.name.value, 0) // overload index 0 — v1 heuristic

    if (isInsideMethod) {
      // Local def → record as local<N>
      val localSym = nextLocalSymbol()
      addLocal(d.name.pos, localSym, d.name.value, isType = false)
    }

    // Emit type param locals and resolve their context/view bounds.
    // Each resolveTparam pushes a LocalScope; pop them after the body.
    d.tparams.foreach(tp => resolveTparam(tp))
    val tparamScopeCount = d.tparams.count(_.name.value.nonEmpty)

    // Resolve param type annotations
    d.paramss.flatten.foreach(p => p.decltpe.foreach(resolveType))
    d.paramss.flatten.foreach(p => recordTypeCandidates(p.name.value, p.decltpe))
    d.decltpe.foreach(resolveType)

    // Bind params in local scope for body resolution
    val paramBindings = d.paramss.flatten.map { p => p.name.value -> SymbolUtils.parameterSymbol(methodSym, p.name.value) }
    scopeStack.push(LocalScope(collection.mutable.Map.from(paramBindings)))
    methodDepth += 1
    resolveTerm(d.body, inCallContext = false)
    methodDepth -= 1
    scopeStack.pop()

    // Pop type param scopes pushed by resolveTparam
    (0 until tparamScopeCount).foreach(_ => scopeStack.pop())
  }

  private def resolveDeclDef(dd: Decl.Def): Unit = {
    val effOwner = effectiveOwner
    val methodSym = SymbolUtils.methodSymbol(effOwner, dd.name.value, 0)
    // Emit type param locals and resolve context/view bounds.
    dd.tparams.foreach(tp => resolveTparam(tp))
    val tparamScopeCount = dd.tparams.count(_.name.value.nonEmpty)
    dd.paramss.flatten.foreach(p => p.decltpe.foreach(resolveType))
    dd.paramss.flatten.foreach(p => recordTypeCandidates(p.name.value, p.decltpe))
    resolveType(dd.decltpe)
    // Pop type param scopes pushed by resolveTparam
    (0 until tparamScopeCount).foreach(_ => scopeStack.pop())
  }

  // ── val ──────────────────────────────────────────────────────

  private def resolveVal(v: Defn.Val): Unit = {
    v.decltpe.foreach(resolveType)
    // record the declared type's candidate symbols for member calls on the val
    v.decltpe.foreach(t => v.pats.foreach {
      case pv: Pat.Var => recordTypeCandidates(pv.name.value, v.decltpe)
      case _ => ()
    })
    if (isInsideMethod) {
      v.pats.foreach {
        case pv: Pat.Var =>
          val localSym = nextLocalSymbol()
          addLocal(pv.name.pos, localSym, pv.name.value, isType = false)
          // Add binding to block-level LocalScope (no push/pop)
          scopeStack.addLocalBinding(pv.name.value, localSym)
        case _ => ()
      }
      // Recurse rhs in value context
      resolveTerm(v.rhs, inCallContext = false)
    } else {
      resolveTerm(v.rhs, inCallContext = false)
    }
  }

  private def resolveDeclVal(dv: Decl.Val): Unit = {
    resolveType(dv.decltpe)
  }

  // ── var ──────────────────────────────────────────────────────

  private def resolveVar(vr: Defn.Var): Unit = {
    vr.decltpe.foreach(resolveType)
    vr.decltpe.foreach(t => vr.pats.foreach {
      case pv: Pat.Var => recordTypeCandidates(pv.name.value, vr.decltpe)
      case _ => ()
    })
    if (isInsideMethod) {
      vr.pats.foreach {
        case pv: Pat.Var =>
          val localSym = nextLocalSymbol()
          addLocal(pv.name.pos, localSym, pv.name.value, isType = false)
          scopeStack.addLocalBinding(pv.name.value, localSym)
        case _ => ()
      }
      vr.rhs match {
        case Some(t: Term) => resolveTerm(t, inCallContext = false)
        case _ => ()
      }
    } else {
      vr.rhs match {
        case Some(t: Term) => resolveTerm(t, inCallContext = false)
        case _ => ()
      }
    }
  }

  private def resolveDeclVar(dvr: Decl.Var): Unit = {
    resolveType(dvr.decltpe)
  }

  // ── given ────────────────────────────────────────────────────

  private def resolveGiven(g: Defn.Given): Unit = {
    if (g.name.value.nonEmpty) {
      val effOwner = effectiveOwner
      val sym = SymbolUtils.termSymbol(effOwner, g.name.value)

      val oldOwner = currentOwner
      val oldIsType = currentOwnerIsType
      currentOwner = sym
      currentOwnerIsType = false
      scopeStack.push(OwnerScope(sym))
      resolveTypeTpeOpt(g.templ.inits)
      resolveStats(g.templ.stats)
      scopeStack.pop()
      currentOwner = oldOwner
      currentOwnerIsType = oldIsType
    }
  }

  // ── extension group ──────────────────────────────────────────

  private def resolveExtensionGroup(eg: Defn.ExtensionGroup): Unit = {
    // Extension params are bound as locals for the method bodies
    methodDepth += 1
    eg.body match {
      case b: Term.Block =>
        b.stats.foreach {
          case d: Defn.Def =>
            // Push extension params into scope
            scopeStack.push(LocalScope(collection.mutable.Map.empty[String, String]))
            methodDepth += 1
            resolveTerm(d.body, inCallContext = false)
            methodDepth -= 1
            scopeStack.pop()
          case s => resolveStat(s)
        }
      case d: Defn.Def =>
        scopeStack.push(LocalScope(collection.mutable.Map.empty[String, String]))
        resolveTerm(d.body, inCallContext = false)
        scopeStack.pop()
      case s: Stat => resolveStat(s)
    }
    methodDepth -= 1
  }

  // ── import ───────────────────────────────────────────────────

  private def resolveImport(imp: Import): Unit = {
    val emitRefFn: (Tree, String) => Unit = (tree, sym) => {
      val resolved = if (sym.isEmpty) {
        // Try to resolve the tree as a term name
        tree match {
          case t: Term.Name =>
            lookup(t.value, isType = false, inCallContext = false).getOrElse("")
          case _ => ""
        }
      } else sym
      if (resolved.nonEmpty) emitRef(tree.pos, resolved)
      else emitRefUnresolved(tree.pos)
    }

    val importScopes = ImportScope.parse(imp, scopeStack, emitRefFn)
    importScopes.foreach(scopeStack.push)
    // Note: import scopes are NOT popped — they remain in scope for the enclosing block
  }

  // ── term resolution ──────────────────────────────────────────

  private def resolveTerm(tree: Term, inCallContext: Boolean): Unit = {
    tree match {
      case t @ Term.Name(n) =>
        lookup(n, isType = false, inCallContext = inCallContext) match {
          case Some(sym) => emitRef(t.pos, sym)
          case None      => emitCandidatesOrUnresolved(t.pos, n, isType = false)
        }

      case Term.Select(qual, name) =>
        resolveTermSelect(qual, name, inCallContext)

      case Term.Apply(fun, args) =>
        resolveTermApply(fun, args)

      case Term.ApplyType(fun, targs) =>
        resolveTerm(fun, inCallContext = true)
        targs.foreach(resolveType)

      case Term.ApplyInfix(lhs, op, targs, args) =>
        // Explicitly SKIP the infix `op` operand in v1
        resolveTerm(lhs, inCallContext = false)
        args.foreach(a => resolveTerm(a, inCallContext = false))

      case Term.New(init) =>
        resolveInit(init, isNew = true)

      case Term.NewAnonymous(_) =>
        // Skip anonymous class
        ()

      case block: Term.Block =>
        resolveBlock(block)

      case param: Term.Param =>
        // Lambda param — resolve type annotation and default
        param.decltpe.foreach(resolveType)
        recordTypeCandidates(param.name.value, param.decltpe)
        param.default.foreach(d => resolveTerm(d, inCallContext = false))

      case Term.ApplyUsing(fun, args) =>
        resolveTerm(fun, inCallContext = true)
        args.foreach(a => resolveTerm(a, inCallContext = false))

      case _ => ()
    }
  }

  // ── term select ──────────────────────────────────────────────

  private def resolveTermSelect(qual: Term, name: Term.Name, inCallContext: Boolean): Unit = {
    // Resolve qual to determine the owner key
    resolveTerm(qual, inCallContext = false)
    val n = name.value

    // Try to resolve as member of resolved qual owner
    val ownerOpt = resolveTermToOwner(qual)
    ownerOpt match {
      case Some(owner) =>
        resolveMemberOf(owner, n, isType = false, inCallContext) match {
          case Some(sym) => emitRef(name.pos, sym)
          case None =>
            // member miss: package-path fallback owners (unresolved qual treated
            // as a package path) and typed locals/params fall through to
            // candidate owners (`io.flatMap` → IO#flatMap via io's decl type).
            // Candidates are emitted UNVERIFIED — the resolver's table cannot
            // see deps; request-time verification + prefix scan resolve the
            // true keys (`EitherT.leftT` → `cats/data/EitherT.leftT().`).
            val candOwners =
              if (owner.endsWith("/")) resolveTermToOwnerCandidates(qual)
              else typedLocalOwnerCandidates(qual)
            emitMemberCandidates(name, n, candOwners, inCallContext)
        }
      case None =>
        // qual is (plausibly) a dep symbol — emit index-0 member candidates
        // per qual candidate; request-time prefix scan resolves the true
        // overload keys (`IO.pure` → `cats/effect/IO.pure(+N).`).
        emitMemberCandidates(name, n, resolveTermToOwnerCandidates(qual), inCallContext)
    }
  }

  /** Emit index-0 member candidates for `n` under each candidate owner
    * (plus the term shape for call positions). */
  private def emitMemberCandidates(name: Term.Name, n: String, owners: List[String], inCallContext: Boolean): Unit = {
    val cands = owners.flatMap { owner =>
      val sym = if (inCallContext) SymbolUtils.methodSymbol(owner, n, 0)
                else SymbolUtils.termSymbol(owner, n)
      if (sym.endsWith("().") || !inCallContext) List(sym) else List(sym, SymbolUtils.termSymbol(owner, n))
    }.distinct
    if (cands.nonEmpty) cands.foreach(sym => emitRef(name.pos, sym))
    else emitRefUnresolved(name.pos)
  }

  // ── term apply ───────────────────────────────────────────────

  private def resolveTermApply(fun: Term, args: List[Term]): Unit = {
    // Resolve fun in call context
    resolveTerm(fun, inCallContext = true)

    // Resolve the called method symbol ONCE so named args can resolve to params
    val methodSymOpt: Option[String] = resolveMethodSymbolForApply(fun)

    // Synthetic apply rule
    fun match {
      case Term.Name(n) =>
        val funSym = lookup(n, isType = false, inCallContext = true)
        funSym.foreach { sym =>
          if (sym.endsWith(".") && isObjectSymbol(sym)) {
            // Use sym directly as owner (e.g. "pkg/Foo." → owner for apply is "pkg/Foo.")
            val applySym = SymbolUtils.methodSymbol(sym, "apply", 0)
            if (symbolTable.get(applySym).isDefined) {
              emitRef(fun.pos, applySym) // synthetic apply at same range
            }
          }
        }

      case Term.Select(qual, name) =>
        val ownerOpt = resolveTermToOwner(qual)
        ownerOpt.foreach { owner =>
          val n = name.value
          // Check if qual resolves to an object with apply
          if (isObjectOfOwner(owner, n)) {
            val applySym = SymbolUtils.methodSymbol(SymbolUtils.termSymbol(owner, n), "apply", 0)
            if (symbolTable.get(applySym).isDefined) {
              emitRef(fun.pos, applySym) // synthetic apply at same range
            }
          }
        }

      case _ => ()
    }

    // Recurse args in value context; named args (`a = expr`) also emit a ref
    // to the method's parameter symbol when the method was resolved.
    args.foreach {
      case Term.Assign(name: Term.Name, value) =>
        methodSymOpt.foreach { ms =>
          val paramSym = SymbolUtils.parameterSymbol(ms, name.value)
          if (symbolTable.get(paramSym).isDefined)
            emitRef(name.pos, paramSym)
        }
        resolveTerm(value, inCallContext = false)
      case other =>
        resolveTerm(other, inCallContext = false)
    }
  }

  // ── block ────────────────────────────────────────────────────

  private def resolveBlock(block: Term.Block): Unit = {
    scopeStack.push(LocalScope(collection.mutable.Map.empty[String, String]))
    resolveStats(block.stats)
    scopeStack.pop()
  }

  // ── type resolution ──────────────────────────────────────────

  /** Resolve a `Type.Param` (type parameter definition): emit a local for the
    * name, push a LocalScope binding `name -> local<N>`, and resolve context
    * bounds so gotodef on a bound type (e.g. `cats.Monad`) works.
    * The caller is responsible for popping the scope afterwards
    * (one pop per non-wildcard type param). Returns true iff a scope was pushed. */
  private def resolveTparam(tp: Type.Param): Boolean = {
    val pn = tp.name.value
    val scopePushed = if (pn.nonEmpty) {
      val localSym = nextLocalSymbol()
      addLocal(tp.name.pos, localSym, pn, isType = false)
      scopeStack.push(LocalScope(collection.mutable.Map(pn -> localSym)))
      true
    } else false
    // Resolve context bounds (e.g. `[F: cats.Monad]` → emits ref to cats/Monad#)
    tp.cbounds.foreach(resolveType)
    // record the bound's candidate TYPE symbols so `fa: F[Int]` member calls
    // can reach the bound's members (`fa.map` → cats/Monad#map)
    val boundCands = tp.cbounds.flatMap(b => typeCandidatesOf(Some(b))).distinct
    if (boundCands.nonEmpty) tparamBounds(tp.name.value) = boundCands
    scopePushed
  }

  private def resolveType(tpe: Type): Unit = {
    tpe match {
      case t @ Type.Name(n) =>
        lookup(n, isType = true, inCallContext = false) match {
          case Some(sym) => emitRef(t.pos, sym)
          case None      => emitCandidatesOrUnresolved(t.pos, n, isType = true)
        }

      case Type.Select(qual, name) =>
        // qual is Term.Ref — resolve it to an owner prefix
        resolveImportPrefixRef(qual)
        val ownerOpt = resolveImportRefToOwner(qual)
        val n = name.value
        ownerOpt match {
          case Some(owner) =>
            val typeSym = SymbolUtils.typeSymbol(owner, n)
            if (symbolTable.get(typeSym).isDefined) {
              emitRef(name.pos, typeSym)
            } else if (owner.endsWith("/")) {
              // package-prefixed dep type (`extends cats.effect.IO`) — emit
              // both plausible shapes; request-time verification picks the
              // one the dep jar defines
              List(typeSym, SymbolUtils.termSymbol(owner, n)).foreach(sym => emitRef(name.pos, sym))
            } else {
              emitRefUnresolved(name.pos)
            }
          case None =>
            emitRefUnresolved(name.pos)
        }

      case Type.Apply(t, args) =>
        resolveType(t)
        args.foreach(resolveType)

      case Type.ByName(t) =>
        resolveType(t)

      case Type.Repeated(t) =>
        resolveType(t)

      case _ => ()
    }
  }

  // ── type tpe opts (extends / with clauses) ───────────────────

  private def resolveTypeTpeOpt(inits: List[Init]): Unit = {
    inits.foreach { init =>
      resolveType(init.tpe)
      // Recurse args as values (argss: List[List[Term]])
      init.argss.foreach(_.foreach(a => resolveTerm(a, inCallContext = false)))
    }
  }

  // ── init (constructor calls in new / extends) ─────────────────

  private def resolveInit(init: Init, isNew: Boolean): Unit = {
    // Emit type ref for the class
    resolveType(init.tpe)

    // For `new C(...)`, emit constructor ref — resolve via scope
    if (isNew) {
      init.tpe match {
        case Type.Name(n) =>
          lookup(n, isType = true, inCallContext = false) match {
            case Some(typeSym) if typeSym.endsWith("#") =>
              val ctorSym = SymbolUtils.constructorSymbol(typeSym, 0)
              emitRef(init.tpe.pos, ctorSym)
            case _ => ()
          }
        case _ => ()
      }
    }

    // Recurse args as values (argss: List[List[Term]])
    init.argss.foreach(_.foreach(a => resolveTerm(a, inCallContext = false)))
  }

  // ── helpers ──────────────────────────────────────────────────

  /** Package owner for a `Pkg` statement nested inside `baseOwner`: enclosing
    * package segments + the statement's own segments. Mirrors the extractor's
    * `mkPackageOwner` exactly (same keys on both sides is mandatory). */
  private def mkPackageOwner(baseOwner: String, segments: List[String]): String = {
    val base = if (baseOwner == "_empty_/" || baseOwner.isEmpty) Nil
               else baseOwner.stripSuffix("/").split('/').toList.filter(_.nonEmpty)
    SymbolUtils.packageOwner(base ++ segments)
  }

  private def mkPackageOwnerForPkgObj(baseOwner: String, pkgObjName: String): String =
    mkPackageOwner(baseOwner, List(pkgObjName))

  /** Resolve the called method symbol of an apply `fun`, for named-arg param lookup.
    * Mirrors the lookup path used by `resolveTerm`/`resolveTermSelect` but only returns
    * a method symbol (ending with `(...).`). Returns None if `fun` is not a method call
    * or the symbol is not in the SymbolTable.
    */
  private def resolveMethodSymbolForApply(fun: Term): Option[String] = fun match {
    case Term.Select(qual, name) =>
      resolveTermToOwner(qual).flatMap { owner =>
        resolveMemberOf(owner, name.value, isType = false, inCallContext = true)
      }
    case Term.Name(n) =>
      lookup(n, isType = false, inCallContext = true)
    case _ => None
  }

  // ── term resolution helpers ───────────────────────────────────

  /** Resolve a term to its owner prefix (SemanticDB owner key), used for member lookups. */
  private def resolveTermToOwner(term: Term): Option[String] = {
    term match {
      case Term.Name(n) =>
        scopeStack.lookup(n, isType = false, inCallContext = false)
          .orElse(PredefSymbols.rawLookup(n))
          .orElse {
            // Direct SymbolTable probe: try _empty_/ package + term symbol
            val candidates = List(
              SymbolUtils.termSymbol("_empty_/", n),
              SymbolUtils.packageOwner(List(n))
            )
            candidates.find(st => symbolTable.get(st).isDefined)
          }
          .orElse(wrapperScan(topLevelPkgOwner, n, isType = false))
          .orElse(Some(SymbolUtils.packageOwner(List(n)))) // fallback: treat as package path

      case Term.Select(qual, name) =>
        val qualOwner = resolveTermToOwner(qual)
        val n = name.value
        qualOwner.map { owner =>
          SymbolUtils.termSymbol(owner, n)
        }

      case Term.New(init) =>
        // `new C().member` — owner is the TYPE symbol of C, so members resolve to C#member
        init.tpe match {
          case Type.Name(n) =>
            lookup(n, isType = true, inCallContext = false)
          case Type.Select(qual, name) =>
            resolveImportRefToOwner(qual).map(owner => SymbolUtils.typeSymbol(owner, name.value))
          case _ => None
        }

      case _ => None
    }
  }

  /** Plausible OWNER symbols for an unresolved qualifier (dep chains). */
  private def resolveTermToOwnerCandidates(term: Term): List[String] = term match {
    case Term.Name(n) =>
      scopeStack.lookupCandidates(n, isType = false)
    case Term.Select(qual, name) =>
      resolveTermToOwnerCandidates(qual).flatMap { owner =>
        List(SymbolUtils.termSymbol(owner, name.value),
             if (owner.endsWith("/")) SymbolUtils.packageOwner((owner.stripSuffix("/").split('/').toList :+ name.value)) else SymbolUtils.termSymbol(owner, name.value))
      }.distinct
    case _ => Nil
  }

  /** Declared-type candidate OWNERS for a typed local/param name
    * (`io` → `cats/effect/IO#` via `val io: IO[Unit]`). */
  private def typedLocalOwnerCandidates(qual: Term): List[String] = qual match {
    case Term.Name(n) => localTypeCandidates.getOrElse(n, Nil)
    case _            => Nil
  }

  /** Record a name's declared-type candidates (dep types only — workspace
    * locals resolve through the normal owner path). */
  private def recordTypeCandidates(name: String, decltpe: Option[Type]): Unit = {
    val cands = typeCandidatesOf(decltpe)
    if (cands.nonEmpty) localTypeCandidates(name) = cands
  }

  /** Plausible TYPE symbols for a type tree — import candidates plus tparam
    * context-bound candidates (e.g. `Monad` under `[F[_]: Monad]`). */
  private def typeCandidatesOf(tpe: Option[Type]): List[String] = tpe match {
    case None => Nil
    case Some(t) =>
      t match {
        case Type.Name(n) =>
          scopeStack.lookupCandidates(n, isType = true) ++ tparamBounds.getOrElse(n, Nil)
        case Type.Select(_, name) =>
          scopeStack.lookupCandidates(name.value, isType = true) ++ tparamBounds.getOrElse(name.value, Nil)
        case Type.Apply(head, _) => typeCandidatesOf(Some(head))
        case _                   => Nil
      }
  }

  /** Resolve a Term.Ref to an owner prefix for type select resolution.
    * Unresolvable first segments are treated as package paths (they accumulate
    * with trailing `/`), never as raw names — a raw name produced malformed
    * keys like `cats/effect.IO#` for `extends cats.effect.IO`. */
  private def resolveImportRefToOwner(ref: Term.Ref): Option[String] = ref match {
    case Term.Name(n) =>
      scopeStack.lookup(n, isType = false, inCallContext = false)
        .orElse(PredefSymbols.rawLookup(n))
        .orElse(Some(SymbolUtils.packageOwner(List(n))))

    case Term.Select(qual: Term.Ref, name) =>
      resolveImportRefToOwner(qual).map { owner =>
        if (owner.endsWith("/"))
          SymbolUtils.packageOwner(owner.stripSuffix("/").split('/').toList :+ name.value)
        else SymbolUtils.termSymbol(owner, name.value)
      }

    case _ => None
  }

  /** Emit refs for a Term.Ref prefix (used in Type.Select qual resolution). */
  private def resolveImportPrefixRef(ref: Term.Ref): Unit = {
    ref match {
      case Term.Name(n) =>
        lookup(n, isType = false, inCallContext = false) match {
          case Some(sym) => emitRef(ref.pos, sym)
          case None => emitRefUnresolved(ref.pos)
        }
      case Term.Select(qual: Term.Ref, name) =>
        resolveImportPrefixRef(qual)
        val n = name.value
        val ownerOpt = resolveImportRefToOwner(qual)
        ownerOpt match {
          case Some(owner) =>
            val memberSym = SymbolUtils.termSymbol(owner, n)
            if (symbolTable.get(memberSym).isDefined) {
              emitRef(name.pos, memberSym)
            } else {
              emitRefUnresolved(name.pos)
            }
          case None =>
            emitRefUnresolved(name.pos)
        }
      case _ => ()
    }
  }

  /** Resolve a member name against an owner. */
  private def resolveMemberOf(owner: String, name: String, isType: Boolean, inCallContext: Boolean): Option[String] = {
    if (isType) {
      val sym = SymbolUtils.typeSymbol(owner, name)
      if (symbolTable.get(sym).isDefined) Some(sym) else None
    } else {
      if (inCallContext) {
        val methodSym = ScopeStack.findMethodOverload(owner, name, symbolTable)
        if (methodSym.isDefined) return methodSym
      }
      val termSym = SymbolUtils.termSymbol(owner, name)
      if (symbolTable.get(termSym).isDefined) Some(termSym) else None
    }
  }

  /** Check if `sym` is an object symbol (ends with `.` or `.)` but not `#`). */
  private def isObjectSymbol(sym: String): Boolean = {
    !sym.endsWith("#") && !sym.endsWith("/")
  }

  /** Check if `owner` has a term member `name` that is an object. */
  private def isObjectOfOwner(owner: String, name: String): Boolean = {
    val termSym = SymbolUtils.termSymbol(owner, name)
    symbolTable.get(termSym).exists(!_.isType)
  }
}
