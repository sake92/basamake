package ba.sake.basamake.navigation.indexing

import java.util.regex.{Pattern, Matcher}

/** Human-friendly position lookup for WorkspaceIndex tests.
  *
  * Instead of hardcoding `(line, char)` triples against a fixture, tests pass a
  * regex anchored to real source content. `at` returns the 0-indexed
  * `(line, startCharacter)` of the whole match. If the regex contains a named
  * group `p`, the position points at the start of that group (handy when the
  * match needs trailing context, e.g. `(?<p>getMsg)\(\)`).
  *
  * Source fixtures stay marker-free and parseable by the real extractor.
  */
object TestPositions:

  /** Returns (0-indexed line, 0-indexed startCharacter) for the first match of
    * `regex` in `content`. If a named group `p` exists, its start is used.
    */
  def at(content: String, regex: String): (Int, Int) =
    val m = Pattern.compile(regex).matcher(content)
    require(m.find(), s"TestPositions.at: regex not found: /$regex/")
    val start =
      try m.start("p")
      catch
        case _: IllegalArgumentException => m.start() // no group "p" → whole match
    require(start >= 0, s"TestPositions.at: group 'p' matched nothing in /$regex/")
    lineStart(content, start)

  /** Convenience: find a literal substring (regex-escaped). */
  def atLiteral(content: String, literal: String): (Int, Int) =
    at(content, Pattern.quote(literal))

  /** Find the Nth (0-based) match of `regex`; for ambiguous repeated tokens. */
  def atNth(content: String, regex: String, n: Int): (Int, Int) =
    val m = Pattern.compile(regex).matcher(content)
    var i = 0
    while i <= n do
      require(m.find(), s"TestPositions.atNth: occurrence $n not found for /$regex/")
      if i == n then return lineStart(content, try m.start("p") catch { case _ => m.start() })
      i += 1
    throw new IllegalStateException("unreachable")

  private def lineStart(content: String, absStart: Int): (Int, Int) =
    val before = content.substring(0, absStart)
    val line = before.count(_ == '\n')
    val lastNl = before.lastIndexOf('\n')
    val char = if lastNl < 0 then before.length else before.length - lastNl - 1
    (line, char)
