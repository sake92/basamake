package ba.sake.basamake.navigation.javasrc

import java.io.InputStream
import scala.compiletime.uninitialized
import com.github.javaparser.{JavaParser, ParseResult}
import com.github.javaparser.Range as JpRange
import com.github.javaparser.ast.*
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.ast.`type`.*
import com.typesafe.scalalogging.StrictLogging
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, SymbolUtils, ResolvedFile, ReferenceOccurrence, ScopeStack, LocalScope, OwnerScope, ImportScopeData}

/** Second pass over a parsed Java source AST that emits reference occurrences.
  * Operates against an already-populated `SymbolTable` of workspace globals.
  *
  * Non-goals (v1): chained method calls, generics/overload-picking, this/super,
  * lambda params, array .length, package-qualified refs beyond prefix.
  */
class JavaReferencesResolver(symbolTable: SymbolTable) extends StrictLogging {

  private var currentPath: os.Path = uninitialized

  def resolve(name: String, is: InputStream, path: os.Path): ResolvedFile =
    try {
      val content = new String(is.readAllBytes(), "UTF-8")
      resolveFromContent(name, content, path)
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Failed to resolve references in ${path}: ${e.getMessage}")
        ResolvedFile(Vector.empty, Vector.empty)
    }

  def resolveFromContent(fileName: String, content: String, path: os.Path): ResolvedFile = {
    currentPath = path
    parse(content) match {
      case Some(cu) => resolveInternal(cu)
      case None => ResolvedFile(Vector.empty, Vector.empty)
    }
  }

  private def parse(content: String): Option[CompilationUnit] = {
    val res: ParseResult[CompilationUnit] = new JavaParser().parse(content)
    if (res.getResult.isPresent) Some(res.getResult.get()) else None
  }

  // ── mutable state ────────────────────────────────────────────

  private val scopeStack = ScopeStack(symbolTable)
  private val occurrences = mutable.ArrayBuffer.empty[ReferenceOccurrence]
  private val locals = mutable.ArrayBuffer.empty[SymbolDefinition]
  private var localIdx: Int = 0
  private var currentOwner: String = "_empty_/"
  private var currentOwnerIsType: Boolean = false
  private var methodDepth: Int = 0

  // ── main traversal ───────────────────────────────────────────

  private def resolveInternal(cu: CompilationUnit): ResolvedFile = {
    occurrences.clear()
    locals.clear()
    localIdx = 0
    methodDepth = 0

    val pkgOwner = cu.getPackageDeclaration.toScala
      .map(pd => SymbolUtils.packageOwner(pd.getNameAsString.split('.').toList))
      .getOrElse(SymbolUtils.packageOwner(Nil))
    currentOwner = pkgOwner
    currentOwnerIsType = false

    // emit def for package
    cu.getPackageDeclaration.toScala.foreach { pd =>
    }

    scopeStack.push(OwnerScope(pkgOwner))

    // imports (file-scoped, never popped)
    cu.getImports.asScala.foreach { imp =>
      scopeStack.push(JavaImports.parse(imp))
    }

    // top-level types
    cu.getTypes.asScala.foreach(resolveTypeDecl(_, pkgOwner))

    ResolvedFile(occurrences.toVector, locals.toVector)
  }

  // ── emit helpers ─────────────────────────────────────────────

  private def emitRefRange(optRange: java.util.Optional[JpRange], symbol: String): Unit =
    if (optRange.isPresent) {
      occurrences += ReferenceOccurrence(symbol, JavaPositionUtils.toRange(optRange.get()))
    }

  private def emitRefUnresolvedRange(optRange: java.util.Optional[JpRange]): Unit =
    if (optRange.isPresent) {
      occurrences += ReferenceOccurrence("", JavaPositionUtils.toRange(optRange.get()))
    }

  private def addLocalRange(optRange: java.util.Optional[JpRange], symbol: String, shortName: String, isType: Boolean): Unit =
    if (optRange.isPresent) {
      locals += SymbolDefinition(symbol, shortName, isType, JavaPositionUtils.toRange(optRange.get()), currentPath)
    }

  private def nextLocalSymbol(): String = {
    val sym = SymbolUtils.localSymbol(localIdx)
    localIdx += 1
    sym
  }

