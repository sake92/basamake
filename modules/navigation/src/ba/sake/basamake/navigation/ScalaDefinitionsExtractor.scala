package ba.sake.basamake.navigation

import java.io.InputStream
import scala.meta.*
import scala.meta.dialects.{Scala3Future, Scala213}
import scala.meta.inputs.Input
import com.typesafe.scalalogging.StrictLogging
import scala.util.control.NonFatal
import scala.collection.mutable

class ScalaDefinitionsExtractor(symbolTable: SymbolTable) extends StrictLogging {

  /** Entry point from file-system scan: filename + InputStream. */
  def extract(name: String, is: InputStream): Unit =
    try {
      val content = new String(is.readAllBytes(), "UTF-8")
      extractFromContent(name, content)
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Failed to parse Scala source ${name}: ${e.getMessage}")
    }

  /** Test-friendly entry point: filename + source string. */
  def extractFromContent(fileName: String, content: String): Unit = {
    parseSource(content) match {
      case Some(src) =>
        extractInternal(fileName, src)
      case None => ()
    }
  }

  // ── parse ────────────────────────────────────────────────────

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

  // ── main traversal ───────────────────────────────────────────

  private def extractInternal(fileName: String, src: Source): Unit = {
    val ovl = mutable.Map.empty[(String, String), Int]
    topLevelPkgOwner = extractPackageOwner(src.stats)
    val wrapper = computeWrapper(fileName, topLevelPkgOwner)
    extractStats(src.stats, "_empty_/", ovl, wrapper)
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

  // ── top-level wrapper for Scala 3 X$package. / package. ──────

  private def computeWrapper(fileName: String, pkgOwner: String): Option[String] = {
    if (fileName.isEmpty) None
    else if (fileName == "package.scala") Some(SymbolUtils.termSymbol(pkgOwner, "package"))
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
        val newOwner = mkPackageOwner(segs)
        extractStats(p.body, newOwner, ovl, wrapper)

      // ── package object ────────────────────────────────────────
      case po: Pkg.Object =>
        val pkgOwner = mkPackageOwnerForPkgObj(owner, po.name.value)
        val pkgObjOwner = SymbolUtils.termSymbol(pkgOwner, "package")
        addSymbol(pkgObjOwner, "package", isType = false)
        extractStats(po.templ.stats, pkgObjOwner, ovl, None)

      // ── class ─────────────────────────────────────────────────
      case c: Defn.Class =>
        val sym = SymbolUtils.typeSymbol(owner, c.name.value)
        addSymbol(sym, c.name.value, isType = true)
        val ctorIdx = bumpOvl(ovl, sym, "<init>")
        addSymbol(SymbolUtils.constructorSymbol(sym, ctorIdx), "<init>", isType = false)
        if (c.mods.exists(_.isInstanceOf[Mod.Case]))
          emitCaseClassSynthetics(owner, c.name.value, ovl)
        extractCaseClassBody(c.templ.stats, sym, owner, c.name.value, ovl)

      // ── trait ─────────────────────────────────────────────────
      case t: Defn.Trait =>
        val sym = SymbolUtils.typeSymbol(owner, t.name.value)
        addSymbol(sym, t.name.value, isType = true)
        extractStats(t.templ.stats, sym, ovl, None)

      // ── object ────────────────────────────────────────────────
      case o: Defn.Object =>
        val sym = SymbolUtils.termSymbol(owner, o.name.value)
        addSymbol(sym, o.name.value, isType = false)
        extractStats(o.templ.stats, sym, ovl, None)

      // ── enum ──────────────────────────────────────────────────
      case e: Defn.Enum =>
        val typeSym = SymbolUtils.typeSymbol(owner, e.name.value)
        val termSym = SymbolUtils.termSymbol(owner, e.name.value)
        addSymbol(typeSym, e.name.value, isType = true)
        addSymbol(termSym, e.name.value, isType = false)
        val ctorIdx = bumpOvl(ovl, typeSym, "<init>")
        addSymbol(SymbolUtils.constructorSymbol(typeSym, ctorIdx), "<init>", isType = false)
        extractStats(e.templ.stats, termSym, ovl, None)

      // ── enum case (single) ────────────────────────────────────
      case ec: Defn.EnumCase =>
        addSymbol(SymbolUtils.termSymbol(owner, ec.name.value), ec.name.value, isType = false)

      // ── repeated enum case ────────────────────────────────────
      case rec: Defn.RepeatedEnumCase =>
        rec.cases.foreach { c =>
          addSymbol(SymbolUtils.termSymbol(owner, c.value), c.value, isType = false)
        }

      // ── def ───────────────────────────────────────────────────
      case d: Defn.Def =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        val idx = bumpOvl(ovl, effectiveOwner, d.name.value)
        val methodSym = SymbolUtils.methodSymbol(effectiveOwner, d.name.value, idx)
        addSymbol(methodSym, d.name.value, isType = false)
        extractBlockStats(d.body, methodSym, ovl)

      // ── abstract decl def ─────────────────────────────────────
      case dd: Decl.Def =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        val idx = bumpOvl(ovl, effectiveOwner, dd.name.value)
        val methodSym = SymbolUtils.methodSymbol(effectiveOwner, dd.name.value, idx)
        addSymbol(methodSym, dd.name.value, isType = false)

      // ── abstract decl val ─────────────────────────────────────
      case dv: Decl.Val =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        if (!effectiveOwner.endsWith(")."))
          dv.pats.foreach {
            case pv: Pat.Var =>
              addSymbol(SymbolUtils.termSymbol(effectiveOwner, pv.name.value), pv.name.value, isType = false)
            case _ => ()
          }

      // ── abstract decl var ─────────────────────────────────────
      case dvr: Decl.Var =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        if (!effectiveOwner.endsWith(")."))
          dvr.pats.foreach {
            case pv: Pat.Var =>
              addSymbol(SymbolUtils.termSymbol(effectiveOwner, pv.name.value), pv.name.value, isType = false)
            case _ => ()
          }

      // ── secondary constructor ─────────────────────────────────
      case cs: Ctor.Secondary =>
        val idx = bumpOvl(ovl, owner, "<init>")
        addSymbol(SymbolUtils.constructorSymbol(owner, idx), "<init>", isType = false)

      // ── val ───────────────────────────────────────────────────
      case v: Defn.Val =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        if (!effectiveOwner.endsWith(")."))
          v.pats.foreach {
            case pv: Pat.Var =>
              addSymbol(SymbolUtils.termSymbol(effectiveOwner, pv.name.value), pv.name.value, isType = false)
            case _ => ()
          }
        extractBlockStats(v.rhs, effectiveOwner, ovl)

      // ── var ───────────────────────────────────────────────────
      case vr: Defn.Var =>
        val effectiveOwner = ifWrapperOwner(owner, wrapper)
        if (!effectiveOwner.endsWith(")."))
          vr.pats.foreach {
            case pv: Pat.Var =>
              addSymbol(SymbolUtils.termSymbol(effectiveOwner, pv.name.value), pv.name.value, isType = false)
            case _ => ()
          }
        vr.rhs.foreach(rhs => extractBlockStats(rhs, effectiveOwner, ovl))

      // ── type alias (includes opaque type) ─────────────────────
      case dt: Defn.Type =>
        addSymbol(SymbolUtils.typeSymbol(owner, dt.name.value), dt.name.value, isType = true)

      // ── named given ───────────────────────────────────────────
      case g: Defn.Given =>
        if (g.name.value.nonEmpty) {
          val sym = SymbolUtils.termSymbol(owner, g.name.value)
          addSymbol(sym, g.name.value, isType = false)
          extractStats(g.templ.stats, sym, ovl, None)
        }

      // ── named given alias ─────────────────────────────────────
      case ga: Defn.GivenAlias =>
        if (ga.name.value.nonEmpty)
          addSymbol(SymbolUtils.termSymbol(owner, ga.name.value), ga.name.value, isType = false)

      // ── extension group ───────────────────────────────────────
      case eg: Defn.ExtensionGroup =>
        eg.body match {
          case b: Term.Block => extractStats(b.stats, owner, ovl, None)
          case s: Stat => extractStat(s, owner, ovl, None)
        }

      // ── expression blocks that may contain nested definitions ─
      case b: Term.Block =>
        extractStats(b.stats, owner, ovl, None)

      case _ => ()
    }
  }

