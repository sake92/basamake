package ba.sake.basamake.lsp.index

import scala.meta.internal.semanticdb.{TextDocument, TextDocuments, Range => SdbRange}
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.{SymbolDefinition, SymbolUtils, ReferenceOccurrence}

object SemanticdbIndexing extends StrictLogging {

  private val skipDirNames = Set(".git",  ".basamake", ".bsp", "node_modules")

  /** Walk workspace for `META-INF/semanticdb/**/*.semanticdb` files and pair each
    * with a workspace source file by relative path heuristic.
    *
    * Returns Map: sourcePath -> semanticdbPath
    */
  def discoverSemanticdbSources(workspaceRoot: os.Path): Map[os.Path, os.Path] = {
    
    val skip: os.Path => Boolean = p => {
      if (os.isDir(p)) {
        skipDirNames.contains(p.last)
      } else false
    }
    val semFiles = os.walk(workspaceRoot, skip = skip).filter { p =>
      os.isFile(p) && p.toString.endsWith(".semanticdb")
    }
    val sources = os.walk(workspaceRoot, skip = skip).filter { p =>
      os.isFile(p) && (p.ext == "scala" || p.ext == "java")
    }.toSet

    val result = scala.collection.mutable.Map.empty[os.Path, os.Path]
    for semPath <- semFiles do {
      val relOpt = relativizeSemanticdbPath(workspaceRoot, semPath)
      relOpt.foreach { relSegs =>
        val matches = sources.filter { src =>
          endsWithSegments(src.relativeTo(workspaceRoot).toString.split('/').toList, relSegs)
        }
        if (matches.size == 1) result(matches.head) = semPath
        else if (matches.size > 1) {
          logger.debug(s"Ambiguous semanticdb match for $relSegs: ${matches.map(_.toString)}")
          result(matches.head) = semPath
        }
      }
    }
    result.toMap
  }

  /** Strip everything up to and including `META-INF/semanticdb/` plus the `.semanticdb`
    * suffix. Returns Some(List("src","main","scala","Main.scala")) or None if path
    * does not contain META-INF/semanticdb/.
    */
  private def relativizeSemanticdbPath(workspaceRoot: os.Path, semPath: os.Path): Option[List[String]] = {
    val segs = semPath.relativeTo(workspaceRoot).toString.split('/').toList
    val idx = segs.indexOf("semanticdb")
    if (idx <= 0) None
    else {
      val pre = segs.drop(idx + 1)
      // Last segment ends with ".semanticdb"; strip that suffix on the last element
      val relSegs = if (pre.nonEmpty) pre.init :+ pre.last.stripSuffix(".semanticdb") else pre
      Some(relSegs)
    }
  }

  private def endsWithSegments(full: List[String], suffix: List[String]): Boolean =
    full.length >= suffix.length && full.takeRight(suffix.length) == suffix

  /** Parse a `.semanticdb` file into definitions only (DEFINITION role).
    * Returns Vector[SymbolDefinition] with path = sourcePath, range = occurrence.range,
    * isType guessed from the descriptor suffix (# => true).
    */
  def parseDefinitions(semPath: os.Path, sourcePath: os.Path): Vector[SymbolDefinition] = {
    val bytes = os.read.bytes(semPath)
    val docs = TextDocuments.parseFrom(bytes)
    docs.documents.toVector.flatMap { doc =>
      doc.occurrences
        .filter(_.role == scala.meta.internal.semanticdb.SymbolOccurrence.Role.DEFINITION)
        .filter(_.symbol.nonEmpty)
        .map { occ =>
          val range = occ.range.getOrElse(new SdbRange(0, 0, 0, 0))
          val isType = occ.symbol.endsWith("#")
          val shortName = inferShortName(occ.symbol)
          SymbolDefinition(occ.symbol, shortName, isType, range, sourcePath)
        }
    }
  }

  /** Parse a `.semanticdb` file into per-occurrences list (defs + refs) — used at didOpen
    * to build the cursor cache for that single open file.
    */
  def parseOccurrences(semPath: os.Path): Vector[ReferenceOccurrence] = {
    val bytes = os.read.bytes(semPath)
    val docs = TextDocuments.parseFrom(bytes)
    docs.documents.toVector.flatMap { doc =>
      doc.occurrences
        .filter(_.symbol.nonEmpty)
        .map { occ =>
          val range = occ.range.getOrElse(new SdbRange(0, 0, 0, 0))
          val isDef = occ.role == scala.meta.internal.semanticdb.SymbolOccurrence.Role.DEFINITION
          ReferenceOccurrence(occ.symbol, range, isDef)
        }
    }
  }

  private def inferShortName(symbol: String): String = {
    val last = symbol.lastIndexOf('/') match
      case -1 => symbol
      case i  => symbol.drop(i + 1)
    last.takeWhile(c => c != '#' && c != '.' && c != '(')
  }

  /** Stand-in climber: given a symbol whose range is sentinel (0,0,0,0), strip its last
    * descriptor and look up the owner in the SymbolTable. Repeat until found. Returns
    * the stand-in SymbolDefinition (caller copies its range).
    */
  // TODO check if necessary..
  def ownerStandIn(symbol: String, table: ba.sake.basamake.navigation.SymbolTable): Option[SymbolDefinition] = {
    var current = symbol
    var depth = 0
    while (depth < 32) {
      current = stripLastDescriptor(current) match {
        case Some(owner) => owner
        case None        => return None
      }
      table.get(current) match {
        case Some(sd) if !isSentinelRange(sd.range) => return Some(sd)
        case _ => ()
      }
      depth += 1
    }
    None
  }

  private def stripLastDescriptor(symbol: String): Option[String] = {
    if (symbol.endsWith(")")) {
      val idx = symbol.lastIndexOf('(')
      if (idx >= 0) Some(symbol.take(idx)) else None
    } else if (symbol.endsWith("]")) {
      val idx = symbol.lastIndexOf('[')
      if (idx >= 0) Some(symbol.take(idx)) else None
    } else if (symbol.endsWith(").")) {
      val idx = symbol.lastIndexOf('(')
      if (idx >= 0) Some(symbol.take(idx)) else None
    } else if (symbol.endsWith("#") || symbol.endsWith(".") || symbol.endsWith("/")) {
      val stripped = symbol.dropRight(1)
      val slashIdx = stripped.lastIndexOf('/')
      if (slashIdx >= 0) Some(stripped.take(slashIdx + 1)) else None
    } else None
  }

  private def isSentinelRange(r: SdbRange): Boolean =
    r.startLine == 0 && r.startCharacter == 0 && r.endLine == 0 && r.endCharacter == 0
}
