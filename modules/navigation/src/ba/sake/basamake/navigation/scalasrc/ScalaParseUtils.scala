package ba.sake.basamake.navigation.scalasrc

import scala.meta.*
import scala.meta.dialects.{Scala213, Scala3Future, Sbt1}
import scala.meta.inputs.Input
import ba.sake.basamake.navigation.SymbolUtils

/** Scala-family file styles that share the parser/extractor/resolver pipeline.
  * Growth seam for future styles: `.sc` (scala-cli/Ammonite) and `build.sc` (Mill)
  * both need the same "top-level statements allowed" treatment as `Sbt`. */
enum ScalaFileStyle {
  case Scala, Sbt
}

object ScalaFileStyle {

  /** Derive the style from the file name (extension-based — LSP `languageId`
    * cannot distinguish `.sbt` from `.scala`, the VS Code extension registers
    * both under `scala`). */
  def fromFileName(fileName: String): ScalaFileStyle =
    if fileName.endsWith(".sbt") then Sbt else Scala
}

/** Shared parse cascade + wrapper computation for Scala-family files.
  *
  * Cascade (both styles start with a top-level-enabled Scala 3 dialect; `Sbt`
  * also allows bare top-level terms, since `.sbt` settings like
  * `ThisBuild / version := "1.0"` are expressions):
  *   1. Scala 3 + top-level statements — in scalameta 4.17.2 the Scala 3 chain
  *      (Scala30 → Scala3Future) already enables `allowToplevelStatements`, so
  *      `withAllowToplevelStatements(true)` is an explicit no-op guard; the
  *      behavior-changing flag is `withAllowToplevelTerms(true)` (Sbt only).
  *   2. `Sbt` style only: `Sbt1` — catches Scala-2-only syntax the Scala 3 parser
  *      rejects, e.g. do-while loops (legal in `.sbt`).
  *   3. `Scala213` — final fallback for both styles.
  *
  * Wrapper symbols: `.scala` files keep the compiler convention `<file>$package.`;
  * `.sbt` files use a stable object named after the file (`build.sbt` → `build`),
  * mirroring sbt's user-facing model (sbt's real compiled modules are content-hash
  * named, so matching them is impossible). */
object ScalaParseUtils {

  def parseSource(fileName: String, content: String): Either[String, Source] = {
    val style = ScalaFileStyle.fromFileName(fileName)
    val input = Input.String(content)
    val sbtLike = style == ScalaFileStyle.Sbt
    val step1 =
      if sbtLike then Scala3Future.withAllowToplevelStatements(true).withAllowToplevelTerms(true)
      else Scala3Future.withAllowToplevelStatements(true)
    val step1Result = { given Dialect = step1; input.parse[Source] }
    step1Result match {
      case Parsed.Success(source) => Right(source)
      case Parsed.Error(_, msg1, _) =>
        if (sbtLike) {
          val sbt1Result = { given Dialect = Sbt1; input.parse[Source] }
          sbt1Result match {
            case Parsed.Success(source) => Right(source)
            case Parsed.Error(_, msg2, _) =>
              val scala2Result = { given Dialect = Scala213; input.parse[Source] }
              scala2Result match {
                case Parsed.Success(source) => Right(source)
                case Parsed.Error(_, msg3, _) => Left(s"""scala3: "$msg1"; sbt1: "$msg2"; scala2: "$msg3";""")
              }
          }
        } else {
          val scala2Result = { given Dialect = Scala213; input.parse[Source] }
          scala2Result match {
            case Parsed.Success(source) => Right(source)
            case Parsed.Error(_, msg2, _) => Left(s"""scala3: "$msg1"; scala2: "$msg2";""")
          }
        }
    }
  }

  /** Top-level wrapper symbol: `X$package.` for `.scala` (compiler convention),
    * `<baseName>.` for `.sbt` (build-object convention). Honors a package owner
    * if the file declares one. */
  def computeWrapper(fileName: String, pkgOwner: String): Option[String] =
    ScalaFileStyle.fromFileName(fileName) match {
      case ScalaFileStyle.Scala =>
        if (fileName == "package.scala") Some(SymbolUtils.termSymbol(pkgOwner, "package$package"))
        else {
          val baseName = fileName.stripSuffix(".scala")
          Some(SymbolUtils.termSymbol(pkgOwner, s"${baseName}$$package"))
        }
      case ScalaFileStyle.Sbt =>
        val baseName = fileName.stripSuffix(".sbt")
        if (baseName.isEmpty) None else Some(SymbolUtils.termSymbol(pkgOwner, baseName))
    }
}
