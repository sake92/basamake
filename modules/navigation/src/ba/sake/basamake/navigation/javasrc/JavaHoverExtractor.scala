package ba.sake.basamake.navigation.javasrc

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal
import com.github.javaparser.{JavaParser, ParseResult}
import com.github.javaparser.Range as JpRange
import com.github.javaparser.ast.{CompilationUnit, Node}
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.comments.JavadocComment
import com.github.javaparser.ast.`type`.Type
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.navigation.DocCommentCleaner

/** Renders hover content (signature + javadoc) for a Java definition.
  *
  * The symbol index stores only (name, range, path) — signatures and doc
  * comments are derived here by re-parsing the declaring source file and
  * locating the node by name position. Works for workspace files AND dep/JDK
  * sources (both are real files on disk). */
object JavaHoverExtractor {

  def parse(content: String): Option[CompilationUnit] = {
    val res: ParseResult[CompilationUnit] = new JavaParser().parse(content)
    if (res.getResult.isPresent) Some(res.getResult.get()) else None
  }

  /** Find the declaration whose NAME position matches `range` (tolerant: same
    * start line + same name text) and render its signature + javadoc. */
  def extractCu(cu: CompilationUnit, shortName: String, range: Range): Option[(String, Option[String])] = {
    val targetLine = range.startLine + 1 // javaparser lines are 1-based
    val onLine = cu.findAll(classOf[Node]).asScala.iterator
      .flatMap(n => nameOf(n).map(n -> _))
      .filter { case (n, _) => nameLine(n) == targetLine }
      .toVector
    findNode(onLine, shortName, range)
      .map { case (n, _) =>
        (renderSignature(n), javadocOf(n).map(DocCommentCleaner.clean))
      }
  }

  /** Match the node at `range`: primary (line + name), then (line + name-span
    * containing the start column) for resilience against shortName/symbol drift. */
  private def findNode(onLine: Vector[(Node, String)], shortName: String, range: Range): Option[(Node, String)] = {
    val targetCol = range.startCharacter + 1 // javaparser columns are 1-based
    onLine
      .find { case (_, name) => name == shortName }
      .orElse {
        onLine.find { case (n, _) =>
          nameRange(n).exists(r => r.begin.column <= targetCol && targetCol <= r.end.column)
        }
      }
  }

  /** Range of the NAME token for nodes matched by `nameOf`. */
  private def nameRange(n: Node): Option[JpRange] = {
    val nameNode: Option[Node] = n match {
      case m: MethodDeclaration             => Some(m.getName)
      case c: ConstructorDeclaration        => Some(c.getName)
      case c: CompactConstructorDeclaration => Some(c.getName)
      case c: ClassOrInterfaceDeclaration   => Some(c.getName)
      case e: EnumDeclaration               => Some(e.getName)
      case a: AnnotationDeclaration         => Some(a.getName)
      case r: RecordDeclaration             => Some(r.getName)
      case v: VariableDeclarator            => Some(v.getName)
      case e: EnumConstantDeclaration       => Some(e.getName)
      case a: AnnotationMemberDeclaration   => Some(a.getName)
      case p: Parameter                     => Some(p.getName)
      case _                                => None
    }
    nameNode.flatMap(_.getRange.toScala)
  }

  // ── node matching ────────────────────────────────────────────

  private def nameOf(n: Node): Option[String] = n match {
    case m: MethodDeclaration             => Some(m.getNameAsString)
    case c: ConstructorDeclaration        => Some(c.getNameAsString)
    case c: CompactConstructorDeclaration => Some(c.getNameAsString)
    case c: ClassOrInterfaceDeclaration   => Some(c.getNameAsString)
    case e: EnumDeclaration               => Some(e.getNameAsString)
    case a: AnnotationDeclaration         => Some(a.getNameAsString)
    case r: RecordDeclaration             => Some(r.getNameAsString)
    case v: VariableDeclarator            => Some(v.getNameAsString)
    case e: EnumConstantDeclaration       => Some(e.getNameAsString)
    case a: AnnotationMemberDeclaration   => Some(a.getNameAsString)
    case p: Parameter                     => Some(p.getNameAsString)
    case _                                => None
  }

  private def nameLine(n: Node): Int = {
    val nameNode: Option[Node] = n match {
      case m: MethodDeclaration             => Some(m.getName)
      case c: ConstructorDeclaration        => Some(c.getName)
      case c: CompactConstructorDeclaration => Some(c.getName)
      case c: ClassOrInterfaceDeclaration   => Some(c.getName)
      case e: EnumDeclaration               => Some(e.getName)
      case a: AnnotationDeclaration         => Some(a.getName)
      case r: RecordDeclaration             => Some(r.getName)
      case v: VariableDeclarator            => Some(v.getName)
      case e: EnumConstantDeclaration       => Some(e.getName)
      case a: AnnotationMemberDeclaration   => Some(a.getName)
      case p: Parameter                     => Some(p.getName)
      case _                                => None
    }
    nameNode.flatMap(_.getRange.toScala).map(_.begin.line).getOrElse(-1)
  }