  // ── lookup chain ─────────────────────────────────────────────

  private def lookup(name: String, isType: Boolean, inCallContext: Boolean): Option[String] =
    scopeStack.lookup(name, isType, inCallContext)
      .orElse(JavaLangSymbols.lookup(name, isType))
      .orElse {
        if (isType) {
          val sym = SymbolUtils.typeSymbol("_empty_/", name)
          if (symbolTable.get(sym).isDefined) Some(sym) else None
        } else {
          val sym = SymbolUtils.termSymbol("_empty_/", name)
          if (symbolTable.get(sym).isDefined) Some(sym) else None
        }
      }

  // ── withOwner helper ─────────────────────────────────────────

  private def withOwner[T](typeSym: String, isType: Boolean, name: String)(body: => T): T = {
    val oldOwner = currentOwner
    val oldIsType = currentOwnerIsType
    currentOwner = typeSym
    currentOwnerIsType = isType

    scopeStack.push(OwnerScope(typeSym))
    scopeStack.push(LocalScope(collection.mutable.Map(name -> typeSym)))
    val result = body
    scopeStack.pop()
    scopeStack.pop()

    currentOwner = oldOwner
    currentOwnerIsType = oldIsType
    result
  }

  // ── type declaration dispatch ────────────────────────────────

  private def resolveTypeDecl(td: TypeDeclaration[?], owner: String): Unit = td match {
    case c: ClassOrInterfaceDeclaration => resolveClassOrInterface(c, owner)
    case e: EnumDeclaration             => resolveEnum(e, owner)
    case a: AnnotationDeclaration       => resolveAnnotation(a, owner)
    case r: RecordDeclaration           => resolveRecord(r, owner)
    case _ => ()
  }

  // ── class/interface ──────────────────────────────────────────

  private def resolveClassOrInterface(c: ClassOrInterfaceDeclaration, owner: String): Unit = {
    val typeSym = SymbolUtils.typeSymbol(owner, c.getNameAsString)

    // type params as locals
    c.getTypeParameters.asScala.foreach { tp =>
      val localSym = nextLocalSymbol()
      addLocalRange(tp.getName.getRange, localSym, tp.getNameAsString, isType = false)
      val globalTp = SymbolUtils.typeParamSymbol(typeSym, tp.getNameAsString)
      scopeStack.push(LocalScope(collection.mutable.Map(tp.getNameAsString -> localSym)))
    }

    // ctor def
    val ctorSym = SymbolUtils.constructorSymbol(typeSym, 0)

    // ctor params (from first constructor or implicit)
    val ctors = c.getConstructors.asScala
    if (ctors.nonEmpty) {
      ctors.head.getParameters.asScala.foreach { p =>
      }
    }

    // recurse members
    withOwner(typeSym, isType = true, c.getNameAsString) {
      c.getMembers.asScala.foreach(resolveMember(_, typeSym))
    }

    // pop type param scopes
    (0 until c.getTypeParameters.size()).foreach(_ => scopeStack.pop())
  }

  // ── enum ─────────────────────────────────────────────────────

  private def resolveEnum(e: EnumDeclaration, owner: String): Unit = {
    val typeSym = SymbolUtils.typeSymbol(owner, e.getNameAsString)

    e.getEntries.asScala.foreach { en =>
      val termSym = SymbolUtils.termSymbol(typeSym, en.getNameAsString)
    }

    withOwner(typeSym, isType = true, e.getNameAsString) {
      e.getMembers.asScala.foreach(resolveMember(_, typeSym))
    }
  }

  // ── annotation ───────────────────────────────────────────────

  private def resolveAnnotation(a: AnnotationDeclaration, owner: String): Unit = {
    val typeSym = SymbolUtils.typeSymbol(owner, a.getNameAsString)
    withOwner(typeSym, isType = true, a.getNameAsString) {
      a.getMembers.asScala.foreach(resolveMember(_, typeSym))
    }
  }

  // ── record ───────────────────────────────────────────────────

