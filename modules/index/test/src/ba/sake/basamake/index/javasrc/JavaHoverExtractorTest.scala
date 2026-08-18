package ba.sake.basamake.index.javasrc

import munit.FunSuite
import scala.meta.internal.semanticdb.Range

class JavaHoverExtractorTest extends FunSuite {

  /** Parse `content`, then extract hover at the line containing `marker`
    * for the declaration named `name`. */
  private def extractAt(content: String, name: String, marker: String): Option[(String, Option[String])] = {
    val cu = JavaHoverExtractor.parse(content).get
    val line = content.linesIterator.indexWhere(_.contains(marker))
    assert(line >= 0, s"marker not found: $marker")
    JavaHoverExtractor.extractCu(cu, name, new Range(line, 0, line, 0))
  }

  test("public method") {
    val code = "class A {\n  public String greet(String name) { return \"hi\"; }\n}"
    assertEquals(extractAt(code, "greet", "public String greet").map(_._1),
      Some("public String greet(String name)"))
  }

  test("generic method") {
    val code = "class A {\n  public <T> T identity(T t) { return t; }\n}"
    assertEquals(extractAt(code, "identity", "public <T> T").map(_._1),
      Some("public <T> T identity(T t)"))
  }

  test("method with varargs") {
    val code = "class A {\n  public void log(String... args) { }\n}"
    assertEquals(extractAt(code, "log", "public void log").map(_._1),
      Some("public void log(String... args)"))
  }

  test("method with throws") {
    val code = "class A {\n  public void run() throws java.io.IOException { }\n}"
    assertEquals(extractAt(code, "run", "public void run").map(_._1),
      Some("public void run() throws java.io.IOException"))
  }

  test("abstract method in interface") {
    val code = "interface Greeter {\n  String greet(String name);\n}"
    assertEquals(extractAt(code, "greet", "String greet").map(_._1), Some("String greet(String name)"))
  }

  test("class with extends and implements") {
    val code = "public class Foo extends Bar implements Baz { }\nclass Bar { }\ninterface Baz { }"
    assertEquals(extractAt(code, "Foo", "class Foo").map(_._1),
      Some("public class Foo extends Bar implements Baz"))
  }

  test("interface") {
    val code = "public interface Greeter { }"
    assertEquals(extractAt(code, "Greeter", "interface Greeter").map(_._1),
      Some("public interface Greeter"))
  }

  test("generic class") {
    val code = "public class Box<T> { }"
    assertEquals(extractAt(code, "Box", "class Box").map(_._1), Some("public class Box<T>"))
  }

  test("enum and enum constant") {
    val code = "public enum Color {\n  RED,\n  GREEN\n}"
    assertEquals(extractAt(code, "Color", "enum Color").map(_._1), Some("public enum Color"))
    assertEquals(extractAt(code, "RED", "RED,").map(_._1), Some("RED"))
  }

  test("record") {
    val code = "public record Point(int x, int y) { }"
    assertEquals(extractAt(code, "Point", "record Point").map(_._1),
      Some("public record Point(int x, int y)"))
  }

  test("annotation declaration") {
    val code = "public @interface Marker { }"
    assertEquals(extractAt(code, "Marker", "@interface Marker").map(_._1),
      Some("public @interface Marker"))
  }

  test("field") {
    val code = "class A {\n  private int count = 0;\n}"
    assertEquals(extractAt(code, "count", "private int count").map(_._1), Some("private int count"))
  }

  test("constructor") {
    val code = "class Foo {\n  public Foo(int x) { }\n}"
    assertEquals(extractAt(code, "Foo", "public Foo(int").map(_._1), Some("public Foo(int x)"))
  }

  test("param hover shows type and name") {
    val code = "class A {\n  public void foo(int x) { }\n}"
    assertEquals(extractAt(code, "x", "int x").map(_._1), Some("int x"))
  }

  test("no match on wrong line") {
    val code = "class A {\n  public void foo() { }\n}"
    val cu = JavaHoverExtractor.parse(code).get
    val res = JavaHoverExtractor.extractCu(cu, "foo", new Range(0, 0, 0, 0))
    assertEquals(res, None)
  }

  // ── javadoc ──────────────────────────────────────────────────

  test("one-line javadoc is attached") {
    val code = "class A {\n  /** Greets. */\n  public String greet(String name) { return \"hi\"; }\n}"
    assertEquals(extractAt(code, "greet", "public String greet").map(_._2), Some(Some("Greets.")))
  }

  test("multi-line javadoc with html and block tags") {
    val code =
      """class A {
        |  /**
        |   * <p>Greets the caller.</p>
        |   * @param name the name to greet
        |   * @return the greeting
        |   */
        |  public String greet(String name) { return "hi"; }
        |}
        |""".stripMargin
    val doc = extractAt(code, "greet", "public String greet").flatMap(_._2)
    assert(doc.exists(_.contains("Greets the caller.")), s"expected description, got: $doc")
    assert(doc.exists(_.contains("@param name the name to greet")), s"expected block tag, got: $doc")
    assert(doc.exists(_.contains("@return the greeting")), s"expected block tag, got: $doc")
  }

  test("javadoc with inline link") {
    val code = "class A {\n  /** Uses {@link java.lang.String#valueOf}. */\n  public void foo() { }\n}"
    assertEquals(extractAt(code, "foo", "public void foo").map(_._2), Some(Some("Uses valueOf.")))
  }
}
