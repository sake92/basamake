package ba.sake.basamake.index

/** Lightweight scaladoc/javadoc → markdown cleanup for hover popups.
  *
  * - Strips `/**`/`*/` fences and leading `*` decoration
  * - Unwraps javadoc inline tags: `{@code X}` → `X`, `{@link X y}` → `y` (or `X`)
  * - Strips HTML tags (`<p>`, `<code>`, ...) keeping their text
  *
  * Deliberately NOT a full javadoc→markdown converter: block tags (`@param`,
  * `@return`, ...) are passed through as-is — they read fine as plain text. */
object DocCommentCleaner {

  private val ScalaDocCodeBlock = """(?s)\{\{\{\R?(.*?)\R?\}\}\}""".r

  def clean(raw: String): String = {
    val body = stripFences(raw)
    val stripped = body.split("\n", -1).iterator
      .map(_.replaceFirst("""^\s*\* ?""", ""))
      .mkString("\n")
    cleanupMarkdown(stripped)
  }

  private def stripFences(raw: String): String = {
    var s = raw.trim
    if (s.startsWith("/**")) s = s.drop(3)
    if (s.endsWith("*/")) s = s.dropRight(2)
    s.trim
  }

  private def cleanupMarkdown(text: String): String = {
    var s = renderScalaDocCodeBlocks(text)
    // {@code X} / {@literal X} → X
    s = s.replaceAll("""\{@(?:code|literal)\s+([^}]*)\}""", "$1")
    // {@link X} / {@link X label} / {@value X} → label, else last name segment
    val linkRe = """\{@(?:link|value)\s+([^\s}]+)(?:\s+([^}]*))?\}""".r
    s = linkRe.replaceAllIn(s, m => {
      val label = Option(m.group(2)).map(_.trim).filter(_.nonEmpty)
      label.getOrElse(m.group(1).split("#").last.split('.').last)
    })
    // strip HTML tags, keep inner text
    s = s.replaceAll("""<[^>]+>""", "")
    // collapse 3+ blank lines
    s = s.replaceAll("""\n{3,}""", "\n\n")
    s.linesIterator.map(_.stripTrailing).mkString("\n").trim
  }

  private def renderScalaDocCodeBlocks(text: String): String =
    ScalaDocCodeBlock.replaceAllIn(text, m => s"```\n${dedent(m.group(1))}\n```")

  private def dedent(text: String): String = {
    val lines = text.linesIterator.toList
    val indentation = lines.iterator
      .filter(_.trim.nonEmpty)
      .map(_.takeWhile(_.isWhitespace).length)
      .minOption
      .getOrElse(0)
    lines.map(_.drop(indentation)).mkString("\n").trim
  }
}