  private def resolveRecord(r: RecordDeclaration, owner: String): Unit = {
    val typeSym = SymbolUtils.typeSymbol(owner, r.getNameAsString)

    // type params
    r.getTypeParameters.asScala.foreach { tp =>
      val localSym = nextLocalSymbol()
      addLocalRange(tp.getName.getRange, localSym, tp.getNameAsString, isType = false)
    }

    // canonical ctor
    val ctorSym = SymbolUtils.constructorSymbol(typeSym, 0)
    r.getParameters.asScala.foreach { p =>
    }

    // synth accessors (skip if user method same name)
    val userMethodNames = r.getMembers.asScala.collect { case md: MethodDeclaration => md.getNameAsString }.toSet
    r.getParameters.asScala.foreach { p =>
      val cn = p.getNameAsString
      if (!userMethodNames.contains(cn)) {
      }
    }

    withOwner(typeSym, isType = true, r.getNameAsString) {
      r.getMembers.asScala.foreach(resolveMember(_, typeSym))
    }

    (0 until r.getTypeParameters.size()).foreach(_ => scopeStack.pop())
  }

  // ── member dispatch ──────────────────────────────────────────

  private def resolveMember(m: BodyDeclaration[?], owner: String): Unit = m match {
    case md: MethodDeclaration =>
      resolveMethod(md, owner)
    case _: ConstructorDeclaration => ()
    case ccd: CompactConstructorDeclaration =>
      withMethodScope {
        ccd.getBody.getStatements.asScala.foreach(resolveStmt)
      }
    case fd: FieldDeclaration =>
      fd.getVariables.asScala.foreach { v =>
        val termSym = SymbolUtils.termSymbol(owner, v.getNameAsString)
        // resolve field type
        resolveTypeRef(v.getType)
        v.getInitializer.toScala.foreach(e => resolveExpr(e, isType = false, inCallContext = false))
      }
    case amd: AnnotationMemberDeclaration =>
      val methodSym = SymbolUtils.methodSymbol(owner, amd.getNameAsString, 0)
    case td: TypeDeclaration[?] =>
      resolveTypeDecl(td, owner)
    case _ => ()
  }

  // ── method ───────────────────────────────────────────────────

  private def resolveMethod(md: MethodDeclaration, owner: String): Unit = {
    val methodSym = SymbolUtils.methodSymbol(owner, md.getNameAsString, 0)

    md.getParameters.asScala.foreach { p =>
    }

    md.getTypeParameters.asScala.foreach { tp =>
      val localSym = nextLocalSymbol()
      addLocalRange(tp.getName.getRange, localSym, tp.getNameAsString, isType = false)
    }

    md.getBody.toScala.foreach { body =>
      withMethodScope {
        // bind params
        md.getParameters.asScala.foreach { p =>
          scopeStack.addLocalBinding(p.getNameAsString, SymbolUtils.parameterSymbol(methodSym, p.getNameAsString))
        }
        methodDepth += 1
        body.getStatements.asScala.foreach(resolveStmt)
        methodDepth -= 1
      }
    }
  }

  // ── method scope ─────────────────────────────────────────────

  private def withMethodScope[T](body: => T): T = {
    scopeStack.push(LocalScope(collection.mutable.Map.empty[String, String]))
    val result = body
    scopeStack.pop()
    result
  }

  // ── statement dispatch ───────────────────────────────────────

  private def resolveStmt(s: Statement): Unit = s match {
    case es: ExpressionStmt =>
      resolveExpr(es.getExpression, isType = false, inCallContext = false)
    case bs: BlockStmt =>
      scopeStack.push(LocalScope(collection.mutable.Map.empty[String, String]))
      bs.getStatements.asScala.foreach(resolveStmt)
      scopeStack.pop()
    case is: IfStmt =>
      resolveExpr(is.getCondition, isType = false, inCallContext = false)
      resolveStmt(is.getThenStmt)
      is.getElseStmt.toScala.foreach(resolveStmt)
    case _: ForStmt | _: ForEachStmt | _: WhileStmt | _: DoStmt =>
      () // skip loops in v1 for simplicity
    case ss: SwitchStmt =>
      resolveExpr(ss.getSelector, isType = false, inCallContext = false)
      ss.getEntries.asScala.foreach(e => e.getStatements.asScala.foreach(resolveStmt))
    case _: TryStmt => ()
    case rs: ReturnStmt =>
      rs.getExpression.toScala.foreach(e => resolveExpr(e, isType = false, inCallContext = false))
    case ths: ThrowStmt =>
      resolveExpr(ths.getExpression, isType = false, inCallContext = false)
    case lcd: LocalClassDeclarationStmt =>
      resolveTypeDecl(lcd.getClassDeclaration, currentOwner)
    case _: BreakStmt | _: ContinueStmt | _: EmptyStmt => ()
    case _ => ()
  }

