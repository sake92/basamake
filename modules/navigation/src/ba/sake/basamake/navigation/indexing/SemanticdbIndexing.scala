package ba.sake.basamake.navigation.indexing

import scala.meta.internal.semanticdb.{TextDocument, TextDocuments, Range => SdbRange}
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.{SymbolDefinition, SymbolUtils, ReferenceOccurrence , ResolvedFile}

// TODO check how works under Scala 3 -Ybest-effort (partial symbols)
object SemanticdbIndexing extends StrictLogging {

  /** Index `.semanticdb` files from a single BSP target's output directory.
    *
    * Walks `semanticdbDir`, reads each file's `TextDocument.uri`, and resolves it
    * directly against `sourceRoot` — no ancestor climbing. The URI inside a
    * SemanticDB file is relative to the source root (specified via scalac
    * `-sourceroot` or defaults to the build tool's source root).
    *
    * Once paired, DEFINITION occurrences are parsed and added to `symbolTable`.
    *
    * @param semanticdbDir directory containing `.semanticdb` files for one target
    * @param sourceRoot    source root path for URI resolution
    * @param symbolTable   target table; definition occurrences are added here
    * @return Map: sourcePath -> semanticdbPath (caller stores it for didOpen/didSave)
    */
  def indexSemanticdbDir(
      semanticdbDir: os.Path,
      sourceRoot: os.Path,
      symbolTable: ba.sake.basamake.navigation.SymbolTable
  ): Map[os.Path, os.Path] = {
    val result = scala.collection.mutable.Map.empty[os.Path, os.Path]
    val semFiles = os.walk(semanticdbDir).filter(_.ext == "semanticdb").toList
    semFiles.foreach { semPath =>
      readUri(semPath).foreach { uri =>
        val uriStr = if (uri.startsWith("/")) uri.drop(1) else uri
        val src = sourceRoot / os.RelPath(uriStr)
        if (os.isFile(src)) {
          pairAndIndex(semPath, src, symbolTable, result)
        } else {
          logger.debug(s"No source match for $semPath (uri=$uri, sourceRoot=$sourceRoot)")
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
      val relSem = pairs.get(src).map(_.relativeTo(workspaceRoot).toString).getOrElse("<<NO SEMANTICDB>>")
      val relSrc = src.relativeTo(workspaceRoot)
      sb.append(s"$relSrc  =>  $relSem\n")
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
        .filterNot(o => SymbolUtils.isLocalSymbol(o.symbol)) // only global symbols go in SymbolTable
        .map { occ =>
          val range = occ.range.getOrElse(new SdbRange(0, 0, 0, 0))
          val isType = occ.symbol.endsWith("#")
          val shortName = inferShortName(occ.symbol)
          SymbolDefinition(occ.symbol, shortName, isType, range, sourcePath)
        }
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
  def parseOccurrences(semPath: os.Path, sourcePath: os.Path): ResolvedFile = {
    val bytes = os.read.bytes(semPath)
    val docs = TextDocuments.parseFrom(bytes).documents
    if (docs.isEmpty) return ResolvedFile(Vector.empty, Vector.empty)
    val doc = docs.head
    val (references, definitions) = doc.occurrences.toVector
      .filter(_.symbol.nonEmpty)
      .partition(_.role == scala.meta.internal.semanticdb.SymbolOccurrence.Role.REFERENCE)

    val refs = references.map { occ =>
      val range = occ.range.getOrElse(new SdbRange(0, 0, 0, 0))
      ReferenceOccurrence(occ.symbol, range)
    }
    val localDefs = definitions.filter(o => SymbolUtils.isLocalSymbol(o.symbol)).map { occ =>
      val range = occ.range.getOrElse(new SdbRange(0, 0, 0, 0))
      val isType = occ.symbol.endsWith("#")
      val shortName = inferShortName(occ.symbol)
      SymbolDefinition(occ.symbol, shortName, isType, range, sourcePath)
    }
    ResolvedFile(refs, localDefs)
  }

  private def inferShortName(symbol: String): String = {
    val last = symbol.lastIndexOf('/') match
      case -1 => symbol
      case i  => symbol.drop(i + 1)
    last.takeWhile(c => c != '#' && c != '.' && c != '(')
  }

}
