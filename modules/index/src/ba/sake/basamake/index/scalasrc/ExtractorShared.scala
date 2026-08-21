package ba.sake.basamake.index.scalasrc

import scala.meta.*
import ba.sake.basamake.index.SymbolUtils

/** Helpers shared with `ScalaDefinitionsExtractor` so the resolver computes the
  * SAME owner keys as the extractor.
  *
  * `computeWrapper` delegates to `ScalaParseUtils` — the single source of truth
  * for wrapper conventions. `extractPackageOwner`/`ifWrapperOwner`/
  * `isTopLevelPackageOwner` remain duplicated from the extractor deliberately
  * (zero-risk boundary for the extractor tests). */
object ExtractorShared {

  /** Top-level wrapper for Scala 3 `X$package.` / `package$package.` and the
    * `.sbt` build-object convention (`build.sbt` → `_empty_/build.`).
    * Delegates to `ScalaParseUtils` — the single source of truth.
    */
  def computeWrapper(fileName: String, pkgOwner: String): Option[String] =
    ScalaParseUtils.computeWrapper(fileName, pkgOwner)

  /** Extract the top-level package owner from source stats.
    * Mirrors `ScalaDefinitionsExtractor.extractPackageOwner` exactly.
    */
  def extractPackageOwner(stats: List[Stat]): String = {
    stats.collectFirst {
      case p: Pkg =>
        SymbolUtils.packageOwner(p.ref.toString.split('.').toList)
      case po: Pkg.Object =>
        SymbolUtils.packageOwner(List(po.name.value))
    }.getOrElse(SymbolUtils.packageOwner(Nil))
  }

  /** Returns the effective owner for a top-level definition, applying the
    * `X$package.` wrapper when the current owner is the top-level package.
    * Mirrors `ScalaDefinitionsExtractor.ifWrapperOwner`.
    */
  def ifWrapperOwner(owner: String, wrapper: Option[String], topLevelPkgOwner: String): String = {
    wrapper match {
      case Some(w) if isTopLevelPackageOwner(owner, topLevelPkgOwner) => w
      case _ => owner
    }
  }

  /** Returns true if `owner` is the top-level package owner of the file.
    * Mirrors `ScalaDefinitionsExtractor.isTopLevelPackageOwner`.
    */
  def isTopLevelPackageOwner(owner: String, topLevelPkgOwner: String): Boolean = {
    owner == topLevelPkgOwner
  }

  /** Package owner for a `Pkg` statement nested inside `baseOwner`: the
    * enclosing package segments + the statement's own segments. Nested package
    * statements (`package scala` + `package collection`, as used across
    * scala-library) must ACCUMULATE, not replace — a bare `packageOwner(segs)`
    * would emit `collection/` instead of `scala/collection/` and no compiler
    * semanticdb symbol would ever match. `baseOwner` at a Pkg site is always a
    * plain package owner. Shared by the extractor (Pass 1) and resolver
    * (Pass 2) so both sides compute the SAME keys — they must never drift. */
  def mkPackageOwner(baseOwner: String, segments: List[String]): String = {
    val base = if (baseOwner == "_empty_/" || baseOwner.isEmpty) Nil
               else baseOwner.stripSuffix("/").split('/').toList.filter(_.nonEmpty)
    SymbolUtils.packageOwner(base ++ segments)
  }

  def mkPackageOwnerForPkgObj(baseOwner: String, pkgObjName: String): String =
    mkPackageOwner(baseOwner, List(pkgObjName))
}
