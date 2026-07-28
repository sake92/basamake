package ba.sake.basamake.navigation.scalasrc

import scala.meta.*
import ba.sake.basamake.navigation.SymbolUtils

/** Duplicated helpers from `ScalaDefinitionsExtractor` so the resolver can
  * compute the SAME owner keys as the extractor without modifying the extractor.
  * Guarantees the 21 extractor tests stay green (zero risk).
  *
  * Strategy: duplicate, do NOT refactor the extractor. If later dedup desired,
  * refactor ONLY after verifying `deder exec -t test -m navigation-test` is 100%
  * green with the refactored extractor.
  */
object ExtractorShared {

  /** Top-level wrapper for Scala 3 `X$package.` / `package$package.`.
    * Mirrors `ScalaDefinitionsExtractor.computeWrapper` exactly.
    */
  def computeWrapper(fileName: String, pkgOwner: String): Option[String] = {
    if (fileName == "package.scala") Some(SymbolUtils.termSymbol(pkgOwner, "package$package"))
    else {
      val baseName = fileName.stripSuffix(".scala")
      Some(SymbolUtils.termSymbol(pkgOwner, s"${baseName}$$package"))
    }
  }

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
