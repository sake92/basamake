package ba.sake.basamake.lsp.index

import scala.meta.internal.semanticdb.{TextDocument, TextDocuments, Range => SdbRange}
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.{SymbolDefinition, SymbolUtils, ReferenceOccurrence}

object SemanticdbIndexing extends StrictLogging {

  /** Pair each `.semanticdb` file with its workspace source file.
    *
    * Strategy: read the `TextDocument.uri` (e.g. `src/main/scala/Main.scala`),
    * then climb from the `.semanticdb` file's parent directory up to `workspaceRoot`
    * (inclusive). At each ancestor `a`, try `a / uri` — first match wins. This
    * handles any layout where the semanticdb output base differs from the workspace
    * root (sbt, bloop, mill, scala-cli, etc.).
    *
    * Once paired, DEFINITION occurrences are parsed and added to `symbolTable`.
    *
    * @param semanticdbFiles paths discovered by the caller (one os.walk already done)
    * @param workspaceRoot   workspace root for path resolution
    * @param sourceFiles      set of `.scala`/`.java` source paths in the workspace
    * @param symbolTable      target table; definition occurrences are added here
    * @return Map: sourcePath -> semanticdbPath (caller stores it for didOpen/didSave)
    */
  def matchSemanticdbWithSources(
      semanticdbFiles: Seq[os.Path],
      workspaceRoot: os.Path,
      sourceFiles: Set[os.Path],
      symbolTable: ba.sake.basamake.navigation.SymbolTable
  ): Map[os.Path, os.Path] = {
    val sourceSet = sourceFiles
    val result = scala.collection.mutable.Map.empty[os.Path, os.Path]
    semanticdbFiles.foreach { semPath =>
      readUri(semPath).foreach { uri =>
        val uriStr = if (uri.startsWith("/")) uri.drop(1) else uri
        val rel = os.RelPath(uriStr)

        // Climb from the .semanticdb file's parent up to workspaceRoot.
        var ancestor: os.Path = semPath / os.up
        var found: Option[os.Path] = None
        while (found.isEmpty) {
          val candidate = ancestor / rel
          if (sourceSet.contains(candidate) || os.isFile(candidate)) {
            found = Some(candidate)
          } else if (ancestor == workspaceRoot) {
            // climbed all the way — done
            found = None
            ancestor = workspaceRoot // break condition below
          } else {
            ancestor = ancestor / os.up
          }
        }
        found match {
          case Some(src) => pairAndIndex(semPath, src, symbolTable, result)
          case None => logger.debug(s"No source match for $semPath (uri=$uri)")
        }
      }
    }
    result.toMap
  }

  private def pairAndIndex(
      semPath: os.Path, sourcePath: os.Path,
      symbolTable: ba.sake.basamake.navigation.SymbolTable,
      result: scala.collection.mutable.Map[os.Path, os.Path]
  ): Unit = {
    if (result.contains(sourcePath)) {
      logger.debug(s"Source $sourcePath already paired; skipping duplicate $semPath")
      return
    }
    result(sourcePath) = semPath
    try {
      val defs = parseDefinitions(semPath, sourcePath)
      defs.foreach(symbolTable.add)
    } catch {
      case e: Exception => logger.warn(s"Failed to parse $semPath: ${e.getMessage}")
    }
  }

  /** Read the first TextDocument's `uri` from a `.semanticdb` file. None if unreadable or empty. */
  private def readUri(semPath: os.Path): Option[String] = {
    try {
      val docs = TextDocuments.parseFrom(os.read.bytes(semPath))
      docs.documents.headOption.map(_.uri).filter(_.nonEmpty)
    } catch {
      case e: Exception => logger.warn(s"Failed to read uri from $semPath: ${e.getMessage}"); None
    }
  }

  /** DEBUG: dump a textual map sourcePath -> semanticdbPath for inspection. */
  def dumpPairs(pairs: Map[os.Path, os.Path], allSources: Set[os.Path], workspaceRoot: os.Path): String = {
    val sb = new StringBuilder
    sb.append(s"# semanticdb pair dump (workspace=$workspaceRoot)\n")
    sb.append(s"# paired sources: ${pairs.size} / ${allSources.size}\n")
    allSources.toList.sorted.foreach { src =>
      val sem = pairs.get(src).map(_.toString).getOrElse("<<NO SEMANTICDB>>")
      sb.append(s"$src  =>  $sem\n")
    }
    sb.toString
  }

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
    *
    * Returns `(occs, complete)` where `complete=false` signals that the semanticdb
    * contains short / unresolved ref symbols (no owner prefix, e.g. `utils.` instead
    * of `_empty_/utils.`). This happens under Scala 3 `-Ybest-effort`: the native
    * semanticdb emits partial symbols. The caller should fall back to source parsing
    * for the ref occurrences when `complete=false` (defs in SymbolTable are still
    * authoritative — DEFINITION occurrences are full symbols).
    */
  def parseOccurrences(semPath: os.Path): (Vector[ReferenceOccurrence], Boolean) = {
    val bytes = os.read.bytes(semPath)
    val docs = TextDocuments.parseFrom(bytes)
    val occs = docs.documents.toVector.flatMap { doc =>
      doc.occurrences
        .filter(_.symbol.nonEmpty)
        .filter(_.role != scala.meta.internal.semanticdb.SymbolOccurrence.Role.DEFINITION)
        .map { occ =>
          val range = occ.range.getOrElse(new SdbRange(0, 0, 0, 0))
          ReferenceOccurrence(occ.symbol, range)
        }
    }
    val complete = occs.forall(o => isFullSymbol(o.symbol))
    (occs, complete)
  }

  /** A full SemanticDB symbol has an owner prefix containing `/` (e.g. `_empty_/utils.`,
    * `scala/Int#`, `java/lang/String#`, `com/example/Outer#m().`). Short / unresolved
    * symbols emitted under `-Ybest-effort` lack the owner (e.g. `utils.`, `Unit#`).
    * `local<N>` are document-scoped and considered complete.
    */
  private def isFullSymbol(symbol: String): Boolean =
    symbol.contains("/") || SymbolUtils.isLocalSymbol(symbol)

  private def inferShortName(symbol: String): String = {
    val last = symbol.lastIndexOf('/') match
      case -1 => symbol
      case i  => symbol.drop(i + 1)
    last.takeWhile(c => c != '#' && c != '.' && c != '(')
  }

  private def isSentinelRange(r: SdbRange): Boolean =
    r.startLine == 0 && r.startCharacter == 0 && r.endLine == 0 && r.endCharacter == 0
}
