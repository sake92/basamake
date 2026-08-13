package ba.sake.basamake.navigation.scalasrc

import java.io.InputStream
import scala.compiletime.uninitialized
import scala.meta.*
import com.typesafe.scalalogging.StrictLogging
import scala.util.control.NonFatal
import scala.collection.mutable
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, SymbolUtils, ResolvedFile, ReferenceOccurrence, ScopeStack, Scope, LocalScope, OwnerScope, ImportScopeData}

/** Second pass over a parsed Scala source AST that emits reference occurrences.
  * Operates against an already-populated `SymbolTable` of workspace globals.
  * Together with `ScalaDefinitionsExtractor`, yields a full per-file
  * `SemanticdbFileSlice`-shaped result from `.scala` text alone, no compiler needed.
  *
  * Non-goals (v1): infix operator resolution, package-qualified refs, anonymous
  * given resolution, static overload picking, `this`/`super`/self-parameter.
  */
class ScalaReferencesResolver(symbolTable: SymbolTable) extends StrictLogging {

  /** Entry point from file-system scan: filename + InputStream. */
  def resolve(name: String, is: InputStream, path: os.Path): ResolvedFile =
    try {
      val content = new String(is.readAllBytes(), "UTF-8")
      resolveFromContent(name, content, path)
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Failed to resolve references in ${path}: ${e.getMessage}")
        ResolvedFile.empty
    }