  // ── expression dispatch ──────────────────────────────────────

  private def resolveExpr(e: Expression, isType: Boolean, inCallContext: Boolean): Unit = e match {
    case ne: NameExpr =>
      resolveNameExpr(ne, inCallContext)
    case fa: FieldAccessExpr =>
      resolveFieldAccess(fa)
    case mc: MethodCallExpr =>
      resolveMethodCall(mc)
    case oc: ObjectCreationExpr =>
      resolveObjectCreation(oc)
    case _: ClassExpr => () // Foo.class — skip v1
    case ae: AssignExpr =>
      resolveExpr(ae.getTarget, isType = false, inCallContext = false)
      resolveExpr(ae.getValue, isType = false, inCallContext = false)
    case be: BinaryExpr =>
      resolveExpr(be.getLeft, isType = false, inCallContext = false)
      resolveExpr(be.getRight, isType = false, inCallContext = false)
    case ue: UnaryExpr =>
      resolveExpr(ue.getExpression, isType = false, inCallContext = false)
    case vde: VariableDeclarationExpr =>
      vde.getVariables.asScala.foreach { vd =>
        val localSym = nextLocalSymbol()
        addLocalRange(vd.getName.getRange, localSym, vd.getNameAsString, isType = false)
        scopeStack.addLocalBinding(vd.getNameAsString, localSym)
        vd.getInitializer.toScala.foreach(init => resolveExpr(init, isType = false, inCallContext = false))
      }
    case ce: ConditionalExpr =>
      resolveExpr(ce.getCondition, isType = false, inCallContext = false)
      resolveExpr(ce.getThenExpr, isType = false, inCallContext = false)
      resolveExpr(ce.getElseExpr, isType = false, inCallContext = false)
    case _: ArrayAccessExpr | _: ArrayCreationExpr => () // skip v1
    case cast: CastExpr =>
      resolveTypeRef(cast.getType)
      resolveExpr(cast.getExpression, isType = false, inCallContext = false)
    case ioe: InstanceOfExpr =>
      resolveTypeRef(ioe.getType)
      resolveExpr(ioe.getExpression, isType = false, inCallContext = false)
    case ep: EnclosedExpr =>
      resolveExpr(ep.getInner, isType, inCallContext)
    case _: ThisExpr | _: SuperExpr => () // skip v1
    case le: LambdaExpr =>
      // recurse body only, skip params
      le.getBody match
        case es: ExpressionStmt => resolveExpr(es.getExpression, isType = false, inCallContext = false)
        case bs: BlockStmt => bs.getStatements.asScala.foreach(resolveStmt)
        case _ => ()
    case _: MethodReferenceExpr => () // skip v1
    case _ => ()
  }

  // ── name expression ──────────────────────────────────────────

  private def resolveNameExpr(ne: NameExpr, inCallContext: Boolean): Unit = {
    lookup(ne.getNameAsString, isType = false, inCallContext = inCallContext) match {
      case Some(sym) => emitRefRange(ne.getName.getRange, sym)
      case None => emitRefUnresolvedRange(ne.getName.getRange)
    }
  }

  // ── field access ─────────────────────────────────────────────

  private def resolveFieldAccess(fa: FieldAccessExpr): Unit = {
    resolveExpr(fa.getScope, isType = false, inCallContext = false)
    val ownerOpt = resolveTermToOwner(fa.getScope)
    val name = fa.getNameAsString
    ownerOpt match {
      case Some(owner) =>
        resolveMemberOf(owner, name, isType = false, inCallContext = false) match {
          case Some(sym) => emitRefRange(fa.getName.getRange, sym)
          case None => emitRefUnresolvedRange(fa.getName.getRange)
        }
      case None =>
        emitRefUnresolvedRange(fa.getName.getRange)
    }
  }

  // ── method call ──────────────────────────────────────────────