  // ── helpers ──────────────────────────────────────────────────

  private def mkPackageOwner(segments: List[String]): String =
    SymbolUtils.packageOwner(segments)

  private def mkPackageOwnerForPkgObj(baseOwner: String, pkgObjName: String): String = {
    val base = if (baseOwner == "_empty_/") Nil
               else baseOwner.stripSuffix("/").split('/').toList.filter(_.nonEmpty)
    SymbolUtils.packageOwner(base :+ pkgObjName)
  }

  private def extractCaseClassBody(
    stats: List[Stat],
    classSym: String,
    classOwner: String,
    className: String,
    ovl: mutable.Map[(String, String), Int]
  ): Unit = {
    val companion = SymbolUtils.termSymbol(classOwner, className)
    stats.foreach {
      case d: Defn.Def if d.name.value == "apply" =>
        val idx = bumpOvl(ovl, companion, "apply")
        addSymbol(SymbolUtils.methodSymbol(companion, "apply", idx), "apply", isType = false)
      case d: Defn.Def if d.name.value == "copy" =>
        val idx = bumpOvl(ovl, classSym, "copy")
        addSymbol(SymbolUtils.methodSymbol(classSym, "copy", idx), "copy", isType = false)
      case dd: Decl.Def if dd.name.value == "apply" =>
        val idx = bumpOvl(ovl, companion, "apply")
        addSymbol(SymbolUtils.methodSymbol(companion, "apply", idx), "apply", isType = false)
      case dd: Decl.Def if dd.name.value == "copy" =>
        val idx = bumpOvl(ovl, classSym, "copy")
        addSymbol(SymbolUtils.methodSymbol(classSym, "copy", idx), "copy", isType = false)
      case other => extractStat(other, classSym, ovl, None)
    }
  }

