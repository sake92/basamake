package ba.sake.basamake.navigation

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*
import ch.epfl.scala.bsp4j.*
import munit.FunSuite
import org.eclipse.lsp4j.{Location, Position, Range}

class NavigationIndexTest extends FunSuite {

  // TODO use temp dir, copy from resources..
  private val workspaceRoot = os.pwd / "examples/hello/scalacli"
  private val sourceUri = (workspaceRoot / "bla.scala").toNIO.toUri.toString
  private val targetId = new BuildTargetIdentifier("target://bla")

  private def buildServerWith(options: List[String], dependencySourceUris: List[String] = Nil): BuildServer =
    val handler = new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        method.getName match
          case "buildTargetOutputPaths" =>
            CompletableFuture.completedFuture(
              new OutputPathsResult(
                List(
                  new OutputPathsItem(
                    targetId,
                    List(
                      new OutputPathItem(workspaceRoot.toNIO.toUri.toString, OutputPathItemKind.DIRECTORY)
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
                    targetId,
                    options.asJava,
                    List.empty[String].asJava,
                    workspaceRoot.toString
                  )
                ).asJava
              )
            )
          case "buildTargetDependencySources" =>
            CompletableFuture.completedFuture(
              new DependencySourcesResult(
                List(
                  new DependencySourcesItem(
                    targetId,
                    dependencySourceUris.asJava
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

  private def refreshWith(
      options: List[String],
      dependencySourceUris: List[String] = List(sourceUri)
  ): NavigationIndex =
    val index = new NavigationIndex()
    index.refresh(
      workspaceRoot,
      buildServerWith(options, dependencySourceUris),
      List(targetId),
      Map(targetId -> List(workspaceRoot.toNIO.toUri.toString)),
      Map(targetId -> dependencySourceUris)
    )
    index

  test("semanticdbTargetPaths parses Scala 3 -semanticdb-target") {
    val paths = SemanticdbIndexing.semanticdbTargetPaths(
      List("-Xsemanticdb", "-semanticdb-target:/tmp/custom/output")
    )
    assertEquals(paths, List(os.Path("/tmp/custom/output")))
  }

  test("semanticdbTargetPaths parses Scala 2 -P:semanticdb:targetroot:") {
    val paths = SemanticdbIndexing.semanticdbTargetPaths(
      List("-P:semanticdb:targetroot:/tmp/custom-s2")
    )
    assertEquals(paths, List(os.Path("/tmp/custom-s2")))
  }

  test("semanticdbTargetPaths returns empty when no target flags present") {
    val paths = SemanticdbIndexing.semanticdbTargetPaths(
      List("-Xsemanticdb", "-deprecation")
    )
    assertEquals(paths, Nil)
  }

  test("semanticdbTargetPaths handles multiple target flags") {
    val paths = SemanticdbIndexing.semanticdbTargetPaths(
      List("-Xsemanticdb", "-semanticdb-target:/tmp/out1", "-P:semanticdb:targetroot:/tmp/out2")
    )
    assertEquals(paths, List(os.Path("/tmp/out1"), os.Path("/tmp/out2")))
  }

  test("semanticdb index handles Scala 3 flags") {
    val index = refreshWith(List("-Xsemanticdb", "-sourceroot", workspaceRoot.toString, s"-semanticdb-target:${workspaceRoot.toString}"))

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
    val index = refreshWith(List("-Yrangepos", "-P:semanticdb:sourceroot:" + workspaceRoot.toString, "-P:semanticdb:targetroot:" + workspaceRoot.toString))

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

  test("dependency source jar indexes definitions") {
    val tmp = os.temp.dir(prefix = "nav-depsrc")
    try
      val depRoot = workspaceRoot / ".basamake" / "dependency-sources"
      if os.exists(depRoot) then os.remove.all(depRoot)
      val jarPath = tmp / "upickle-sources.jar"
      val entryContent =
        "object upickle { def name: String = \"upickle\" }"
      val jarFile = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(jarPath.toIO))
      try
        val entry = new java.util.zip.ZipEntry("upickle.scala")
        jarFile.putNextEntry(entry)
        jarFile.write(entryContent.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        jarFile.closeEntry()
      finally jarFile.close()

      val jarUri = s"jar:${jarPath.toNIO.toUri.toString}!/upickle.scala"
      val index = refreshWith(List("-deprecation"), List(jarUri))
      val extracted = os.walk(depRoot).find(_.last == "upickle.scala").get
      val extractedUri = extracted.toNIO.toUri.toString
      val defs = index.definition(extractedUri, new Position(0, 7))

      assertEquals(defs.map(_.getUri), List(extractedUri))
      assert(extracted.toString.matches(".*dependency-sources/[0-9a-f]{8}/upickle\\.scala$"))
      assert(defs.forall(_.getUri.startsWith("file:")))
    finally
      os.remove.all(tmp)
  }

  test("dependency source jar extraction uses gav plus hash when maven path is present") {
    val tmp = os.temp.dir(prefix = "nav-depsrc-gav")
    try
      val depRoot = workspaceRoot / ".basamake" / "dependency-sources"
      if os.exists(depRoot) then os.remove.all(depRoot)
      val jarPath = tmp / "maven2" / "com" / "lihaoyi" / "upickle_3" / "4.0.0" / "upickle_3-4.0.0-sources.jar"
      os.makeDir.all(jarPath / os.up)
      val jarFile = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(jarPath.toIO))
      try
        val entry = new java.util.zip.ZipEntry("upickle/Api.scala")
        jarFile.putNextEntry(entry)
        jarFile.write("object Api { def rw = 1 }".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        jarFile.closeEntry()
      finally jarFile.close()

      val jarUri = s"jar:${jarPath.toNIO.toUri.toString}!/upickle/Api.scala"
      val index = refreshWith(List("-deprecation"), List(jarUri))
      val extracted = os.walk(depRoot).find(_.last == "Api.scala").get
      val extractedUri = extracted.toNIO.toUri.toString
      val defs = index.definition(extractedUri, new Position(0, 8))

      assert(defs.nonEmpty)
      assertEquals(defs.head.getUri, extractedUri)
      assert(extracted.toString.matches(".*dependency-sources/com\\.lihaoyi-upickle_3-4\\.0\\.0-[0-9a-f]{8}/upickle/Api\\.scala$"))
    finally
      os.remove.all(tmp)
  }

  test("dependency source jar resolves go-to-def from workspace symbol") {
    val tmp = os.temp.dir(prefix = "nav-depsrc-link")
    try
      val mainPath = tmp / "Main.scala"
      val depPath = tmp / "upickle.scala"
      os.write(mainPath, "object Main { val x = upickle }")
      os.write(depPath, "object upickle { def name: String = \"upickle\" }")
      val workspaceUri = mainPath.toNIO.toUri.toString
      val depUri = depPath.toNIO.toUri.toString
      val defRange = new Range(new Position(0, 7), new Position(0, 14))
      val callRange = new Range(new Position(0, 22), new Position(0, 29))

      val index = new NavigationIndex()
      index.setTargetSlicesForTest(
        targetId,
        Map(
          workspaceUri ->
            SemanticdbFileSlice(
              sourceUri = workspaceUri,
              occurrences = List(SemanticdbOccurrence("upickle", callRange, isDefinition = false)),
              symbolDefinitions = Map("upickle" -> List(new Location(workspaceUri, callRange))),
              symbolReferences = Map("upickle" -> List(new Location(workspaceUri, callRange))),
              documentSymbols = Nil
            )
        )
      )
      index.setTargetDependencySlicesForTest(
        targetId,
        List(
          SemanticdbFileSlice(
            sourceUri = depUri,
            occurrences = List(SemanticdbOccurrence("upickle", defRange, isDefinition = true)),
            symbolDefinitions = Map("upickle" -> List(new Location(depUri, defRange))),
            symbolReferences = Map.empty,
            documentSymbols = Nil
          )
        )
      )

      val defs = index.definition(workspaceUri, new Position(0, 24))
      assertEquals(defs.map(_.getUri), List(workspaceUri))
    finally
      os.remove.all(tmp)
  }

  test("dependency source order picks first hit") {
    val tmp = os.temp.dir(prefix = "nav-depsrc-order")
    try
      val mainPath = tmp / "Main.scala"
      os.write(mainPath, "object Main { val x = upickle }")
      val workspaceUri = mainPath.toNIO.toUri.toString
      val dep1Path = tmp / "lib1.scala"
      val dep2Path = tmp / "lib2.scala"
      os.write(dep1Path, "object upickle { def one = 1 }")
      os.write(dep2Path, "object upickle { def two = 2 }")
      val dep1 = dep1Path.toNIO.toUri.toString
      val dep2 = dep2Path.toNIO.toUri.toString
      val callRange = new Range(new Position(0, 22), new Position(0, 29))
      val defRange1 = new Range(new Position(0, 7), new Position(0, 14))
      val defRange2 = new Range(new Position(0, 7), new Position(0, 14))

      val index = new NavigationIndex()
      index.setTargetSlicesForTest(
        targetId,
        Map(
          workspaceUri ->
            SemanticdbFileSlice(
              sourceUri = workspaceUri,
              occurrences = List(SemanticdbOccurrence("upickle", callRange, isDefinition = false)),
              symbolDefinitions = Map.empty,
              symbolReferences = Map("upickle" -> List(new Location(workspaceUri, callRange))),
              documentSymbols = Nil
            )
        )
      )
      index.setTargetDependencySlicesForTest(
        targetId,
        List(
          SemanticdbFileSlice(
            sourceUri = dep1,
            occurrences = List(SemanticdbOccurrence("upickle", defRange1, isDefinition = true)),
            symbolDefinitions = Map("upickle" -> List(new Location(dep1, defRange1))),
            symbolReferences = Map.empty,
            documentSymbols = Nil
          ),
          SemanticdbFileSlice(
            sourceUri = dep2,
            occurrences = List(SemanticdbOccurrence("upickle", defRange2, isDefinition = true)),
            symbolDefinitions = Map("upickle" -> List(new Location(dep2, defRange2))),
            symbolReferences = Map.empty,
            documentSymbols = Nil
          )
        )
      )
      val defs = index.definition(workspaceUri, new Position(0, 24))
      assertEquals(defs.map(_.getUri), List(dep1))
      assertEquals(defs.map(_.getUri), List(dep1))
    finally
      os.remove.all(tmp)
  }

  test("resolveCandidates handles sbt-style relative source uris") {
    val workspace = os.pwd / "examples" / "hello"
    val sourceRoot = workspace / "sbt" / "src" / "main" / "scala"
    val rel = os.RelPath("src/main/scala/Main.scala")
    val candidates = SemanticdbIndexing.resolveCandidates(workspace, rel, List(sourceRoot))

    assert(candidates.contains(sourceRoot / os.RelPath("Main.scala")))
  }

  test("definition and references resolve across files in same connection") {
    val tmp = os.temp.dir(prefix = "nav-crossfile")
    try
      val index = new NavigationIndex()
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

      index.setTargetSlicesForTest(new BuildTargetIdentifier("target://sbt-main"), Map(mainUri -> mainSlice, utilsUri -> utilsSlice))

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
      val index = new NavigationIndex()
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

      index.setTargetSlicesForTest(new BuildTargetIdentifier("target://sbt-main"), Map(mainUri -> mainSlice, existingUtilsUri -> defsSlice))
      val defs = index.definition(mainUri, new Position(2, 10))

      assertEquals(defs.map(_.getUri), List(existingUtilsUri))
    finally
      os.remove.all(tmp)
  }

  test("dependency source go-to-def resolves nested member via ownerName") {
    val tmp = os.temp.dir(prefix = "nav-ownername")
    try
      val index = new NavigationIndex()
      val workspaceUri = "file:///ws/Main.scala"
      val depUri = "file:///ws/dep/Foo.scala"

      // SemanticDB symbol from workspace: Foo.bar().
      val semanticdbSymbol = "Foo.bar()."
      val callRange = new Range(new Position(2, 4), new Position(2, 7))
      val defRange = new Range(new Position(4, 6), new Position(4, 9))

      // Dependency slice uses ownerName as key (e.g. Foo.bar)
      val depSlice = SemanticdbFileSlice(
        sourceUri = depUri,
        occurrences = List(
          SemanticdbOccurrence("pkg/Foo.bar", defRange, isDefinition = true),
          SemanticdbOccurrence("Foo.bar", defRange, isDefinition = true)
        ),
        symbolDefinitions = Map(
          "pkg/Foo.bar" -> List(new Location(depUri, defRange)),
          "Foo.bar" -> List(new Location(depUri, defRange)),
          "bar" -> List(new Location(depUri, defRange))
        ),
        symbolReferences = Map.empty,
        documentSymbols = Nil
      )

      val workspaceSlice = SemanticdbFileSlice(
        sourceUri = workspaceUri,
        occurrences = List(SemanticdbOccurrence(semanticdbSymbol, callRange, isDefinition = false)),
        symbolDefinitions = Map.empty,
        symbolReferences = Map(semanticdbSymbol -> List(new Location(workspaceUri, callRange))),
        documentSymbols = Nil
      )

      index.setTargetSlicesForTest(targetId, Map(workspaceUri -> workspaceSlice))
      index.setTargetDependencySlicesForTest(targetId, List(depSlice))

      val defs = index.definition(workspaceUri, new Position(2, 5))
      // candidateSymbolKeys("Foo.bar().") produces ["Foo", "Foo.bar"]
      // "Foo.bar" matches the ownerName key in the dependency slice
      assertEquals(defs.map(_.getUri), List(depUri))
    finally
      os.remove.all(tmp)
  }

  test("dependency source go-to-def disambiguates same-named methods in different classes") {
    val tmp = os.temp.dir(prefix = "nav-disambig")
    try
      val index = new NavigationIndex()
      val workspaceUri = "file:///ws/Main.scala"
      val fooUri = "file:///ws/dep/Foo.scala"
      val bazUri = "file:///ws/dep/Baz.scala"

      val callRange = new Range(new Position(2, 4), new Position(2, 7))
      val fooDefRange = new Range(new Position(3, 6), new Position(3, 9))
      val bazDefRange = new Range(new Position(3, 6), new Position(3, 9))

      // Workspace references Foo.bar()
      val workspaceSymbol = "Foo.bar()."
      val workspaceSlice = SemanticdbFileSlice(
        sourceUri = workspaceUri,
        occurrences = List(SemanticdbOccurrence(workspaceSymbol, callRange, isDefinition = false)),
        symbolDefinitions = Map.empty,
        symbolReferences = Map(workspaceSymbol -> List(new Location(workspaceUri, callRange))),
        documentSymbols = Nil
      )

      // Foo.scala has method bar
      val fooSlice = SemanticdbFileSlice(
        sourceUri = fooUri,
        occurrences = List(SemanticdbOccurrence("Foo.bar", fooDefRange, isDefinition = true)),
        symbolDefinitions = Map(
          "Foo.bar" -> List(new Location(fooUri, fooDefRange)),
          "bar" -> List(new Location(fooUri, fooDefRange))
        ),
        symbolReferences = Map.empty,
        documentSymbols = Nil
      )

      // Baz.scala also has method bar (should NOT be selected)
      val bazSlice = SemanticdbFileSlice(
        sourceUri = bazUri,
        occurrences = List(SemanticdbOccurrence("Baz.bar", bazDefRange, isDefinition = true)),
        symbolDefinitions = Map(
          "Baz.bar" -> List(new Location(bazUri, bazDefRange)),
          "bar" -> List(new Location(bazUri, bazDefRange))
        ),
        symbolReferences = Map.empty,
        documentSymbols = Nil
      )

      index.setTargetSlicesForTest(targetId, Map(workspaceUri -> workspaceSlice))
      index.setTargetDependencySlicesForTest(targetId, List(fooSlice, bazSlice))

      val defs = index.definition(workspaceUri, new Position(2, 5))
      // candidateSymbolKeys("Foo.bar().") → ["Foo", "Foo.bar"]
      // "Foo.bar" matches Foo slice EXACTLY, not Baz.bar
      // Also, firstDefinition picks FIRST match, which is Foo (before Baz)
      assertEquals(defs.map(_.getUri), List(fooUri))
    finally
      os.remove.all(tmp)
  }
}
