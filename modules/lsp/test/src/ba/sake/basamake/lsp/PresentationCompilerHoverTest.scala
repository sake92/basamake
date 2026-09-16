package ba.sake.basamake.lsp

import java.net.URI
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import ba.sake.basamake.bsp.ScalaPresentationTarget
import ba.sake.basamake.index.{InMemorySymbolTable, SymbolDefinition}
import ba.sake.basamake.index.indexing.{Fingerprint, IndexedSymbolTable, SourceJarIndexer, WorkspaceIndex}
import scala.meta.internal.semanticdb.Range
import java.io.FileOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

class PresentationCompilerHoverTest extends FunSuite {
  private val scalaLibrary = os.Path(classOf[scala.Option[?]].getProtectionDomain.getCodeSource.getLocation.toURI)

  test("hover reports an inferred Scala 3 val type through an isolated compiler") {
    val target = ScalaPresentationTarget(
      id = "poc",
      scalaVersion = "3.7.4",
      classpath = List(scalaLibrary),
      options = Nil,
      sourcePaths = Nil
    )
    val hover = new PresentationCompilerHover(os.pwd)
    try {
      val result = hover.hover(
        target,
        URI.create("file:///PresentationCompilerHoverPoc.scala"),
        "object Main:\n  val answer = 42\n",
        line = 1,
        character = 6
      )
      assert(result.exists(_.toString.contains("Int")), clues(result))
    } finally hover.shutdown()
  }

  test("hover reports an inferred Scala 2 val type through mtags") {
    val target = ScalaPresentationTarget(
      id = "scala-2-poc",
      scalaVersion = "2.13.16",
      classpath = List(scalaLibrary),
      options = Nil,
      sourcePaths = Nil
    )
    val hover = new PresentationCompilerHover(os.pwd)
    try {
      val result = hover.hover(
        target,
        URI.create("file:///Scala2PresentationCompilerHoverPoc.scala"),
        "object Main { val answer = 42 }\n",
        line = 0,
        character = 18
      )
      assert(result.exists(_.toString.contains("Int")), clues(result))
    } finally hover.shutdown()
  }

  test("hover includes Scaladoc for an indexed workspace definition") {
    val source = os.temp.dir() / "Documented.scala"
    val text = """object Main:
      |  /**
      |   * The answer to everything.
      |   *
      |   * === Heading ===
      |   * [[scala.Int]]
      |   */
      |  val answer = 42
      |  val result = answer
      |""".stripMargin
    os.write(source, text)
    val symbols = new InMemorySymbolTable
    symbols.add(SymbolDefinition("_empty_/Main.answer.", "answer", false, Range(7, 6, 7, 12), source))
    val target = ScalaPresentationTarget("scaladoc-poc", "3.7.4", List(scalaLibrary), Nil, Nil, Nil)
    val hover = new PresentationCompilerHover(
      os.pwd,
      (_, _) => new PresentationCompilerSymbolSearch(
        new WorkspaceIndex(os.pwd, symbols),
        Nil,
        MtagsScaladocMarkdown()
      )
    )
    try {
      val result = hover.hover(target, source.toNIO.toUri, text, line = 8, character = 16)
      assert(result.exists(_.toString.contains("The answer to everything.")), clues(result))
      assert(!result.exists(_.toString.contains("=== Heading ===")), clues(result))
    } finally hover.shutdown()
  }

  test("symbol search supplies Scaladoc from a target-scoped third-party source jar") {
    val workspace = os.temp.dir(prefix = "pc-dep-docs-ws-")
    val jarDir = os.temp.dir(prefix = "pc-dep-docs-jar-")
    val cacheRoot = os.temp.dir(prefix = "pc-dep-docs-cache-")
    val jar = jarDir / "example-sources.jar"
    val source =
      """package com.example
        |/** A documented library type. */
        |class LibraryType
        |""".stripMargin
    writeSourceJar(jar, "com/example/LibraryType.scala", source)
    try {
      val fingerprint = Fingerprint.fromJarPath(jar)
      SourceJarIndexer.index(jar, fingerprint, cacheRoot)
      val deps = new IndexedSymbolTable(cacheRoot = cacheRoot)
      deps.registerTarget("target", List(jar))
      val search = new PresentationCompilerSymbolSearch(
        new WorkspaceIndex(workspace, new InMemorySymbolTable, Some(deps)),
        List(jar)
      )

      val docs = search.documentation("com/example/LibraryType#", null).toScala
      assert(docs.exists(_.docstring().contains("documented library type")), clues(docs))
    } finally {
      os.remove.all(workspace)
      os.remove.all(jarDir)
      os.remove.all(cacheRoot)
    }
  }

  test("symbol search preserves indentation in Scaladoc code examples") {
    val root = os.temp.dir()
    val source = root / "Documented.scala"
    val text = """object Main:
      |  /**
      |   * {{{
      |   *   if (prime)
      |   *     Console.println("yes")
      |   *   else
      |   *     Console.err.println("no")
      |   * }}}
      |   */
      |  val answer = 42
      |""".stripMargin
    os.write(source, text)
    val symbols = new InMemorySymbolTable
    symbols.add(SymbolDefinition("_empty_/Main.answer.", "answer", false, Range(9, 6, 9, 12), source))
    val renderer = MtagsScaladocMarkdown()
    val search = new PresentationCompilerSymbolSearch(new WorkspaceIndex(os.pwd, symbols), Nil, renderer)
    try {
      val docs = search.documentation("_empty_/Main.answer.", null, scala.meta.pc.ContentType.MARKDOWN)
        .toScala
        .map(_.docstring())
      assert(docs.exists(_.contains("```\nif (prime)")), clues(docs))
      assert(docs.exists(_.contains("  Console.println(\"yes\")")), clues(docs))
    } finally {
      renderer.close()
      os.remove.all(root)
    }
  }

  test("completion reports members from the target-matched Scala 3 compiler") {
    val target = ScalaPresentationTarget(
      id = "scala-3-completion-poc",
      scalaVersion = "3.7.4",
      classpath = List(scalaLibrary),
      options = Nil,
      sourcePaths = Nil
    )
    val hover = new PresentationCompilerHover(os.pwd)
    try {
      val result = hover.complete(
        target,
        URI.create("file:///PresentationCompilerCompletionPoc.scala"),
        "object Main:\n  val answer = 42\n  ans\n",
        line = 2,
        character = 5
      )
      val labels = result.toList.flatMap(_.getItems.asScala).map(_.getLabel)
      assert(labels.exists(_.startsWith("answer")), s"completion labels did not contain answer: $labels")
    } finally hover.shutdown()
  }

  private def writeSourceJar(jar: os.Path, entry: String, content: String): Unit = {
    val zip = new ZipOutputStream(new FileOutputStream(jar.toIO))
    try {
      zip.putNextEntry(new ZipEntry(entry))
      zip.write(content.getBytes("UTF-8"))
      zip.closeEntry()
    } finally zip.close()
  }
}