  private def extractBlockStats(body: Term, owner: String, ovl: mutable.Map[(String, String), Int]): Unit = {
    body match {
      case b: Term.Block => extractStats(b.stats, owner, ovl, None)
      case _ => ()
    }
  }

  private def addSymbol(symbol: String, shortName: String, isType: Boolean): Unit = {
    symbolTable.add(SymbolDefinition(symbol, shortName, isType, None))
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
      case Some(w) if isTopLevelPackageOwner(owner) => w
      case _ => owner
    }
  }

  // track top-level package owner for accurate wrapper scoping
  private var topLevelPkgOwner: String = "_empty_/"

  private def isTopLevelPackageOwner(owner: String): Boolean = {
    owner == topLevelPkgOwner
  }

  // ── case class synthetics ────────────────────────────────────

  private def emitCaseClassSynthetics(
    classOwner: String,
    className: String,
    ovl: mutable.Map[(String, String), Int]
  ): Unit = {
    val companion = SymbolUtils.termSymbol(classOwner, className)
    addSymbol(companion, className, isType = false)
    val applyIdx = bumpOvl(ovl, companion, "apply")
    addSymbol(SymbolUtils.methodSymbol(companion, "apply", applyIdx), "apply", isType = false)
    val classSym = SymbolUtils.typeSymbol(classOwner, className)
    val copyIdx = bumpOvl(ovl, classSym, "copy")
    addSymbol(SymbolUtils.methodSymbol(classSym, "copy", copyIdx), "copy", isType = false)
  }

}