  // ── signature rendering ──────────────────────────────────────

  private def renderSignature(n: Node): String = n match {
    case m: MethodDeclaration =>
      val tparams = renderTypeParams(m.getTypeParameters)
      val ret = renderType(m.getType)
      val params = renderParams(m.getParameters)
      val throws = renderThrows(m.getThrownExceptions)
      s"${renderMods(m.getModifiers)}${tparams}${ret} ${m.getNameAsString}${params}${throws}".trim

    case c: ConstructorDeclaration =>
      val params = renderParams(c.getParameters)
      val throws = renderThrows(c.getThrownExceptions)
      s"${renderMods(c.getModifiers)}${c.getNameAsString}${params}${throws}".trim

    case c: CompactConstructorDeclaration =>
      s"${renderMods(c.getModifiers)}${c.getNameAsString}".trim

    case c: ClassOrInterfaceDeclaration =>
      val kind = if (c.isInterface) "interface" else "class"
      val tparams = renderTypeParams(c.getTypeParameters)
      val ext = if (c.getExtendedTypes.isEmpty) "" else s" extends ${c.getExtendedTypes.asScala.map(_.asString()).mkString(", ")}"
      val impl = if (c.isInterface || c.getImplementedTypes.isEmpty) "" else s" implements ${c.getImplementedTypes.asScala.map(_.asString()).mkString(", ")}"
      s"${renderMods(c.getModifiers)}${kind} ${c.getNameAsString}${tparams}${ext}${impl}".trim

    case e: EnumDeclaration =>
      val impl = if (e.getImplementedTypes.isEmpty) "" else s" implements ${e.getImplementedTypes.asScala.map(_.asString()).mkString(", ")}"
      s"${renderMods(e.getModifiers)}enum ${e.getNameAsString}${impl}".trim

    case a: AnnotationDeclaration =>
      s"${renderMods(a.getModifiers)}@interface ${a.getNameAsString}".trim

    case r: RecordDeclaration =>
      val tparams = renderTypeParams(r.getTypeParameters)
      val components = renderParams(r.getParameters)
      val impl = if (r.getImplementedTypes.isEmpty) "" else s" implements ${r.getImplementedTypes.asScala.map(_.asString()).mkString(", ")}"
      s"${renderMods(r.getModifiers)}record ${r.getNameAsString}${tparams}${components}${impl}".trim

    case v: VariableDeclarator =>
      v.getParentNode.toScala match {
        case Some(f: FieldDeclaration) =>
          s"${renderMods(f.getModifiers)}${renderType(v.getType)} ${v.getNameAsString}".trim
        case _ =>
          s"${renderType(v.getType)} ${v.getNameAsString}".trim
      }

    case e: EnumConstantDeclaration =>
      e.getNameAsString

    case a: AnnotationMemberDeclaration =>
      s"${renderType(a.getType)} ${a.getNameAsString}()".trim

    case p: Parameter =>
      val varargs = if (p.isVarArgs) "..." else ""
      s"${renderMods(p.getModifiers)}${renderType(p.getType)}${varargs} ${p.getNameAsString}".trim

    case _ => n.toString
  }

  private def renderMods(mods: NodeList[Modifier]): String =
    if (mods.isEmpty) "" else mods.asScala.map(_.getKeyword.asString).mkString("", " ", " ")

  private def renderType(t: Type): String = t.asString()

  private def renderTypeParams(tparams: NodeList[com.github.javaparser.ast.`type`.TypeParameter]): String =
    if (tparams.isEmpty) "" else tparams.asScala.map(_.getNameAsString).mkString("<", ", ", "> ")

  private def renderParams(params: NodeList[Parameter]): String =
    params.asScala.map { p =>
      val varargs = if (p.isVarArgs) "..." else ""
      val mods = renderMods(p.getModifiers)
      s"${mods}${renderType(p.getType)}${varargs} ${p.getNameAsString}".trim
    }.mkString("(", ", ", ")")

  private def renderThrows(thrown: NodeList[com.github.javaparser.ast.`type`.ReferenceType]): String =
    if (thrown.isEmpty) "" else thrown.asScala.map(_.asString()).mkString(" throws ", ", ", "")

  // ── javadoc extraction ───────────────────────────────────────

  private def javadocOf(n: Node): Option[String] = {
    try {
      n.getComment.toScala.collect { case jc: JavadocComment =>
        val j = jc.parse()
        val desc = j.getDescription.toText.trim
        val blocks = j.getBlockTags.asScala.map { t =>
          val content = t.getContent.toText.trim
          t.getName.toScala match {
            case Some(name) => s"@${t.getTagName} $name $content".trim
            case None       => s"@${t.getTagName} $content".trim
          }
        }.mkString("\n")
        List(desc, blocks).filter(_.nonEmpty).mkString("\n\n")
      }.filter(_.nonEmpty)
    } catch {
      case NonFatal(_) => None
    }
  }
}
