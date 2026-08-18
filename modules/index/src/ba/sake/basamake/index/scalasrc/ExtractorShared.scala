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
}
