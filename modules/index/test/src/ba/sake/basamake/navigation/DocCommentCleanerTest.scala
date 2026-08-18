package ba.sake.basamake.index

import munit.FunSuite

class DocCommentCleanerTest extends FunSuite {

  test("multi-line scaladoc: strips fences and leading stars") {
    val raw = "/**\n * Line one.\n * Line two.\n */"
    assertEquals(DocCommentCleaner.clean(raw), "Line one.\nLine two.")
  }

  test("one-line scaladoc") {
    assertEquals(DocCommentCleaner.clean("/** Single line. */"), "Single line.")
  }

  test("indented decoration is stripped") {
    val raw = "  /**\n   * Indented line.\n   */"
    assertEquals(DocCommentCleaner.clean(raw), "Indented line.")
  }

  test("javadoc html tags are stripped, inner text kept") {
    val raw = "/**\n * <p>First para.</p>\n * Use <code>code()</code> here.\n */"
    assertEquals(DocCommentCleaner.clean(raw), "First para.\nUse code() here.")
  }

  test("{@code} and {@link} are unwrapped") {
    assertEquals(
      DocCommentCleaner.clean("/** Use {@code foo()} or {@link java.lang.String#valueOf}. */"),
      "Use foo() or valueOf."
    )
  }

  test("{@link} with label keeps the label") {
    assertEquals(
      DocCommentCleaner.clean("/** See {@link java.lang.String#length length()} for details. */"),
      "See length() for details."
    )
  }

  test("{@literal} is unwrapped") {
    assertEquals(DocCommentCleaner.clean("/** A {@literal <} B. */"), "A < B.")
  }

  test("block tags are passed through as-is") {
    val raw = "/**\n * Does things.\n * @param x the input\n * @return the result\n */"
    assertEquals(
      DocCommentCleaner.clean(raw),
      "Does things.\n@param x the input\n@return the result"
    )
  }

  test("blank line runs collapse to a single blank line") {
    val raw = "/**\n * a\n *\n *\n *\n * b\n */"
    assertEquals(DocCommentCleaner.clean(raw), "a\n\nb")
  }
}
