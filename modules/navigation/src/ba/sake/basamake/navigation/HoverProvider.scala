package ba.sake.basamake.navigation

import java.util.{Collections, LinkedHashMap}
import com.typesafe.scalalogging.StrictLogging
import scala.util.control.NonFatal
import ba.sake.basamake.navigation.indexing.WorkspaceIndex
import ba.sake.basamake.navigation.scalasrc.ScalaHoverExtractor
import ba.sake.basamake.navigation.javasrc.JavaHoverExtractor

/** Hover content for one symbol: signature line, optional doc comment, and the
  * declaration location (file + 0-based line). */
case class HoverInfo(signature: String, doc: Option[String], defPath: os.Path, defLine: Int) {

  /** Render as LSP markdown: bold signature, docs, location footer. */
  def markdown: String = {
    val sb = new StringBuilder(s"**${signature}**")
    doc.foreach(d => sb.append("\n\n").append(d))
    sb.append(s"\n\n— ${defPath.last}:${defLine + 1}")
    sb.toString
  }
}

/** Position → hover content.
  *
  * Pipeline: `WorkspaceIndex.findSymbolsAt` (refs, local defs, global defs —
  * hover works on def sites too) → symbol resolution (workspace table, open-file
  * locals, dep/JDK index scoped to the file's BSP target) → re-parse the
  * declaring source file (cached per path+mtime+size, LRU-bounded) → render
  * signature + doc comment. Falls back to raw declaration-line extraction when
  * parsing or tree matching fails (e.g. synthetic symbols like case-class
  * `apply`, whose def-range points at the class name). */
class HoverProvider(val workspaceIndex: WorkspaceIndex) extends StrictLogging {

  private val maxCacheEntries = 64
  private val parseCache = Collections.synchronizedMap(
    new LinkedHashMap[os.Path, CacheEntry](maxCacheEntries, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[os.Path, CacheEntry]): Boolean =
        size() > maxCacheEntries
    }
  )

  private final case class CacheEntry(mtime: Long, size: Long, parsed: ParsedFile)

  private sealed trait ParsedFile { def path: os.Path }
  private final case class ParsedScala(path: os.Path, source: scala.meta.Source) extends ParsedFile
  private final case class ParsedJava(path: os.Path, cu: com.github.javaparser.ast.CompilationUnit) extends ParsedFile

  /** @return hover info for the symbol(s) under the cursor, if any. */
  def hover(path: os.Path, line: Int, char: Int, depCandidates: List[os.Path] = Nil): Option[HoverInfo] = {
    val symbols = workspaceIndex.findSymbolsAt(path, line, char)
    if (symbols.isEmpty) None
    else
      symbols.iterator
        .flatMap(sym => workspaceIndex.getSymbol(sym, depCandidates))
        .flatMap(renderDef)
        .nextOption()
  }

  private def renderDef(defn: SymbolDefinition): Option[HoverInfo] = {
    val content = getParsed(defn.path).flatMap {
      case p: ParsedScala => ScalaHoverExtractor.extractSource(p.source, defn.shortName, defn.range)
      case p: ParsedJava  => JavaHoverExtractor.extractCu(p.cu, defn.shortName, defn.range)
    }
    content
      .orElse(sourceLineFallback(defn))
      .map { case (sig, doc) => HoverInfo(sig, doc, defn.path, defn.range.startLine) }
  }

  // ── parse cache ──────────────────────────────────────────────

  private def getParsed(path: os.Path): Option[ParsedFile] = {
    try {
      val stat = os.stat(path)
      var hit: Option[ParsedFile] = None
      parseCache.synchronized {
        val cached = parseCache.get(path)
        if (cached != null && cached.mtime == stat.mtime.toMillis && cached.size == stat.size) hit = Some(cached.parsed)
        else if (cached != null) parseCache.remove(path)
      }
      if (hit.isDefined) return hit

      val content = os.read(path)
      val parsed: Option[ParsedFile] = path.ext match {
        case "scala" | "sbt" => ScalaHoverExtractor.parse(path.last, content).map(ParsedScala(path, _))
        case "java"  => JavaHoverExtractor.parse(content).map(ParsedJava(path, _))
        case _       => None
      }
      parsed.foreach(p => parseCache.put(path, CacheEntry(stat.mtime.toMillis, stat.size, p)))
      parsed
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Hover: failed to read/parse ${path}: ${e.getMessage}")
        None
    }
  }

  // ── fallback: raw declaration line(s) from source ────────────

  private[navigation] def sourceLineFallback(defn: SymbolDefinition): Option[(String, Option[String])] = {
    try {
      if (!os.exists(defn.path)) return None
      val lines = os.read.lines(defn.path)
      if (defn.range.startLine >= lines.length) return None
      Some((extractDeclaration(lines, defn.range.startLine), None))
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Hover: fallback failed for ${defn.path}: ${e.getMessage}")
        None
    }
  }

  /** Read declaration lines starting at `startLine`, continuing while brackets
    * are unbalanced or the line ends with an open construct (`=`, `(`, `,`).
    * Capped at 10 lines. */
  private def extractDeclaration(lines: IndexedSeq[String], startLine: Int): String = {
    val maxLines = 10
    val sb = new StringBuilder
    var balance = 0
    var i = startLine
    var done = false
    while (i < lines.length && i < startLine + maxLines && !done) {
      val line = lines(i).trim
      if (line.nonEmpty) {
        sb.append(line).append(" ")
        balance += countOpen(line) - countClose(line)
        val endsOpen = line.endsWith("(") || line.endsWith("[") || line.endsWith("{") || line.endsWith("=") || line.endsWith(",")
        if (balance <= 0 && !endsOpen && sb.length > 10) done = true
      }
      i += 1
    }
    sb.toString.trim
  }

  private def countOpen(s: String): Int = s.count(c => c == '(' || c == '[' || c == '{')
  private def countClose(s: String): Int = s.count(c => c == ')' || c == ']' || c == '}')
}
