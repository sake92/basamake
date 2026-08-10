package ba.sake.basamake.navigation.scalasrc

import scala.meta.*
import scala.meta.dialects.{Scala3Future, Scala213}
import scala.meta.inputs.Input
import scala.meta.internal.semanticdb.Range
import scala.meta.tokens.Token
import scala.collection.mutable
import ba.sake.basamake.navigation.DocCommentCleaner

/** Renders hover content (signature + scaladoc) for a Scala definition.
  *
  * The symbol index stores only (name, range, path) — signatures and doc
  * comments are derived here by re-parsing the declaring source file and
  * locating the definition tree by name position. Works for workspace files
  * AND dep/JDK sources (both are real files on disk). */
object ScalaHoverExtractor {

  /** Parse with Scala 3 first, fall back to Scala 2 — mirrors ScalaDefinitionsExtractor. */
  def parse(content: String): Option[Source] = {
    val input = Input.String(content)
    given Dialect = Scala3Future
    input.parse[Source] match {
      case Parsed.Success(src) => Some(src)
      case Parsed.Error(_, _, _) =>
        given Dialect = Scala213
        input.parse[Source] match {
          case Parsed.Success(src) => Some(src)
          case _                   => None
        }
    }
  }

  /** Find the definition whose NAME position matches `range` (tolerant: same
    * start line + same name text) and render its signature + doc comment. */
  def extractSource(src: Source, shortName: String, range: Range): Option[(String, Option[String])] = {
    val candidates = mutable.ArrayBuffer.empty[Candidate]
    collectCandidates(src, candidates)
    candidates
      .find(c => c.pos.startLine == range.startLine && c.name == shortName)
      .map { c =>
        (c.render(), findDocComment(src, c).map(DocCommentCleaner.clean))
      }
  }

  // ── candidate collection ─────────────────────────────────────

  private final case class Candidate(pos: Position, name: String, render: () => String)

  private def collectCandidates(t: Tree, out: mutable.ArrayBuffer[Candidate]): Unit = {
    t.children.foreach(child => collectCandidates(child, out))
    t match {
      case d: Defn.Def       => out.append(Candidate(d.name.pos, d.name.value, () => renderDefnDef(d)))
      case d: Decl.Def       => out.append(Candidate(d.name.pos, d.name.value, () => renderDeclDef(d)))
      case d: Defn.Val       => d.pats.foreach { case pv: Pat.Var => out.append(Candidate(pv.name.pos, pv.name.value, () => renderVal(d.mods, "val", pv.name.value, d.decltpe))); case _ => () }
      case d: Decl.Val       => d.pats.foreach { case pv: Pat.Var => out.append(Candidate(pv.name.pos, pv.name.value, () => renderVal(d.mods, "val", pv.name.value, Some(d.decltpe)))); case _ => () }
      case d: Defn.Var       => d.pats.foreach { case pv: Pat.Var => out.append(Candidate(pv.name.pos, pv.name.value, () => renderVal(d.mods, "var", pv.name.value, d.decltpe))); case _ => () }
      case d: Decl.Var       => d.pats.foreach { case pv: Pat.Var => out.append(Candidate(pv.name.pos, pv.name.value, () => renderVal(d.mods, "var", pv.name.value, Some(d.decltpe)))); case _ => () }
      case d: Defn.Class     => out.append(Candidate(d.name.pos, d.name.value, () => renderClass(d)))
      case d: Defn.Trait     => out.append(Candidate(d.name.pos, d.name.value, () => renderTrait(d)))
      case d: Defn.Object    => out.append(Candidate(d.name.pos, d.name.value, () => renderObject(d)))
      case d: Defn.Enum      => out.append(Candidate(d.name.pos, d.name.value, () => renderEnum(d)))
      case d: Defn.EnumCase  => out.append(Candidate(d.name.pos, d.name.value, () => renderEnumCase(d)))
      case d: Defn.RepeatedEnumCase => d.cases.foreach(c => out.append(Candidate(c.pos, c.value, () => s"case ${c.value}")))
      case d: Defn.Type      => out.append(Candidate(d.name.pos, d.name.value, () => renderTypeAlias(d)))
      case d: Defn.Given     => out.append(Candidate(d.name.pos, d.name.value, () => renderGiven(d)))
      case d: Defn.GivenAlias => out.append(Candidate(d.name.pos, d.name.value, () => renderGivenAlias(d)))
      case d: Pkg.Object     => out.append(Candidate(d.name.pos, d.name.value, () => s"package object ${d.name.value}"))
      case p: Term.Param     => out.append(Candidate(p.name.pos, p.name.value, () => renderParam(p)))
      case _                 => ()
    }
  }

  // ── signature rendering ──────────────────────────────────────

  private def renderDefnDef(d: Defn.Def): String = {
    val mods = renderMods(d.mods)
    val tparams = renderTypeParams(tparamsOf(d.paramClauseGroups))
    val params = renderParamClauses(paramssOf(d.paramClauseGroups))
    val ret = d.decltpe.map(t => s": ${renderType(t)}").getOrElse("")
    s"${mods}def ${d.name.value}${tparams}${params}${ret}".trim
  }

