package ba.sake.basamake.navigation.indexing

import scala.meta.internal.semanticdb.{TextDocument, TextDocuments, Range => SdbRange}
import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.navigation.{SymbolDefinition, SymbolUtils, ReferenceOccurrence , ResolvedFile}

// TODO check how works under Scala 3 -Ybest-effort (partial symbols)
object SemanticdbIndexing extends StrictLogging {

  /** Result of a broad semanticdb dir walk: source→semanticdb pairings plus the
    * number of definition occurrences indexed (for startup phase diagnostics). */
  final case class SemanticdbIndexResult(pairs: Map[os.Path, os.Path], definitionsIndexed: Int)

  /** Index `.semanticdb` files from a single BSP target's output directory.
    *
    * Walks `semanticdbDir`, reads each file's `TextDocument.uri`, and resolves it
    * against `sourceRoot` — with ancestor climbing as a fallback (see
    * [[resolveSourcePath]]). The URI inside a SemanticDB file is relative to the
    * source root (specified via scalac `-sourceroot`, or the build tool's compile
    * base dir when the flag is absent — e.g. sbt-semanticdb passes no `-sourceroot`).
    *
    * Once paired, DEFINITION occurrences are parsed and added to `symbolTable`.
    *
    * @param semanticdbDir directory containing `.semanticdb` files for one target
    * @param sourceRoot    source root path for URI resolution (BSP-provided)
    * @param workspaceRoot upper bound for the ancestor-climbing fallback
    * @param symbolTable   target table; definition occurrences are added here
    * @return paired sources (sourcePath -> semanticdbPath) + definitions indexed
    */
  def indexSemanticdbDir(
      semanticdbDir: os.Path,
      sourceRoot: os.Path,
      workspaceRoot: os.Path,
      symbolTable: ba.sake.basamake.navigation.SymbolTable
  ): SemanticdbIndexResult = {
    val result = scala.collection.mutable.Map.empty[os.Path, os.Path]
    var definitionsIndexed = 0
    val semFiles = os.walk(semanticdbDir).filter(_.ext == "semanticdb").toList
    semFiles.foreach { semPath =>
      readUri(semPath).foreach { uri =>
        resolveSourcePath(semPath, uri, sourceRoot, workspaceRoot) match {
          case Some(src) => definitionsIndexed += pairAndIndex(semPath, src, symbolTable, result)
          case None => logger.warn(s"No source match for $semPath (uri=$uri, sourceRoot=$sourceRoot)")
        }
      }
    }
    SemanticdbIndexResult(result.toMap, definitionsIndexed)
  }

