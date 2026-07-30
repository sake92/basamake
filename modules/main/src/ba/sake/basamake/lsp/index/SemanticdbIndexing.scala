package ba.sake.basamake.lsp.index

import scala.meta.internal.semanticdb.{TextDocument, TextDocuments, Range => SdbRange}
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.{SymbolDefinition, SymbolUtils, ReferenceOccurrence}

object SemanticdbIndexing extends StrictLogging {

  /** Pair each `.semanticdb` file with a workspace source file by relative-path
    * heuristic, parse DEFINITION occurrences, and add them to `symbolTable`.
    * Returns Map: sourcePath -> semanticdbPath (caller stores it for didOpen/didSave).
    *
    * @param semanticdbFiles paths discovered by the caller (one os.walk already done)
    * @param workspaceRoot   workspace root for path relativization
    * @param sourceFiles      set of `.scala`/`.java` source paths in the workspace
    * @param symbolTable      target table; definition occurrences are added here
    */
  def discoverSemanticdbSources(
      semanticdbFiles: Seq[os.Path],
      workspaceRoot: os.Path,
      sourceFiles: Set[os.Path],
      symbolTable: ba.sake.basamake.navigation.SymbolTable
  ): Map[os.Path, os.Path] = {
    val result = scala.collection.mutable.Map.empty[os.Path, os.Path]
    semanticdbFiles.foreach { semPath =>
      relativizeSemanticdbPath(workspaceRoot, semPath).foreach { relSegs =>
        val matches = sourceFiles.filter { src =>
          endsWithSegments(src.relativeTo(workspaceRoot).toString.split('/').toList, relSegs)
        }
        matches.headOption.foreach { sourcePath =>
          if (matches.size > 1)
            logger.debug(s"Ambiguous semanticdb match for $relSegs: ${matches.map(_.toString)}")
          result(sourcePath) = semPath
          try {
            val defs = parseDefinitions(semPath, sourcePath)
            defs.foreach(symbolTable.add)
          } catch {
            case e: Exception => logger.warn(s"Failed to parse $semPath: ${e.getMessage}")
          }
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
        .filterNot(sd => isSentinelRange(sd.range))
    }
  }

  /** Parse a `.semanticdb` file into per-occurrences list — REFS ONLY.
    * Used at didOpen to build the cursor cache for a single open file.
    * Definition occurrences are filtered out; defs live in SymbolTable.
    */
  def parseOccurrences(semPath: os.Path): Vector[ReferenceOccurrence] = {
    val bytes = os.read.bytes(semPath)
    val docs = TextDocuments.parseFrom(bytes)
    docs.documents.toVector.flatMap { doc =>
      doc.occurrences
        .filter(_.symbol.nonEmpty)
        .filter(_.role != scala.meta.internal.semanticdb.SymbolOccurrence.Role.DEFINITION)
        .map { occ =>
          val range = occ.range.getOrElse(new SdbRange(0, 0, 0, 0))
          ReferenceOccurrence(occ.symbol, range, isDefinition = false)
        }
    }
  }

  private def inferShortName(symbol: String): String = {
    val last = symbol.lastIndexOf('/') match
      case -1 => symbol
      case i  => symbol.drop(i + 1)
    last.takeWhile(c => c != '#' && c != '.' && c != '(')
  }

  private def isSentinelRange(r: SdbRange): Boolean =
    r.startLine == 0 && r.startCharacter == 0 && r.endLine == 0 && r.endCharacter == 0
}