  private def renderDeclDef(d: Decl.Def): String = {
    val mods = renderMods(d.mods)
    val tparams = renderTypeParams(tparamsOf(d.paramClauseGroups))
    val params = renderParamClauses(paramssOf(d.paramClauseGroups))
    val ret = s": ${renderType(d.decltpe)}"
    s"${mods}def ${d.name.value}${tparams}${params}${ret}".trim
  }

  private def renderVal(mods: List[Mod], keyword: String, name: String, decltpe: Option[Type]): String = {
    val modsStr = renderMods(mods)
    val tpe = decltpe.map(t => s": ${renderType(t)}").getOrElse("")
    s"${modsStr}$keyword $name$tpe".trim
  }

  private def renderClass(c: Defn.Class): String = {
    val mods = renderMods(c.mods)
    val tparams = renderTypeParams(c.tparamClause.values)
    val params = renderParamClauses(c.ctor.paramClauses.map(_.values).toList)
    s"${mods}class ${c.name.value}${tparams}${params}${renderInits(c.templ.inits)}".trim
  }

  private def renderTrait(t: Defn.Trait): String = {
    val mods = renderMods(t.mods)
    val tparams = renderTypeParams(t.tparamClause.values)
    s"${mods}trait ${t.name.value}${tparams}${renderInits(t.templ.inits)}".trim
  }

  private def renderObject(o: Defn.Object): String = {
    val mods = renderMods(o.mods)
    s"${mods}object ${o.name.value}${renderInits(o.templ.inits)}".trim
  }

  private def renderEnum(e: Defn.Enum): String = {
    val mods = renderMods(e.mods)
    val tparams = renderTypeParams(e.tparamClause.values)
    s"${mods}enum ${e.name.value}${tparams}${renderInits(e.templ.inits)}".trim
  }

  private def renderEnumCase(ec: Defn.EnumCase): String = {
    val params = renderParamClauses(ec.ctor.paramClauses.map(_.values).toList)
    s"case ${ec.name.value}${params}"
  }

  private def renderTypeAlias(d: Defn.Type): String = {
    val mods = renderMods(d.mods)
    val tparams = renderTypeParams(d.tparamClause.values)
    s"${mods}type ${d.name.value}${tparams} = ${d.body}".trim
  }

  private def renderGiven(g: Defn.Given): String = {
    val mods = renderMods(g.mods)
    val tparams = renderTypeParams(tparamsOf(g.paramClauseGroups))
    val params = renderParamClauses(paramssOf(g.paramClauseGroups))
    s"${mods}given ${g.name.value}${tparams}${params}${renderInits(g.templ.inits)}".trim
  }

  private def renderGivenAlias(ga: Defn.GivenAlias): String = {
    val mods = renderMods(ga.mods)
    val tparams = renderTypeParams(tparamsOf(ga.paramClauseGroups))
    val params = renderParamClauses(paramssOf(ga.paramClauseGroups))
    val ret = s": ${renderType(ga.decltpe)}"
    s"${mods}given ${ga.name.value}${tparams}${params}${ret}".trim
  }

  private def renderParam(p: Term.Param): String = {
    val mods = p.mods.filterNot(_.is[Mod.Annot]).map(_.toString).mkString("", " ", " ")
    val tpe = p.decltpe.map(t => s": ${renderType(t)}").getOrElse("")
    s"$mods${p.name.value}$tpe".trim
  }

  private def renderParamClauses(paramss: List[List[Term.Param]]): String =
    paramss.map { clause =>
      clause.map(renderParam).mkString("(", ", ", ")")
    }.mkString

  private def renderType(t: Type): String = t match {
    case Type.ByName(inner)   => s"=> ${renderType(inner)}"
    case Type.Repeated(inner) => s"${renderType(inner)}*"
    case other                => other.toString
  }

  private def renderTypeParams(tparams: List[Type.Param]): String =
    if (tparams.isEmpty) "" else tparams.map(_.name.value).mkString("[", ", ", "]")

  /** Type params from modern param-clause-group API (replaces deprecated `tparams`). */
  private def tparamsOf(groups: List[Member.ParamClauseGroup]): List[Type.Param] =
    groups.flatMap(_.tparamClause.values)

  /** Param clauses from modern param-clause-group API (replaces deprecated `paramss`/`sparams`). */
  private def paramssOf(groups: List[Member.ParamClauseGroup]): List[List[Term.Param]] =
    groups.flatMap(_.paramClauses.map(_.values))

  private def renderInits(inits: List[Init]): String = {
    if (inits.isEmpty) ""
    else {
      val joined = inits.map(_.toString).mkString(", ")
      if (joined.length <= 120) s" extends $joined"
      else s" extends ${joined.take(117)}..."
    }
  }

  private def renderMods(mods: List[Mod]): String =
    mods.filterNot(_.is[Mod.Annot]).map(_.toString).mkString("", " ", " ")

  // ── doc comment extraction ───────────────────────────────────

  /** Closest scaladoc comment token ending at most 2 lines above the definition
    * (allows one blank line between doc and def). */
  private def findDocComment(src: Source, c: Candidate): Option[String] = {
    if (c.pos == Position.None) return None
    val comments = src.tokens.collect { case t: Token.Comment if t.text.startsWith("/**") => t }
    comments
      .filter(t => t.pos.endLine <= c.pos.startLine && t.pos.endLine >= c.pos.startLine - 2)
      .sortBy(_.pos.end)
      .lastOption
      .map(_.text)
  }
}
