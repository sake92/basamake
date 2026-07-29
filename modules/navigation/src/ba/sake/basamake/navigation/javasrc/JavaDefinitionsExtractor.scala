package ba.sake.basamake.navigation.javasrc

import java.io.InputStream
import com.github.javaparser.{JavaParser, ParseResult}
import com.github.javaparser.ast.*
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.`type`.TypeParameter
import com.typesafe.scalalogging.StrictLogging
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, SymbolUtils}

class JavaDefinitionsExtractor(symbolTable: SymbolTable) extends StrictLogging {

  def extract(name: String, is: InputStream): Unit =
    try {
      val content = new String(is.readAllBytes(), "UTF-8")
      extractFromContent(name, content)
    } catch {
      case NonFatal(e) => logger.warn(s"Failed to parse Java source ${name}: ${e.getMessage}")
    }

  def extractFromContent(fileName: String, content: String): Unit = {
    parse(content) match {
      case Some(cu) => extractCompilationUnit(cu)
      case None     => ()
    }
  }

  private def parse(content: String): Option[CompilationUnit] = {
    val res: ParseResult[CompilationUnit] = new JavaParser().parse(content)
    if (res.getResult.isPresent) Some(res.getResult.get()) else None
  }

  // ── overload counter ─────────────────────────────────────────

  private val ovl = mutable.Map.empty[(String, String), Int]

  private def bumpOvl(owner: String, name: String): Int = {
    val key = (owner, name)
    val idx = ovl.getOrElse(key, 0)
    ovl(key) = idx + 1
    idx
  }

  // ── main traversal ───────────────────────────────────────────

  private def extractCompilationUnit(cu: CompilationUnit): Unit = {
    ovl.clear()
    val pkgOwner = if (cu.getPackageDeclaration.isPresent) {
      val pd = cu.getPackageDeclaration.get()
      SymbolUtils.packageOwner(pd.getNameAsString.split('.').toList)
    } else {
      SymbolUtils.packageOwner(Nil)
    }
    cu.getTypes.asScala.foreach(visitTypeDecl(_, pkgOwner))
  }

  // ── type declaration dispatch ────────────────────────────────

  private def visitTypeDecl(td: TypeDeclaration[?], owner: String): Unit = td match {
    case c: ClassOrInterfaceDeclaration => visitClassOrInterface(c, owner)
    case e: EnumDeclaration             => visitEnum(e, owner)
    case a: AnnotationDeclaration       => visitAnnotation(a, owner)
    case r: RecordDeclaration           => visitRecord(r, owner)
    case _ => () // empty enum constant stmt etc.
  }

  // ── class/interface ──────────────────────────────────────────

  private def visitClassOrInterface(c: ClassOrInterfaceDeclaration, owner: String): Unit = {
    val typeSym = SymbolUtils.typeSymbol(owner, c.getNameAsString)
    addSymbol(typeSym, c.getNameAsString, isType = true)

    // type params
    emitTypeParams(typeSym, c.getTypeParameters)

    // constructors (only for classes, not interfaces)
    val isInterface = c.isInterface
    if (!isInterface) {
      val ctors = c.getConstructors.asScala
      if (ctors.isEmpty) {
        // implicit default no-arg constructor (javaparser doesn't synthesize it)
        val ctorSym = SymbolUtils.constructorSymbol(typeSym, bumpOvl(typeSym, "<init>"))
        addSymbol(ctorSym, "<init>", isType = false)
      } else {
        ctors.foreach { cd =>
          val ctorSym = SymbolUtils.constructorSymbol(typeSym, bumpOvl(typeSym, "<init>"))
          addSymbol(ctorSym, "<init>", isType = false)
          emitParams(ctorSym, cd.getParameters)
        }
      }
    }

    // recurse members
    c.getMembers.asScala.foreach(visitMember(_, typeSym))
  }

  // ── enum ─────────────────────────────────────────────────────

  private def visitEnum(e: EnumDeclaration, owner: String): Unit = {
    val typeSym = SymbolUtils.typeSymbol(owner, e.getNameAsString)
    addSymbol(typeSym, e.getNameAsString, isType = true)

    // enum constants
    e.getEntries.asScala.foreach { en =>
      addSymbol(SymbolUtils.termSymbol(typeSym, en.getNameAsString), en.getNameAsString, isType = false)
    }

    // user-declared constructors
    e.getConstructors.asScala.foreach { cd =>
      val ctorSym = SymbolUtils.constructorSymbol(typeSym, bumpOvl(typeSym, "<init>"))
      addSymbol(ctorSym, "<init>", isType = false)
      emitParams(ctorSym, cd.getParameters)
    }

    // recurse members
    e.getMembers.asScala.foreach(visitMember(_, typeSym))
  }

