package ba.sake.basamake.navigation.scalasrc

import java.io.InputStream
import scala.compiletime.uninitialized
import scala.meta.*
import scala.meta.dialects.{Scala3Future, Scala213}
import scala.meta.inputs.Input
import com.typesafe.scalalogging.StrictLogging
import scala.util.control.NonFatal
import scala.collection.mutable
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, SymbolUtils}

class ScalaDefinitionsExtractor(symbolTable: SymbolTable) extends StrictLogging {

  /** Entry point from file-system scan: filename + InputStream. */
  def extract(name: String, is: InputStream, path: os.Path): Unit =
    try {
      val content = new String(is.readAllBytes(), "UTF-8")
      extractFromContent(name, content, path)
    } catch {
      case NonFatal(e) =>
        logger.error(s"Failed to parse Scala source '${path}': ${e.getMessage}")
    }

  /** Test-friendly entry point: filename + source string. */
  def extractFromContent(fileName: String, content: String, path: os.Path): Unit =
    try {
      currentPath = path
      require(fileName.nonEmpty, "fileName must be non-empty — Scala 3 always wraps top-level defs under `<basename>$package.` and needs the filename to compute it")
      parseSource(content) match {
        case Right(src) =>
          extractInternal(fileName, src)
        case Left(err) =>
          logger.error(s"Failed to parse Scala source '${path}': ${err}")
      }
    } catch {
      case NonFatal(e) =>
        // One unhandled tree shape must never abort workspace indexing — log
        // (MatchError's message names the tree class) and continue.
        logger.warn(s"Failed to extract definitions from ${path}: ${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  // ── parse ────────────────────────────────────────────────────

  private def parseSource(content: String): Either[String, Source] = {
    val input = Input.String(content)
    val scala3Result ={ given Dialect = Scala3Future; input.parse[Source] } 
    scala3Result match {
      case Parsed.Success(source) => Right(source)
      case Parsed.Error(_, msg1, _) =>
        val scala2Result = { given Dialect = Scala213; input.parse[Source] }
        scala2Result match {
          case Parsed.Success(source) => Right(source)
          case Parsed.Error(_, msg2, _) => Left(s"""scala3: "${msg1}"; scala2: "${msg2}";""")
        }
    }
  }

  // ── main traversal ───────────────────────────────────────────

  private var currentPath: os.Path = uninitialized

  private def extractInternal(fileName: String, src: Source): Unit = {
    val ovl = mutable.Map.empty[(String, String), Int]
    topLevelPkgOwner = extractPackageOwner(src.stats)
    val wrapper = computeWrapper(fileName, topLevelPkgOwner)
    wrapperUsed = false
    extractStats(src.stats, "_empty_/", ovl, wrapper)
    if (wrapperUsed) wrapper.foreach { w =>
      val shortName = w.split('/').last.stripSuffix(".")
      addSymbol(w, shortName, isType = false, Position.None)
    }
  }

  // ── package owner extraction ─────────────────────────────────

  private def extractPackageOwner(stats: List[Stat]): String = {
    stats.collectFirst {
      case p: Pkg =>
        SymbolUtils.packageOwner(p.ref.toString.split('.').toList)
      case po: Pkg.Object =>
        SymbolUtils.packageOwner(List(po.name.value))
    }.getOrElse(SymbolUtils.packageOwner(Nil))
  }

  // ── top-level wrapper for Scala 3 X$package. / package$package. ──

  private def computeWrapper(fileName: String, pkgOwner: String): Option[String] = {
    if (fileName == "package.scala") Some(SymbolUtils.termSymbol(pkgOwner, "package$package"))
    else {
      val baseName = fileName.stripSuffix(".scala")
      Some(SymbolUtils.termSymbol(pkgOwner, s"${baseName}$$package"))
    }
  }

  // ── stat extraction ──────────────────────────────────────────

  private def extractStats(
    stats: List[Stat],
    owner: String,
    ovl: mutable.Map[(String, String), Int],
    wrapper: Option[String]
  ): Unit = {
    stats.foreach(extractStat(_, owner, ovl, wrapper))
  }

  private def extractStat(
    stat: Stat,
    owner: String,
    ovl: mutable.Map[(String, String), Int],
    wrapper: Option[String]
  ): Unit = {
    stat match {
      // ── package ───────────────────────────────────────────────
      case p: Pkg =>
        val segs = p.ref.toString.split('.').toList
        val newOwner = mkPackageOwner(owner, segs)
        extractStats(p.body, newOwner, ovl, wrapper)

      // ── package object ────────────────────────────────────────
      case po: Pkg.Object =>
        val pkgOwner = mkPackageOwnerForPkgObj(owner, po.name.value)
        val pkgObjOwner = SymbolUtils.termSymbol(pkgOwner, "package")
        addSymbol(pkgObjOwner, "package", isType = false, po.name.pos)
        extractStats(po.templ.stats, pkgObjOwner, ovl, None)

      // ── class ─────────────────────────────────────────────────
      case c: Defn.Class =>
        val sym = SymbolUtils.typeSymbol(owner, c.name.value)
        addSymbol(sym, c.name.value, isType = true, c.name.pos)
        emitTypeParams(sym, c.tparams, c.name.pos)
        val ctorSym = SymbolUtils.constructorSymbol(sym, bumpOvl(ovl, sym, "<init>"))
        addSymbol(ctorSym, "<init>", isType = false, c.name.pos) // stand-in: class name position
        emitCtorParams(ctorSym, c.ctor.paramss, c.name.pos)
        emitCtorFieldAccessors(sym, c.ctor.paramss, c.name.pos)
        val isCaseClass = c.mods.exists(_.isInstanceOf[Mod.Case])
        if (isCaseClass)
          emitCaseClassSynthetics(owner, c.name.value, c.ctor.paramss,
            hasUserCopy = c.templ.stats.exists {
              case d: Defn.Def => d.name.value == "copy"
              case d: Decl.Def => d.name.value == "copy"
              case _ => false
            },
            hasUserApply = c.templ.stats.exists {
              case d: Defn.Def => d.name.value == "apply"
              case d: Decl.Def => d.name.value == "apply"
              case _ => false
            },
            ovl, c.name.pos)
        extractStats(c.templ.stats, sym, ovl, None)

      // ── trait ─────────────────────────────────────────────────
      case t: Defn.Trait =>
        val sym = SymbolUtils.typeSymbol(owner, t.name.value)
        addSymbol(sym, t.name.value, isType = true, t.name.pos)
        val ctorIdx = bumpOvl(ovl, sym, "<init>")
        addSymbol(SymbolUtils.constructorSymbol(sym, ctorIdx), "<init>", isType = false, t.name.pos)
        emitTypeParams(sym, t.tparams, t.name.pos)
        extractStats(t.templ.stats, sym, ovl, None)

      // ── object ────────────────────────────────────────────────
      case o: Defn.Object =>
        val sym = SymbolUtils.termSymbol(owner, o.name.value)
        addSymbol(sym, o.name.value, isType = false, o.name.pos)
        extractStats(o.templ.stats, sym, ovl, None)

      // ── enum ──────────────────────────────────────────────────
      case e: Defn.Enum =>
        val typeSym = SymbolUtils.typeSymbol(owner, e.name.value)
        val termSym = SymbolUtils.termSymbol(owner, e.name.value)
        addSymbol(typeSym, e.name.value, isType = true, e.name.pos)
        addSymbol(termSym, e.name.value, isType = false, e.name.pos)
        val ctorIdx = bumpOvl(ovl, typeSym, "<init>")
        addSymbol(SymbolUtils.constructorSymbol(typeSym, ctorIdx), "<init>", isType = false, e.name.pos)
        emitTypeParams(typeSym, e.tparams, e.name.pos)
        extractStats(e.templ.stats, termSym, ovl, None)

      // ── enum case (single) ────────────────────────────────────
      case ec: Defn.EnumCase =>
        addSymbol(SymbolUtils.termSymbol(owner, ec.name.value), ec.name.value, isType = false, ec.name.pos)

      // ── repeated enum case ────────────────────────────────────
      case rec: Defn.RepeatedEnumCase =>
        rec.cases.foreach { c =>
          addSymbol(SymbolUtils.termSymbol(owner, c.value), c.value, isType = false, c.pos)
        }

      // ── def ───────────────────────────────────────────────────
      case d: Defn.Def =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        val idx = bumpOvl(ovl, effectiveOwner, d.name.value)
        val methodSym = SymbolUtils.methodSymbol(effectiveOwner, d.name.value, idx)
        addSymbol(methodSym, d.name.value, isType = false, d.name.pos)
        emitParams(methodSym, d.paramss, d.name.pos)

      // ── abstract decl def ─────────────────────────────────────
      case dd: Decl.Def =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        val idx = bumpOvl(ovl, effectiveOwner, dd.name.value)
        val methodSym = SymbolUtils.methodSymbol(effectiveOwner, dd.name.value, idx)
        addSymbol(methodSym, dd.name.value, isType = false, dd.name.pos)
        emitParams(methodSym, dd.paramss, dd.name.pos)

      // ── abstract decl val ─────────────────────────────────────
      case dv: Decl.Val =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        if (!effectiveOwner.endsWith(")."))
          dv.pats.foreach {
            case pv: Pat.Var =>
              addSymbol(SymbolUtils.termSymbol(effectiveOwner, pv.name.value), pv.name.value, isType = false, pv.name.pos)
            case _ => ()
          }

      // ── abstract decl var ─────────────────────────────────────
      case dvr: Decl.Var =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        if (!effectiveOwner.endsWith(")."))
          dvr.pats.foreach {
            case pv: Pat.Var =>
              addSymbol(SymbolUtils.termSymbol(effectiveOwner, pv.name.value), pv.name.value, isType = false, pv.name.pos)
            case _ => ()
          }

      // ── secondary constructor ─────────────────────────────────
      case cs: Ctor.Secondary =>
        val idx = bumpOvl(ovl, owner, "<init>")
        val ctorSym = SymbolUtils.constructorSymbol(owner, idx)
        addSymbol(ctorSym, "<init>", isType = false, cs.name.pos)
        emitParams(ctorSym, cs.paramss, cs.name.pos)

      // ── val ───────────────────────────────────────────────────
      case v: Defn.Val =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        if (!effectiveOwner.endsWith(")."))
          v.pats.foreach {
            case pv: Pat.Var =>
              addSymbol(SymbolUtils.termSymbol(effectiveOwner, pv.name.value), pv.name.value, isType = false, pv.name.pos)
            case _ => ()
          }

      // ── var ───────────────────────────────────────────────────
      case vr: Defn.Var =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        if (!effectiveOwner.endsWith(")."))
          vr.pats.foreach {
            case pv: Pat.Var =>
              addSymbol(SymbolUtils.termSymbol(effectiveOwner, pv.name.value), pv.name.value, isType = false, pv.name.pos)
            case _ => ()
          }

      // ── type alias (includes opaque type) ─────────────────────
      case dt: Defn.Type =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        addSymbol(SymbolUtils.typeSymbol(effectiveOwner, dt.name.value), dt.name.value, isType = true, dt.name.pos)

      // ── named given ───────────────────────────────────────────
      case g: Defn.Given =>
        if (g.name.value.nonEmpty) {
          val effectiveOwner = ifWrapperOwner(owner, wrapper)
          val sym = SymbolUtils.termSymbol(effectiveOwner, g.name.value)
          addSymbol(sym, g.name.value, isType = false, g.name.pos)
          extractStats(g.templ.stats, sym, ovl, None)
        }

      // ── named given alias ─────────────────────────────────────
      case ga: Defn.GivenAlias =>
        if (ga.name.value.nonEmpty) {
          val effectiveOwner = ifWrapperOwner(owner, wrapper)
          addSymbol(SymbolUtils.termSymbol(effectiveOwner, ga.name.value), ga.name.value, isType = false, ga.name.pos)
        }

      // ── extension group ───────────────────────────────────────
      case eg: Defn.ExtensionGroup =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        eg.body match {
          case b: Term.Block =>
            b.stats.foreach {
              case d: Defn.Def =>
                val idx = bumpOvl(ovl, effectiveOwner, d.name.value)
                val methodSym = SymbolUtils.methodSymbol(effectiveOwner, d.name.value, idx)
                addSymbol(methodSym, d.name.value, isType = false, d.name.pos)
                emitParams(methodSym, eg.paramss, d.name.pos)   // extension params
                emitParams(methodSym, d.paramss, d.name.pos)    // method's own params
              case s => extractStat(s, effectiveOwner, ovl, None)
            }
          case d: Defn.Def =>
            val idx = bumpOvl(ovl, effectiveOwner, d.name.value)
            val methodSym = SymbolUtils.methodSymbol(effectiveOwner, d.name.value, idx)
            addSymbol(methodSym, d.name.value, isType = false, d.name.pos)
            emitParams(methodSym, eg.paramss, d.name.pos)
            emitParams(methodSym, d.paramss, d.name.pos)
          case s: Stat => extractStat(s, effectiveOwner, ovl, None)
        }

      // ── expression blocks that may contain nested definitions ─
      case b: Term.Block =>
        extractStats(b.stats, owner, ovl, None)

      case _ => ()
    }
  }

  // ── helpers ──────────────────────────────────────────────────

  /** Package owner for a `Pkg` statement nested inside `baseOwner`: the enclosing
    * package segments + the statement's own segments. Nested package statements
    * (`package scala` + `package collection`, as used across scala-library) must
    * ACCUMULATE, not replace — a bare `packageOwner(segs)` would emit
    * `collection/` instead of `scala/collection/` and no compiler semanticdb symbol
    * would ever match. `baseOwner` at a Pkg site is always a plain package owner. */
  private def mkPackageOwner(baseOwner: String, segments: List[String]): String = {
    val base = if (baseOwner == "_empty_/" || baseOwner.isEmpty) Nil
               else baseOwner.stripSuffix("/").split('/').toList.filter(_.nonEmpty)
    SymbolUtils.packageOwner(base ++ segments)
  }

  private def mkPackageOwnerForPkgObj(baseOwner: String, pkgObjName: String): String =
    mkPackageOwner(baseOwner, List(pkgObjName))

  private def addSymbol(symbol: String, shortName: String, isType: Boolean, pos: Position): Unit = {
    val range = if (pos == Position.None) new Range(0, 0, 0, 0) else PositionUtils.toRange(pos)
    symbolTable.add(SymbolDefinition(symbol, shortName, isType, range, currentPath))
  }

  private def bumpOvl(
    ovl: mutable.Map[(String, String), Int],
    owner: String,
    name: String
  ): Int = {
    val key = (owner, name)
    val idx = ovl.getOrElse(key, 0)
    ovl(key) = idx + 1
    idx
  }

  private def ifWrapperOwner(owner: String, wrapper: Option[String]): String = {
    wrapper match {
      case Some(w) if isTopLevelPackageOwner(owner) =>
        wrapperUsed = true
        w
      case _ => owner
    }
  }

  // track top-level package owner for accurate wrapper scoping
  private var topLevelPkgOwner: String = "_empty_/"

  private var wrapperUsed: Boolean = false

  private def isTopLevelPackageOwner(owner: String): Boolean = {
    owner == topLevelPkgOwner
  }

  // ── type parameter emission ──────────────────────────────────

  private def emitTypeParams(ownerTypeSym: String, tparams: List[Type.Param], standInPos: Position): Unit = {
    tparams.foreach { tp =>
      val n = tp.name.value
      if (n.nonEmpty)
        addSymbol(SymbolUtils.typeParamSymbol(ownerTypeSym, n), n, isType = false, tp.name.pos)
    }
  }

  // ── parameter emission helpers ───────────────────────────────

  /** Emits `<methodSym>(<paramName>)` for each Term.Param in the paramss. */
  private def emitParams(methodSym: String, paramss: List[List[Term.Param]], standInPos: Position): Unit =
    paramss.foreach { clause => clause.foreach { p =>
      val n = p.name.value
      if (n.nonEmpty) addSymbol(SymbolUtils.parameterSymbol(methodSym, n), n, isType = false, p.name.pos)
    }}

  /** Primary-constructor params double as init parameters.
    * Uses standInPos for the range (enclosing class name position). */
  private def emitCtorParams(ctorSym: String, paramss: List[List[Term.Param]], standInPos: Position): Unit =
    emitParams(ctorSym, paramss, standInPos)

  /** Emits `<classSym>.<paramName>.` for each primary-ctor param.
    * Uses standInPos for the range (enclosing class name position). */
  private def emitCtorFieldAccessors(classSym: String, paramss: List[List[Term.Param]], standInPos: Position): Unit =
    paramss.foreach { clause => clause.foreach { p =>
      val n = p.name.value
      if (n.nonEmpty) addSymbol(SymbolUtils.termSymbol(classSym, n), n, isType = false, standInPos) // synthetic → stand-in
    }}

  // ── case class synthetics ────────────────────────────────────

  /**
    * Emits: companion object, apply() + params, unapply() + param, copy() + params,
    * and toString(). Skips copy() if the user defines their own `def copy` in the
    * class body. Synthetic apply always goes in the companion; user-defined apply
    * stays in the class body.
    *
    * Skip: `copy$default$N`, `_N`, `productElement`, `$values`, etc. (internal).
    *
    * All synthetic ranges use `classPos` as stand-in (the enclosing class name position).
    */
  private def emitCaseClassSynthetics(
      classOwner: String,
      className: String,
      primaryCtorParamss: List[List[Term.Param]],
      hasUserCopy: Boolean,
      hasUserApply: Boolean,
      ovl: mutable.Map[(String, String), Int],
      classPos: Position
  ): Unit = {
    val companion = SymbolUtils.termSymbol(classOwner, className)
    addSymbol(companion, className, isType = false, classPos) // synthetic companion → stand-in

    // Synthetic apply in companion — always emitted.
    val applyIdx = bumpOvl(ovl, companion, "apply")
    val applySym = SymbolUtils.methodSymbol(companion, "apply", applyIdx)
    addSymbol(applySym, "apply", isType = false, classPos) // synthetic → stand-in
    emitParams(applySym, primaryCtorParamss, classPos)

    // Synthetic unapply in companion.
    val unapplyIdx = bumpOvl(ovl, companion, "unapply")
    val unapplySym = SymbolUtils.methodSymbol(companion, "unapply", unapplyIdx)
    addSymbol(unapplySym, "unapply", isType = false, classPos) // synthetic → stand-in
    // unapply has one synthetic param `x$1`
    addSymbol(SymbolUtils.parameterSymbol(unapplySym, "x$1"), "x$1", isType = false, classPos) // synthetic → stand-in

    // Synthetic toString in companion.
    val toStringIdx = bumpOvl(ovl, companion, "toString")
    addSymbol(SymbolUtils.methodSymbol(companion, "toString", toStringIdx), "toString", isType = false, classPos) // synthetic → stand-in

    // Synthetic copy in class — skip if user defines their own `def copy`.
    if (!hasUserCopy) {
      val classSym = SymbolUtils.typeSymbol(classOwner, className)
      val copyIdx = bumpOvl(ovl, classSym, "copy")
      val copySym = SymbolUtils.methodSymbol(classSym, "copy", copyIdx)
      addSymbol(copySym, "copy", isType = false, classPos) // synthetic → stand-in
      emitParams(copySym, primaryCtorParamss, classPos)
    }
  }

}
