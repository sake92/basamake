package ba.sake.basamake.navigation.scalasrc

import munit.FunSuite

class ScalaParseUtilsTest extends FunSuite {

  private def parseOk(fileName: String, code: String): Boolean =
    ScalaParseUtils.parseSource(fileName, code).isRight

  test("sbt style: settings expression (bare term) parses") {
    assert(parseOk("build.sbt", """ThisBuild / scalaVersion := "3.3.1""""))
  }

  test("sbt style: multi-statement top-level vals parse") {
    assert(parseOk("build.sbt", "lazy val core = project\nlazy val cli = project.dependsOn(core)"))
  }

  test("sbt style: scala 3 syntax (named given with indentation) parses") {
    assert(parseOk("build.sbt", "given myOrdering: Ordering[Int] with\n  def compare(a: Int, b: Int) = a - b"))
  }

  test("sbt style: xml literal parses (allowed in all scalameta dialects)") {
    assert(parseOk("build.sbt", "val xmlValue = <a/><b/>"))
  }

  test("sbt style: do-while body falls back to Sbt1") {
    assert(parseOk("build.sbt", "def f = do { println(1) } while (false)"))
  }

  test("sbt style: invalid content fails") {
    assert(ScalaParseUtils.parseSource("build.sbt", "class {").isLeft)
  }

  test("scala style: top-level statements now parse (scala 3)") {
    assert(parseOk("Main.scala", "val greeting = \"hello\"\ndef main(): Unit = println(greeting)"))
  }

  test("scala style: plain class still parses") {
    assert(parseOk("Main.scala", "package a\nclass C"))
  }

  test("wrapper: build.sbt → _empty_/build.") {
    assertEquals(ScalaParseUtils.computeWrapper("build.sbt", "_empty_/"), Some("_empty_/build."))
  }

  test("wrapper: plugins.sbt → _empty_/plugins.") {
    assertEquals(ScalaParseUtils.computeWrapper("plugins.sbt", "_empty_/"), Some("_empty_/plugins."))
  }

  test("wrapper: scala file keeps X$package convention") {
    assertEquals(ScalaParseUtils.computeWrapper("Foo.scala", "_empty_/"), Some("_empty_/Foo$package."))
  }

  test("wrapper: package.scala special case keeps package$package") {
    assertEquals(ScalaParseUtils.computeWrapper("package.scala", "com/example/"), Some("com/example/package$package."))
  }

  test("wrapper: sbt with non-empty package owner") {
    assertEquals(ScalaParseUtils.computeWrapper("build.sbt", "com/example/"), Some("com/example/build."))
  }

  test("wrapper: file named exactly .sbt has no wrapper") {
    assertEquals(ScalaParseUtils.computeWrapper(".sbt", "_empty_/"), None)
  }
}