  /** Direct pairing for ONE known source file under ONE semanticdb root — the
    * onDidOpen fast path. Derives the conventional candidate path
    * (`<semanticdbDir>/META-INF/semanticdb/<uri>.semanticdb`), parses its
    * `TextDocument`, verifies the `uri` resolves back to exactly `sourcePath`,
    * and only then indexes its definitions into `symbolTable`.
    *
    * Ordinary "not present yet" cases return None WITHOUT warnings: source
    * outside the root, absent candidate, or a candidate whose uri maps to a
    * different source. WARN is logged only for malformed/unreadable candidate
    * data (matching the broad walk's error style).
    *
    * Does not replace [[indexSemanticdbDir]] — broad walking is still needed
    * for generated/unusual layouts and for sources that are not open.
    *
    * @param sourcePath    the opened source file to pair
    * @param sourceRoot    source root for SemanticDB URI resolution
    * @param semanticdbDir SemanticDB output directory (contains `META-INF/semanticdb`)
    * @param workspaceRoot upper bound for the ancestor-climbing fallback
    * @param symbolTable   target table; validated definition occurrences are added here
    * @return the paired `.semanticdb` path on success, else None
    */
  def pairSourceFromRoot(
      sourcePath: os.Path,
      sourceRoot: os.Path,
      semanticdbDir: os.Path,
      workspaceRoot: os.Path,
      symbolTable: ba.sake.basamake.navigation.SymbolTable
  ): Option[os.Path] = {
    if !sourcePath.startsWith(sourceRoot) then return None
    // conventional layout: <semanticdbDir>/META-INF/semanticdb/<uri>.semanticdb
    val rel = sourcePath.relativeTo(sourceRoot)
    val candidate = semanticdbDir / "META-INF" / "semanticdb" / os.RelPath(rel.toString + ".semanticdb")
    if !os.isFile(candidate) then return None
    try {
      val docs = TextDocuments.parseFrom(os.read.bytes(candidate))
      val uriOpt = docs.documents.headOption.map(_.uri).filter(_.nonEmpty)
      uriOpt match {
        case Some(uri) if resolveSourcePath(candidate, uri, sourceRoot, workspaceRoot).contains(sourcePath) =>
          // validated — reuse the same definition indexing as the broad walk
          parseDefinitions(candidate, sourcePath).foreach(symbolTable.add)
          Some(candidate)
        case _ => None
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to read $candidate: ${e.getMessage}")
        None
    }
  }

  /** Resolve a `.semanticdb` file's `uri` to an absolute source path.
    *
    * 1. Direct: `primaryRoot / uri` (BSP-provided source root from scalac
    *    `-sourceroot` or a structural fallback).
    * 2. Climb: from the `.semanticdb` file's parent directory up to `stopAt`
    *    (workspace root, inclusive), trying `ancestor / uri` at each level.
    *    SemanticDB files always live at `<targetRoot>/META-INF/semanticdb/<uri>.semanticdb`
    *    inside the build's output dir, so the source root is always an ancestor
    *    of the file. Bounded: stops at `stopAt` or the filesystem root — no
    *    infinite loop on stale semanticdb for deleted sources.
    *
    * @return Some(sourcePath) if a matching source file exists, else None
    */
  def resolveSourcePath(semPath: os.Path, uri: String, primaryRoot: os.Path, stopAt: os.Path): Option[os.Path] = {
    val uriStr = if (uri.startsWith("/")) uri.drop(1) else uri
    val rel = os.RelPath(uriStr)
    val direct = primaryRoot / rel
    if (os.isFile(direct)) return Some(direct)
    var ancestor: os.Path = semPath / os.up
    while (true) {
      val candidate = ancestor / rel
      if (os.isFile(candidate)) return Some(candidate)
      if (ancestor == stopAt || ancestor == ancestor / os.up) return None
      ancestor = ancestor / os.up
    }
    None
  }

  private def pairAndIndex(
      semPath: os.Path, sourcePath: os.Path,
      symbolTable: ba.sake.basamake.navigation.SymbolTable,
      result: scala.collection.mutable.Map[os.Path, os.Path]
  ): Int = {
    if (result.contains(sourcePath)) {
      logger.debug(s"Source $sourcePath already paired; skipping duplicate $semPath")
      return 0
    }
    result(sourcePath) = semPath
    try {
      val defs = parseDefinitions(semPath, sourcePath)
      defs.foreach(symbolTable.add)
      defs.size
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to parse $semPath: ${e.getMessage}")
        0
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

  /** DEBUG: dump a textual map sourcePath -> semanticdbPath for inspection.
    * Workspace sources are listed first, relative to the workspace root; files
    * OUTSIDE the workspace (dep/JDK sources opened via goto-def) are listed
    * LAST, after a marker comment, serialized as ABSOLUTE paths — they are
    * never semanticdb-paired. */
  def dumpPairs(pairs: Map[os.Path, os.Path], allSources: Set[os.Path], workspaceRoot: os.Path): String = {
    val sb = new StringBuilder
    sb.append(s"# semanticdb pair dump (workspace=$workspaceRoot)\n")
    sb.append(s"# paired sources: ${pairs.size} / ${allSources.size}\n")
    val (inside, outside) = allSources.toList.sorted.partition(_.startsWith(workspaceRoot))
    inside.foreach { src =>
      val relSem = pairs.get(src).map(_.relativeTo(workspaceRoot).toString).getOrElse("<<NO SEMANTICDB>>")
      val relSrc = src.relativeTo(workspaceRoot)
      sb.append(s"$relSrc  =>  $relSem\n")
    }
    if (outside.nonEmpty) {
      sb.append(s"# files outside the workspace (opened via goto-def)\n")
      outside.foreach { src =>
        val sem = pairs.get(src).map(_.toString).getOrElse("<<NO SEMANTICDB>>")
        sb.append(s"$src  =>  $sem\n")
      }
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
          val shortName = SymbolUtils.shortNameOf(occ.symbol)
          SymbolDefinition(occ.symbol, shortName, isType, range, sourcePath)
        }
    }
  }

  /** Parse a `.semanticdb` file into per-occurrences list — REFS ONLY.
    * Used at didOpen to build the cursor cache for a single open file.
    * Definition occurrences are filtered out; defs live in SymbolTable.
    *
    * `ResolvedFile.complete=false` signals that the semanticdb contains short /
    * unresolved ref symbols (no owner prefix, e.g. `utils.` instead of
    * `_empty_/utils.`). This happens under Scala 3 `-Ybest-effort`: the native
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
      val shortName = SymbolUtils.shortNameOf(occ.symbol)
      SymbolDefinition(occ.symbol, shortName, isType, range, sourcePath)
    }
    val complete = refs.forall(o => isFullSymbol(o.symbol))
    ResolvedFile(refs, localDefs, complete)
  }

  /** A full SemanticDB symbol has an owner prefix containing `/` (e.g. `_empty_/utils.`,
    * `scala/Int#`, `java/lang/String#`, `com/example/Outer#m().`). Short / unresolved
    * symbols emitted under `-Ybest-effort` lack the owner (e.g. `utils.`, `Unit#`).
    * `local<N>` are document-scoped and considered complete. */
  private def isFullSymbol(symbol: String): Boolean =
    symbol.contains("/") || SymbolUtils.isLocalSymbol(symbol)

}
