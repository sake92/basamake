package ba.sake.basamake.lsp

import java.net.URI
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import ba.sake.basamake.bsp.ScalaPresentationTarget

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
}