  private def resolveMethodCall(mc: MethodCallExpr): Unit = {
    val methodName = mc.getNameAsString
    mc.getScope.toScala match {
      case Some(scope) =>
        resolveExpr(scope, isType = false, inCallContext = false)
        val ownerOpt = resolveTermToOwner(scope)
        ownerOpt match {
          case Some(owner) =>
            resolveMemberOf(owner, methodName, isType = false, inCallContext = true) match {
              case Some(sym) => emitRefRange(mc.getName.getRange, sym)
              case None => emitRefUnresolvedRange(mc.getName.getRange)
            }
          case None =>
            emitRefUnresolvedRange(mc.getName.getRange)
        }
      case None =>
        lookup(methodName, isType = false, inCallContext = true) match {
          case Some(sym) => emitRefRange(mc.getName.getRange, sym)
          case None => emitRefUnresolvedRange(mc.getName.getRange)
        }
    }
    mc.getArguments.asScala.foreach(a => resolveExpr(a, isType = false, inCallContext = false))
  }

  // ── new expression ───────────────────────────────────────────

  private def resolveObjectCreation(oc: ObjectCreationExpr): Unit = {
    val typeRef = oc.getType
    resolveTypeRef(typeRef)

    val name = typeRef.getNameAsString
    val ownerOpt = resolveTypeRefToOwner(typeRef)
    ownerOpt.foreach { owner =>
      val ctorSym = SymbolUtils.constructorSymbol(owner, 0)
      if (symbolTable.get(ctorSym).isDefined) {
        emitRefRange(typeRef.getName.getRange, ctorSym)
      }
    }

    oc.getArguments.asScala.foreach(a => resolveExpr(a, isType = false, inCallContext = false))
  }

  // ── type reference ───────────────────────────────────────────

  private def resolveTypeRef(t: Type): Unit = t match {
    case cit: ClassOrInterfaceType =>
      val name = cit.getNameAsString
      cit.getScope.toScala match {
        case Some(qual) =>
          resolveTypeRef(qual)
          val ownerOpt = resolveTypeRefToOwner(qual)
          ownerOpt match {
            case Some(owner) =>
              val typeSym = SymbolUtils.typeSymbol(owner, name)
              if (symbolTable.get(typeSym).isDefined)
                emitRefRange(cit.getName.getRange, typeSym)
              else
                emitRefUnresolvedRange(cit.getName.getRange)
            case None =>
              emitRefUnresolvedRange(cit.getName.getRange)
          }
        case None =>
          lookup(name, isType = true, inCallContext = false) match {
            case Some(sym) => emitRefRange(cit.getName.getRange, sym)
            case None => emitRefUnresolvedRange(cit.getName.getRange)
          }
      }
      cit.getTypeArguments.toScala.foreach(_.asScala.foreach(resolveTypeRef))
    case _ => ()
  }

  // ── resolve type ref to owner prefix ─────────────────────────

  private def resolveTypeRefToOwner(t: Type): Option[String] = t match {
    case cit: ClassOrInterfaceType =>
      val name = cit.getNameAsString
      cit.getScope.toScala match {
        case Some(qual) =>
          resolveTypeRefToOwner(qual).map(owner => SymbolUtils.typeSymbol(owner, name))
        case None =>
          lookup(name, isType = true, inCallContext = false)
            .orElse(Some(SymbolUtils.packageOwner(List(name))))
      }
    case _ => None
  }

  // ── resolve term to owner ────────────────────────────────────

  private def resolveTermToOwner(e: Expression): Option[String] = e match {
    case ne: NameExpr =>
      lookup(ne.getNameAsString, isType = false, inCallContext = false)
        .orElse(JavaLangSymbols.rawLookup(ne.getNameAsString))
        .orElse {
          List(
            SymbolUtils.termSymbol("_empty_/", ne.getNameAsString),
            SymbolUtils.packageOwner(List(ne.getNameAsString))
          ).find(c => symbolTable.get(c).isDefined)
        }
        .orElse(Some(SymbolUtils.packageOwner(List(ne.getNameAsString))))
    case fa: FieldAccessExpr =>
      resolveTermToOwner(fa.getScope).map(o => SymbolUtils.termSymbol(o, fa.getNameAsString))
    case _ => None
  }

  // ── resolve member of owner ──────────────────────────────────

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
}
