package ba.sake.basamake.navigation

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.*
import munit.FunSuite
import org.eclipse.lsp4j.{Location, Position, Range}

class SemanticdbNavigationIndexTest extends FunSuite {

  private val workspaceRoot = os.pwd / "examples" / "hello" / "scalacli"
  private val semanticdbFile =
    SemanticdbNavigationIndex
      .semanticdbFilesUnder(workspaceRoot)
      .find(_.last == "bla.scala.semanticdb")
      .get
  private val semanticdbRoot = semanticdbFile / os.up / os.up / os.up / os.up
  private val sourceUri = (workspaceRoot / "bla.scala").toNIO.toUri.toString
  private val targetId = "target://bla"

  private def buildServerWith(options: List[String]): BuildServer =
    val handler = new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        method.getName match
          case "buildTargetOutputPaths" =>
            CompletableFuture.completedFuture(
              new OutputPathsResult(
                List(
                  new OutputPathsItem(
                    new BuildTargetIdentifier(targetId),
                    List(
                      new OutputPathItem(semanticdbRoot.toNIO.toUri.toString, OutputPathItemKind.DIRECTORY)
                    ).asJava
                  )
                ).asJava
              )
            )
          case "buildTargetScalacOptions" =>
            CompletableFuture.completedFuture(
              new ScalacOptionsResult(
                List(
                  new ScalacOptionsItem(
                    new BuildTargetIdentifier(targetId),
                    options.asJava,
                    List.empty[String].asJava,
                    semanticdbRoot.toString
                  )
                ).asJava
              )
            )
          case "toString" => "semanticdb-test-build-server"
          case _          => throw new UnsupportedOperationException(method.getName)
    }

    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[BuildServer], classOf[ScalaBuildServer]),
        handler
      )
      .asInstanceOf[BuildServer]

  private def refreshWith(options: List[String]): SemanticdbNavigationIndex =
    val index = new SemanticdbNavigationIndex()
    index.refresh(
      workspaceRoot,
      buildServerWith(options),
      List(targetId),
      Map(targetId -> List(workspaceRoot.toNIO.toUri.toString))
    )
    index

  test("semanticdb index handles Scala 3 flags") {
    val index = refreshWith(List("-Xsemanticdb", "-sourceroot", workspaceRoot.toString, "-semanticdb-target", semanticdbRoot.toString))

    val defLocs = index.definition(sourceUri, new Position(2, 10))
    val refLocs = index.references(sourceUri, new Position(2, 10))
    val symbols = index.documentSymbols(sourceUri)

    assert(defLocs.nonEmpty)
    assertEquals(defLocs.head.getUri, sourceUri)
    assert(refLocs.nonEmpty)
    assertEquals(refLocs.head.getUri, sourceUri)
    assert(symbols.exists(_.isLeft))
    assert(symbols.collect { case e if e.isLeft => e.getLeft.getName }.exists(_.contains("bla")))
  }

  test("semanticdb index handles Scala 2 semanticdb flags") {
    val index = refreshWith(List("-Yrangepos", "-P:semanticdb:sourceroot:" + workspaceRoot.toString, "-P:semanticdb:targetroot:" + semanticdbRoot.toString))

    val defLocs = index.definition(sourceUri, new Position(2, 10))
    val refLocs = index.references(sourceUri, new Position(2, 10))

    assert(defLocs.nonEmpty)
    assertEquals(defLocs.head.getUri, sourceUri)
    assert(refLocs.nonEmpty)
    assertEquals(refLocs.head.getUri, sourceUri)
  }

  test("semanticdb index still works when scalacOptions miss semanticdb flags") {
    val index = refreshWith(List("-deprecation"))
    val defLocs = index.definition(sourceUri, new Position(2, 10))
    assert(defLocs.nonEmpty)
    assertEquals(defLocs.head.getUri, sourceUri)
  }

  test("resolveCandidates handles sbt-style relative source uris") {
    val workspace = os.pwd / "examples" / "hello"
    val sourceRoot = workspace / "sbt" / "src" / "main" / "scala"
    val rel = os.RelPath("src/main/scala/Main.scala")
    val candidates = SemanticdbNavigationIndex.resolveCandidates(workspace, rel, List(sourceRoot))

    assert(candidates.contains(sourceRoot / os.RelPath("Main.scala")))
  }

  test("definition and references resolve across files in same connection") {
    val tmp = os.temp.dir(prefix = "nav-crossfile")
    try
      val index = new SemanticdbNavigationIndex()
      val symbol = "_empty_/utils.getMsg()."

      val mainPath = tmp / "sbt" / "src" / "main" / "scala" / "Main.scala"
      val utilsPath = tmp / "sbt" / "src" / "main" / "scala" / "utils.scala"
      os.makeDir.all(mainPath / os.up)
      os.write(mainPath, "object Main {}")
      os.write(utilsPath, "object utils {}")
      val mainUri = mainPath.toNIO.toUri.toString
      val utilsUri = utilsPath.toNIO.toUri.toString

      val callRange = new Range(new Position(2, 8), new Position(2, 12))
      val defRange = new Range(new Position(2, 8), new Position(2, 14))

      val mainSlice = SemanticdbFileSlice(
        sourceUri = mainUri,
        occurrences = List(SemanticdbOccurrence(symbol, callRange, isDefinition = false)),
        symbolDefinitions = Map.empty,
        symbolReferences = Map(symbol -> List(new Location(mainUri, callRange))),
        documentSymbols = Nil
      )

      val utilsSlice = SemanticdbFileSlice(
        sourceUri = utilsUri,
        occurrences = List(SemanticdbOccurrence(symbol, defRange, isDefinition = true)),
        symbolDefinitions = Map(symbol -> List(new Location(utilsUri, defRange))),
        symbolReferences = Map.empty,
        documentSymbols = Nil
      )

      index.setTargetSlicesForTest("target://sbt-main", Map(mainUri -> mainSlice, utilsUri -> utilsSlice))

      val defs = index.definition(mainUri, new Position(2, 10))
      val refs = index.references(mainUri, new Position(2, 10))

      assertEquals(defs.map(_.getUri), List(utilsUri))
      assert(refs.exists(_.getUri == mainUri))
      assert(refs.exists(_.getUri == utilsUri))
    finally
      os.remove.all(tmp)
  }

  test("definition drops duplicate ghost path entries") {
    val tmp = os.temp.dir(prefix = "nav-existing")
    try
      val index = new SemanticdbNavigationIndex()
      val symbol = "_empty_/utils.getMsg()."

      val mainUri = "file:///ws/examples/hello/sbt/src/main/scala/Main.scala"
      val existingUtilsPath = tmp / "sbt" / "src" / "main" / "scala" / "utils.scala"
      os.makeDir.all(existingUtilsPath / os.up)
      os.write(existingUtilsPath, "object utils {}")
      val existingUtilsUri = existingUtilsPath.toNIO.toUri.toString
      val ghostUtilsUri = existingUtilsUri.replace("/sbt/", "/")

      val callRange = new Range(new Position(2, 8), new Position(2, 12))
      val defRange = new Range(new Position(2, 8), new Position(2, 14))

      val mainSlice = SemanticdbFileSlice(
        sourceUri = mainUri,
        occurrences = List(SemanticdbOccurrence(symbol, callRange, isDefinition = false)),
        symbolDefinitions = Map.empty,
        symbolReferences = Map(symbol -> List(new Location(mainUri, callRange))),
        documentSymbols = Nil
      )

      val defsSlice = SemanticdbFileSlice(
        sourceUri = existingUtilsUri,
        occurrences = List(SemanticdbOccurrence(symbol, defRange, isDefinition = true)),
        symbolDefinitions = Map(symbol -> List(new Location(existingUtilsUri, defRange), new Location(ghostUtilsUri, defRange))),
        symbolReferences = Map.empty,
        documentSymbols = Nil
      )

      index.setTargetSlicesForTest("target://sbt-main", Map(mainUri -> mainSlice, existingUtilsUri -> defsSlice))
      val defs = index.definition(mainUri, new Position(2, 10))

      assertEquals(defs.map(_.getUri), List(existingUtilsUri))
    finally
      os.remove.all(tmp)
  }
}