  // ── annotation ───────────────────────────────────────────────

  private def visitAnnotation(a: AnnotationDeclaration, owner: String): Unit = {
    val typeSym = SymbolUtils.typeSymbol(owner, a.getNameAsString)
    addSymbol(typeSym, a.getNameAsString, isType = true)
    // annotation members are MethodDeclaration
    a.getMembers.asScala.foreach(visitMember(_, typeSym))
  }

  // ── record ───────────────────────────────────────────────────

  private def visitRecord(r: RecordDeclaration, owner: String): Unit = {
    val typeSym = SymbolUtils.typeSymbol(owner, r.getNameAsString)
    addSymbol(typeSym, r.getNameAsString, isType = true)

    // type params
    emitTypeParams(typeSym, r.getTypeParameters)

    // canonical constructor
    val ctorSym = SymbolUtils.constructorSymbol(typeSym, bumpOvl(typeSym, "<init>"))
    addSymbol(ctorSym, "<init>", isType = false)
    emitParams(ctorSym, r.getParameters)

    // Check for user-declared methods with same name as record components before emitting accessors
    val userMethodNames = r.getMembers.asScala.collect {
      case md: MethodDeclaration => md.getNameAsString
    }.toSet

    // synthetic accessors for record components
    r.getParameters.asScala.foreach { p =>
      val compName = p.getNameAsString
      if (!userMethodNames.contains(compName)) {
        bumpOvl(typeSym, compName) // consume slot 0
        val accessorSym = SymbolUtils.methodSymbol(typeSym, compName, 0)
        addSymbol(accessorSym, compName, isType = false)
      }
    }

    // recurse members (includes compact constructors, methods, etc.)
    r.getMembers.asScala.foreach(visitMember(_, typeSym))
  }

  // ── member dispatch ──────────────────────────────────────────

  private def visitMember(m: BodyDeclaration[?], owner: String): Unit = m match {
    case cd: ConstructorDeclaration =>
      // Skip — user-declared ctors are already emitted by visitClassOrInterface/visitEnum
      ()

    case ccd: CompactConstructorDeclaration =>
      // Compact record constructor: emit ctor symbol (no extra params — uses record components)
      val ctorSym = SymbolUtils.constructorSymbol(owner, bumpOvl(owner, "<init>"))
      addSymbol(ctorSym, "<init>", isType = false)

    case amd: AnnotationMemberDeclaration =>
      // Annotation member: emit as method symbol
      val methodSym = SymbolUtils.methodSymbol(owner, amd.getNameAsString, bumpOvl(owner, amd.getNameAsString))
      addSymbol(methodSym, amd.getNameAsString, isType = false)

    case md: MethodDeclaration =>
      val methodSym = SymbolUtils.methodSymbol(owner, md.getNameAsString, bumpOvl(owner, md.getNameAsString))
      addSymbol(methodSym, md.getNameAsString, isType = false)
      emitParams(methodSym, md.getParameters)
      // method-level type params: place at enclosing type symbol owner
      val typeOwner = owner // the enclosing class/interface symbol
      emitTypeParams(typeOwner, md.getTypeParameters)

    case fd: FieldDeclaration =>
      fd.getVariables.asScala.foreach { v =>
        addSymbol(SymbolUtils.termSymbol(owner, v.getNameAsString), v.getNameAsString, isType = false)
      }

    case td: TypeDeclaration[?] =>
      visitTypeDecl(td, owner)

    case _ => () // initializers, blocks, etc.
  }

  // ── helpers ──────────────────────────────────────────────────

  private def addSymbol(symbol: String, shortName: String, isType: Boolean): Unit =
    symbolTable.add(SymbolDefinition(symbol, shortName, isType, None))

  private def emitParams(methodSym: String, params: java.util.List[Parameter]): Unit =
    params.asScala.foreach { p =>
      val n = p.getNameAsString
      if (n.nonEmpty && !n.startsWith(" ")) // skip receiver params
        addSymbol(SymbolUtils.parameterSymbol(methodSym, n), n, isType = false)
    }

  private def emitTypeParams(ownerTypeSym: String, tparams: java.util.List[TypeParameter]): Unit =
    tparams.asScala.foreach { tp =>
      val n = tp.getNameAsString
      if (n.nonEmpty)
        addSymbol(SymbolUtils.typeParamSymbol(ownerTypeSym, n), n, isType = false)
    }
}