  /** Test-friendly entry point: filename + source string. */
  def resolveFromContent(fileName: String, content: String, path: os.Path): ResolvedFile =
    try {
      currentPath = path
      require(fileName.nonEmpty, "fileName must be non-empty")
      parseSource(fileName, content) match {
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

  // ── parse ────────────────────────────────────────────────────

  private def parseSource(fileName: String, content: String): Either[String, Source] =
    ScalaParseUtils.parseSource(fileName, content)

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

  // ── main traversal ───────────────────────────────────────────

  private def resolveInternal(fileName: String, src: Source): ResolvedFile = {
    occurrences.clear()
    locals.clear()
    localIdx = 0
    methodDepth = 0
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
      case ec: Defn.EnumCase =>
        resolveEnumCase(ec)
      case rec: Defn.RepeatedEnumCase =>
        resolveRepeatedEnumCase(rec)
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
      case dt: Defn.Type =>
        resolveTypeAlias(dt)
      case g: Defn.Given =>
        resolveGiven(g)
      case ga: Defn.GivenAlias =>
        resolveGivenAlias(ga)
      case eg: Defn.ExtensionGroup =>
        resolveExtensionGroup(eg)
      case cs: Ctor.Secondary =>
        resolveSecondaryCtor(cs)
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
    * When the scope walk misses, scan the symbol table for any
    * `<pkgOwner>/<file>$package.<name>...` key matching the call site's package.
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
    symbolTable.keys.find { k =>
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

      val ctorSym = SymbolUtils.constructorSymbol(globalSym, 0)
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
      resolveStats(c.templ.stats)
      // Pop type-param scopes + local scope + owner scope
      (0 until c.tparams.length + 2).foreach(_ => scopeStack.pop())

      currentOwner = oldOwner
      currentOwnerIsType = oldIsType
    } else {
      // Global class
      val ctorSym = SymbolUtils.constructorSymbol(classSym, 0)
      c.ctor.paramss.flatten.foreach(p => p.decltpe.foreach(resolveType))

      val oldOwner = currentOwner
      val oldIsType = currentOwnerIsType
      currentOwner = classSym
      currentOwnerIsType = true

      scopeStack.push(OwnerScope(classSym))
      scopeStack.push(LocalScope(collection.mutable.Map(c.name.value -> classSym)))
      resolveTypeTpeOpt(c.templ.inits)
      resolveStats(c.templ.stats)
      scopeStack.pop()
      scopeStack.pop()

      currentOwner = oldOwner
      currentOwnerIsType = oldIsType
    }
  }

  // ── trait ────────────────────────────────────────────────────

  private def resolveTrait(t: Defn.Trait): Unit = {
    val effOwner = currentOwner
    val traitSym = SymbolUtils.typeSymbol(effOwner, t.name.value)
    val ctorSym = SymbolUtils.constructorSymbol(traitSym, 0)

    val oldOwner = currentOwner
    val oldIsType = currentOwnerIsType
    currentOwner = traitSym
    currentOwnerIsType = true
    // NOTE: local traits inside methods are not yet emitted as local<N> — v1 limitation

    scopeStack.push(OwnerScope(traitSym))
    scopeStack.push(LocalScope(collection.mutable.Map(t.name.value -> traitSym)))
    resolveTypeTpeOpt(t.templ.inits)
    resolveStats(t.templ.stats)
    scopeStack.pop()
    scopeStack.pop()

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
    val ctorSym = SymbolUtils.constructorSymbol(typeSym, 0)

    val oldOwner = currentOwner
    val oldIsType = currentOwnerIsType
    currentOwner = termSym
    currentOwnerIsType = false
    // NOTE: local enums inside methods are not yet emitted as local<N> — v1 limitation

    scopeStack.push(OwnerScope(termSym))
    scopeStack.push(LocalScope(collection.mutable.Map(e.name.value -> termSym)))
    resolveStats(e.templ.stats)
    scopeStack.pop()
    scopeStack.pop()

    currentOwner = oldOwner
    currentOwnerIsType = oldIsType
  }

  private def resolveEnumCase(ec: Defn.EnumCase): Unit = {
    val sym = SymbolUtils.termSymbol(currentOwner, ec.name.value)
  }

  private def resolveRepeatedEnumCase(rec: Defn.RepeatedEnumCase): Unit = {
    rec.cases.foreach { c =>
      val sym = SymbolUtils.termSymbol(currentOwner, c.value)
    }
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

    // Resolve param type annotations
    d.paramss.flatten.foreach(p => p.decltpe.foreach(resolveType))
    d.decltpe.foreach(resolveType)

    // Bind params in local scope for body resolution
    val paramBindings = d.paramss.flatten.map { p => p.name.value -> SymbolUtils.parameterSymbol(methodSym, p.name.value) }
    scopeStack.push(LocalScope(collection.mutable.Map.from(paramBindings)))
    methodDepth += 1
    resolveTerm(d.body, inCallContext = false)
    methodDepth -= 1
    scopeStack.pop()
  }

  private def resolveDeclDef(dd: Decl.Def): Unit = {
    val effOwner = effectiveOwner
    val methodSym = SymbolUtils.methodSymbol(effOwner, dd.name.value, 0)
    dd.paramss.flatten.foreach(p => p.decltpe.foreach(resolveType))
    resolveType(dd.decltpe)
  }

  // ── val ──────────────────────────────────────────────────────

  private def resolveVal(v: Defn.Val): Unit = {
    val effOwner = effectiveOwner

    v.decltpe.foreach(resolveType)
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
    val effOwner = effectiveOwner
    resolveType(dv.decltpe)
  }

  // ── var ──────────────────────────────────────────────────────

  private def resolveVar(vr: Defn.Var): Unit = {
    val effOwner = effectiveOwner
    vr.decltpe.foreach(resolveType)
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
    val effOwner = effectiveOwner
    resolveType(dvr.decltpe)
  }

  // ── type alias ───────────────────────────────────────────────

  private def resolveTypeAlias(dt: Defn.Type): Unit = {
    // Type alias is registered in SymbolTable by the extractor
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
      resolveStats(g.templ.stats)
      scopeStack.pop()
      currentOwner = oldOwner
      currentOwnerIsType = oldIsType
    }
  }

  private def resolveGivenAlias(ga: Defn.GivenAlias): Unit = {
    if (ga.name.value.nonEmpty) {
      // Name is registered in SymbolTable by the extractor — nothing to emit here
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

  // ── secondary constructor ────────────────────────────────────

  private def resolveSecondaryCtor(cs: Ctor.Secondary): Unit = {
    // Ctor params are resolved by the extractor; nothing to emit here
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
          case None => emitRefUnresolved(t.pos)
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
        val resolved = resolveMemberOf(owner, n, isType = false, inCallContext)
        if (resolved.nonEmpty) {
          emitRef(name.pos, resolved.get)
        } else {
          emitRefUnresolved(name.pos)
        }
      case None =>
        emitRefUnresolved(name.pos)
    }
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

  private def resolveType(tpe: Type): Unit = {
    tpe match {
      case t @ Type.Name(n) =>
        lookup(n, isType = true, inCallContext = false) match {
          case Some(sym) => emitRef(t.pos, sym)
          case None => emitRefUnresolved(t.pos)
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
            } else {
              emitRefUnresolved(name.pos)
            }
          case None =>
            emitRefUnresolved(name.pos)
        }

      case Type.Apply(t, args) =>
        resolveType(t)
        args.foreach(resolveType)

      case Type.Param(mods, name, tparams, vbounds, cbounds, bounds) =>
        // Type param definition — emit as local<N>
        val pn = name.value
        if (pn.nonEmpty) {
          val localSym = nextLocalSymbol()
          addLocal(name.pos, localSym, pn, isType = false)

          // Bind in local scope
          scopeStack.push(LocalScope(collection.mutable.Map(pn -> localSym)))
        }

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

  /** Resolve a Term.Ref to an owner prefix for type select resolution. */
  private def resolveImportRefToOwner(ref: Term.Ref): Option[String] = {
    ref match {
      case Term.Name(n) =>
        scopeStack.lookup(n, isType = false, inCallContext = false)
          .orElse(PredefSymbols.rawLookup(n))
          .orElse(Some(n))

      case Term.Select(qual: Term.Ref, name) =>
        val qualOwner = resolveImportRefToOwner(qual)
        val n = name.value
        qualOwner.map { owner =>
          SymbolUtils.termSymbol(owner, n)
        }

      case _ => None
    }
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
