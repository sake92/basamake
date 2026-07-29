package ba.sake.basamake.navigation.scalasrc

import java.io.InputStream
import scala.compiletime.uninitialized
import scala.meta.*
import scala.meta.dialects.{Scala3Future, Scala213}
import scala.meta.inputs.Input
import com.typesafe.scalalogging.StrictLogging
import scala.util.control.NonFatal
import scala.collection.mutable
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, SymbolUtils, ResolvedFile, ReferenceOccurrence, PositionUtils}

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
        logger.warn(s"Failed to resolve references in ${name}: ${e.getMessage}")
        ResolvedFile(Vector.empty, Vector.empty)
    }

  /** Test-friendly entry point: filename + source string. */
  def resolveFromContent(fileName: String, content: String, path: os.Path): ResolvedFile = {
    currentPath = path
    require(fileName.nonEmpty, "fileName must be non-empty")
    parseSource(content) match {
      case Some(src) => resolveInternal(fileName, src)
      case None => ResolvedFile(Vector.empty, Vector.empty)
    }
  }

  // ── parse ────────────────────────────────────────────────────

  // TODO inputstream
  private def parseSource(content: String): Option[Source] = {
    val input = Input.String(content)
    { given Dialect = Scala3Future; input.parse[Source] } match {
      case Parsed.Success(source) => Some(source)
      case Parsed.Error(_, _, _) =>
        { given Dialect = Scala213; input.parse[Source] } match
          case Parsed.Success(source) => Some(source)
          case Parsed.Error(_, _, _) => None
    }
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

  // ── main traversal ───────────────────────────────────────────

  private def resolveInternal(fileName: String, src: Source): ResolvedFile = {
    occurrences.clear()
    locals.clear()
    localIdx = 0
    methodDepth = 0
    topLevelPkgOwner = ExtractorShared.extractPackageOwner(src.stats)
    wrapper = ExtractorShared.computeWrapper(fileName, topLevelPkgOwner)
    currentOwner = topLevelPkgOwner
    currentOwnerIsType = false

    resolveStats(src.stats)

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

  private def emitDef(pos: Position, symbol: String): Unit = {
    if (symbol.nonEmpty) {
      val range = PositionUtils.toRange(pos)
      occurrences += ReferenceOccurrence(symbol, range, isDefinition = true)
    }
  }

  private def emitRef(pos: Position, symbol: String): Unit = {
    val range = PositionUtils.toRange(pos)
    occurrences += ReferenceOccurrence(symbol, range, isDefinition = false)
  }

  private def emitRefUnresolved(pos: Position): Unit = {
    val range = PositionUtils.toRange(pos)
    occurrences += ReferenceOccurrence("", range, isDefinition = false)
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
  }

  // ── package ──────────────────────────────────────────────────

  private def resolvePkg(p: Pkg): Unit = {
    val segs = p.ref.toString.split('.').toList
    val pkgOwner = SymbolUtils.packageOwner(segs)
    emitDef(p.ref.pos, pkgOwner)

    val oldOwner = currentOwner
    val oldIsType = currentOwnerIsType
    currentOwner = pkgOwner
    currentOwnerIsType = false

    scopeStack.push(OwnerScope(pkgOwner))
    resolveStats(p.body)
    scopeStack.pop()

    currentOwner = oldOwner
    currentOwnerIsType = oldIsType
  }

  // ── package object ───────────────────────────────────────────

  private def resolvePkgObject(po: Pkg.Object): Unit = {
    val pkgOwner = mkPackageOwnerForPkgObj(currentOwner, po.name.value)
    val pkgObjOwner = SymbolUtils.termSymbol(pkgOwner, "package")
    emitDef(po.name.pos, pkgObjOwner)

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
      emitDef(c.name.pos, localSym)
      addLocal(c.name.pos, localSym, c.name.value, isType = true)

      // Also emit extractor-style key for parity (pragmatic compromise)
      val globalSym = classSym
      emitDef(c.name.pos, globalSym)
      // emit type params for the global key too
      c.tparams.foreach { tp =>
        val tpSym = SymbolUtils.typeParamSymbol(globalSym, tp.name.value)
        val localTpSym = nextLocalSymbol()
        emitDef(tp.name.pos, tpSym)
        emitDef(tp.name.pos, localTpSym)
        addLocal(tp.name.pos, localTpSym, tp.name.value, isType = false)
      }

      val ctorSym = SymbolUtils.constructorSymbol(globalSym, 0)
      emitDef(c.name.pos, ctorSym)
      emitCtorParams(ctorSym, c.ctor.paramss)
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
      emitDef(c.name.pos, classSym)
      emitTypeParams(classSym, c.tparams)
      val ctorSym = SymbolUtils.constructorSymbol(classSym, 0)
      emitDef(c.name.pos, ctorSym)
      emitCtorParams(ctorSym, c.ctor.paramss)
      c.ctor.paramss.flatten.foreach(p => p.decltpe.foreach(resolveType))
      emitCtorFieldAccessors(classSym, c.ctor.paramss)

      val isCaseClass = c.mods.exists(_.isInstanceOf[Mod.Case])
      if (isCaseClass) {
        emitCaseClassSynthetics(effOwner, c.name.value, c.ctor.paramss,
          c.templ.stats.exists {
            case d: Defn.Def => d.name.value == "copy"
            case d: Decl.Def => d.name.value == "copy"
            case _ => false
          },
          c.templ.stats.exists {
            case d: Defn.Def => d.name.value == "apply"
            case d: Decl.Def => d.name.value == "apply"
            case _ => false
          })
      }

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
    emitDef(t.name.pos, traitSym)
    emitTypeParams(traitSym, t.tparams)
    val ctorSym = SymbolUtils.constructorSymbol(traitSym, 0)
    emitDef(t.name.pos, ctorSym)

    val oldOwner = currentOwner
    val oldIsType = currentOwnerIsType
    currentOwner = traitSym
    currentOwnerIsType = true
    // TODO if isInsideMethod -> local??

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
      emitDef(o.name.pos, localSym)
      addLocal(o.name.pos, localSym, o.name.value, isType = false)
      emitDef(o.name.pos, objSym) // also emit global key

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
      emitDef(o.name.pos, objSym)

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
    emitDef(e.name.pos, typeSym)
    emitDef(e.name.pos, termSym)
    emitTypeParams(typeSym, e.tparams)
    val ctorSym = SymbolUtils.constructorSymbol(typeSym, 0)
    emitDef(e.name.pos, ctorSym)

    val oldOwner = currentOwner
    val oldIsType = currentOwnerIsType
    currentOwner = termSym
    currentOwnerIsType = false
    // TODO isInsideMethod ?

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
    emitDef(ec.name.pos, sym)
  }

  private def resolveRepeatedEnumCase(rec: Defn.RepeatedEnumCase): Unit = {
    rec.cases.foreach { c =>
      val sym = SymbolUtils.termSymbol(currentOwner, c.value)
      emitDef(c.pos, sym)
    }
  }

  // ── def ──────────────────────────────────────────────────────

  private def resolveDef(d: Defn.Def): Unit = {
    val effOwner = effectiveOwner
    val methodSym = SymbolUtils.methodSymbol(effOwner, d.name.value, 0) // overload index 0 — v1 heuristic

    if (isInsideMethod) {
      // Local def → emit as local<N>
      val localSym = nextLocalSymbol()
      emitDef(d.name.pos, localSym)
      addLocal(d.name.pos, localSym, d.name.value, isType = false)
      emitDef(d.name.pos, methodSym) // also emit global key
    } else {
      emitDef(d.name.pos, methodSym)
    }

    // Emit param defs and resolve param type annotations
    val paramsKey = if (isInsideMethod) {
      // For local defs inside methods, params get methodSym.(name) convention
      methodSym
    } else methodSym
    emitParams(paramsKey, d.paramss)
    d.paramss.flatten.foreach(p => p.decltpe.foreach(resolveType))
    emitTypeParams(methodSym, d.tparams)
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
    emitDef(dd.name.pos, methodSym)
    emitParams(methodSym, dd.paramss)
    dd.paramss.flatten.foreach(p => p.decltpe.foreach(resolveType))
    emitTypeParams(methodSym, dd.tparams)
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
          emitDef(pv.name.pos, localSym)
          addLocal(pv.name.pos, localSym, pv.name.value, isType = false)
          // Add binding to block-level LocalScope (no push/pop)
          scopeStack.addLocalBinding(pv.name.value, localSym)
        case _ => ()
      }
      // Recurse rhs in value context
      resolveTerm(v.rhs, inCallContext = false)
    } else {
      if (!effOwner.endsWith(").")) {
        v.pats.foreach {
          case pv: Pat.Var =>
            val sym = SymbolUtils.termSymbol(effOwner, pv.name.value)
            emitDef(pv.name.pos, sym)
          case _ => ()
        }
      }
      resolveTerm(v.rhs, inCallContext = false)
    }
  }

  private def resolveDeclVal(dv: Decl.Val): Unit = {
    val effOwner = effectiveOwner
    resolveType(dv.decltpe)
    if (!isInsideMethod && !effOwner.endsWith(").")) {
      dv.pats.foreach {
        case pv: Pat.Var =>
          val sym = SymbolUtils.termSymbol(effOwner, pv.name.value)
          emitDef(pv.name.pos, sym)
        case _ => ()
      }
    }
  }

  // ── var ──────────────────────────────────────────────────────

  private def resolveVar(vr: Defn.Var): Unit = {
    val effOwner = effectiveOwner
    vr.decltpe.foreach(resolveType)
    if (isInsideMethod) {
      vr.pats.foreach {
        case pv: Pat.Var =>
          val localSym = nextLocalSymbol()
          emitDef(pv.name.pos, localSym)
          addLocal(pv.name.pos, localSym, pv.name.value, isType = false)
          scopeStack.addLocalBinding(pv.name.value, localSym)
        case _ => ()
      }
      vr.rhs match {
        case Some(t: Term) => resolveTerm(t, inCallContext = false)
        case _ => ()
      }
    } else {
      if (!effOwner.endsWith(").")) {
        vr.pats.foreach {
          case pv: Pat.Var =>
            val sym = SymbolUtils.termSymbol(effOwner, pv.name.value)
            emitDef(pv.name.pos, sym)
          case _ => ()
        }
      }
      vr.rhs match {
        case Some(t: Term) => resolveTerm(t, inCallContext = false)
        case _ => ()
      }
    }
  }

  private def resolveDeclVar(dvr: Decl.Var): Unit = {
    val effOwner = effectiveOwner
    resolveType(dvr.decltpe)
    if (!isInsideMethod && !effOwner.endsWith(").")) {
      dvr.pats.foreach {
        case pv: Pat.Var =>
          val sym = SymbolUtils.termSymbol(effOwner, pv.name.value)
          emitDef(pv.name.pos, sym)
        case _ => ()
      }
    }
  }

  // ── type alias ───────────────────────────────────────────────

  private def resolveTypeAlias(dt: Defn.Type): Unit = {
    val effOwner = effectiveOwner
    val sym = SymbolUtils.typeSymbol(effOwner, dt.name.value)
    emitDef(dt.name.pos, sym)
    emitTypeParams(sym, dt.tparams)
  }

  // ── given ────────────────────────────────────────────────────

  private def resolveGiven(g: Defn.Given): Unit = {
    if (g.name.value.nonEmpty) {
      val effOwner = effectiveOwner
      val sym = SymbolUtils.termSymbol(effOwner, g.name.value)
      emitDef(g.name.pos, sym)

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
      val effOwner = effectiveOwner
      val sym = SymbolUtils.termSymbol(effOwner, ga.name.value)
      emitDef(ga.name.pos, sym)
    }
  }

  // ── extension group ──────────────────────────────────────────

  private def resolveExtensionGroup(eg: Defn.ExtensionGroup): Unit = {
    val effOwner = effectiveOwner
    // Extension params are bound as locals for the method bodies
    methodDepth += 1
    eg.body match {
      case b: Term.Block =>
        b.stats.foreach {
          case d: Defn.Def =>
            val methodSym = SymbolUtils.methodSymbol(effOwner, d.name.value, 0)
            emitDef(d.name.pos, methodSym)
            emitParams(methodSym, eg.paramss)
            emitParams(methodSym, d.paramss)

            // Push extension params into scope
            scopeStack.push(LocalScope(collection.mutable.Map.empty[String, String]))
            methodDepth += 1
            resolveTerm(d.body, inCallContext = false)
            methodDepth -= 1
            scopeStack.pop()
          case s => resolveStat(s)
        }
      case d: Defn.Def =>
        val methodSym = SymbolUtils.methodSymbol(effOwner, d.name.value, 0)
        emitDef(d.name.pos, methodSym)
        emitParams(methodSym, eg.paramss)
        emitParams(methodSym, d.paramss)

        scopeStack.push(LocalScope(collection.mutable.Map.empty[String, String]))
        resolveTerm(d.body, inCallContext = false)
        scopeStack.pop()
      case s: Stat => resolveStat(s)
    }
    methodDepth -= 1
  }

  // ── secondary constructor ────────────────────────────────────

  private def resolveSecondaryCtor(cs: Ctor.Secondary): Unit = {
    val ctorSym = SymbolUtils.constructorSymbol(currentOwner, 0)
    emitDef(cs.name.pos, ctorSym)
    emitParams(ctorSym, cs.paramss)
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

    // Recurse args in value context
    args.foreach(a => resolveTerm(a, inCallContext = false))
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
        // Type param definition — emit as local<N> and extractor-style #[T]
        val pn = name.value
        if (pn.nonEmpty) {
          val localSym = nextLocalSymbol()
          emitDef(name.pos, localSym)
          addLocal(name.pos, localSym, pn, isType = false)

          // Also emit extractor-style key: currentOwner#[T]
          if (currentOwnerIsType) {
            val globalSym = SymbolUtils.typeParamSymbol(currentOwner, pn)
            emitDef(name.pos, globalSym)
          }

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

  private def mkPackageOwnerForPkgObj(baseOwner: String, pkgObjName: String): String = {
    val base = if (baseOwner == "_empty_/") Nil
               else baseOwner.stripSuffix("/").split('/').toList.filter(_.nonEmpty)
    SymbolUtils.packageOwner(base :+ pkgObjName)
  }

  private def emitParams(methodSym: String, paramss: List[List[Term.Param]]): Unit = {
    paramss.foreach { clause => clause.foreach { p =>
      val n = p.name.value
      if (n.nonEmpty) {
        val paramSym = SymbolUtils.parameterSymbol(methodSym, n)
        emitDef(p.name.pos, paramSym)
      }
    }}
  }

  private def emitCtorParams(ctorSym: String, paramss: List[List[Term.Param]]): Unit =
    emitParams(ctorSym, paramss)

  private def emitCtorFieldAccessors(classSym: String, paramss: List[List[Term.Param]]): Unit = {
    paramss.foreach { clause => clause.foreach { p =>
      val n = p.name.value
      if (n.nonEmpty) {
        val sym = SymbolUtils.termSymbol(classSym, n)
        emitDef(p.name.pos, sym)
      }
    }}
  }

  private def emitTypeParams(ownerTypeSym: String, tparams: List[Type.Param]): Unit = {
    tparams.foreach { tp =>
      val n = tp.name.value
      if (n.nonEmpty) {
        val sym = SymbolUtils.typeParamSymbol(ownerTypeSym, n)
        emitDef(tp.name.pos, sym)
      }
    }
  }

  // ── case class synthetics ────────────────────────────────────

  private def emitCaseClassSynthetics(
      classOwner: String,
      className: String,
      primaryCtorParamss: List[List[Term.Param]],
      hasUserCopy: Boolean,
      hasUserApply: Boolean
  ): Unit = {
    val companion = SymbolUtils.termSymbol(classOwner, className)
    emitDef(Position.None, companion)

    val applySym = SymbolUtils.methodSymbol(companion, "apply", 0)
    emitDef(Position.None, applySym)
    emitParams(applySym, primaryCtorParamss)

    val unapplySym = SymbolUtils.methodSymbol(companion, "unapply", 0)
    emitDef(Position.None, unapplySym)
    emitDef(Position.None, SymbolUtils.parameterSymbol(unapplySym, "x$1"))

    val toStringSym = SymbolUtils.methodSymbol(companion, "toString", 0)
    emitDef(Position.None, toStringSym)

    if (!hasUserCopy) {
      val classSym = SymbolUtils.typeSymbol(classOwner, className)
      val copySym = SymbolUtils.methodSymbol(classSym, "copy", 0)
      emitDef(Position.None, copySym)
      emitParams(copySym, primaryCtorParamss)
    }
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
          .orElse(Some(SymbolUtils.packageOwner(List(n)))) // fallback: treat as package path

      case Term.Select(qual, name) =>
        val qualOwner = resolveTermToOwner(qual)
        val n = name.value
        qualOwner.map { owner =>
          SymbolUtils.termSymbol(owner, n)
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
        var idx = 0
        while (idx <= 8) {
          val methodSym = SymbolUtils.methodSymbol(owner, name, idx)
          if (symbolTable.get(methodSym).isDefined) return Some(methodSym)
          idx += 1
        }
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
