package ba.sake.basamake.bsp

import com.typesafe.scalalogging.StrictLogging
import ba.sake.tupson.{given, *}
import ba.sake.basamake.index.indexing.GitIgnoreEngine

object BspDiscovery extends StrictLogging {

  /** Discover ALL .bsp JSON files recursively under workspace root.
    * Gitignored directories are pruned, EXCEPT `.bsp` dirs themselves — they are
    * typically gitignored but essential. Pass an engine built with
    * `exemptLastNames = Set(".bsp")`.
    * `.git` dirs and nested git repositories are never entered. */
  def discover(workspaceRoot: os.Path, engine: GitIgnoreEngine): List[BspConnectionSpec] = {
    val jsonFiles = findBspJsonFiles(workspaceRoot, engine)
    if jsonFiles.isEmpty then
      logger.warn(s"No .bsp directories found under $workspaceRoot")
    jsonFiles.toList.sortBy(_.toString).flatMap(parseBspSpec(_, workspaceRoot))
  }

  /** Parse a single .bsp JSON file. Called from BspManager.handleBspChanges
    * (same package) when the watcher detects a new/changed .bsp JSON. */
  private[bsp] def parseSingleSpec(jsonPath: os.Path, workspaceRoot: os.Path): Option[BspConnectionSpec] =
    parseBspSpec(jsonPath, workspaceRoot)

  private def findBspJsonFiles(workspaceRoot: os.Path, engine: GitIgnoreEngine): Set[os.Path] =
    findBspDirs(workspaceRoot, engine).flatMap { bspDir =>
      logger.debug(s"Searching for .bsp JSON files in $bspDir")
      os.list(bspDir).filter(p => p.last.endsWith(".json"))
    }
    .toSet

  private def findBspDirs(root: os.Path, engine: GitIgnoreEngine): List[os.Path] =
    os.walk(root, maxDepth = 10, skip = p =>
      os.isDir(p) && (p.last == ".git" || engine.isIgnored(p, isDir = true))
    )
    .filter(p => os.isDir(p) && p.last == ".bsp")
    .toList

  private def parseBspSpec(jsonPath: os.Path, workspaceRoot: os.Path): Option[BspConnectionSpec] =
    try
      val raw = os.read(jsonPath)
      val content = raw.parseJson[BspDiscoveryFile]
      if content.argv.isEmpty then
        logger.warn(s"No argv found in $jsonPath")
        None
      else
        logger.debug(s"Discovered ${content.name} from $jsonPath: ${content.argv.mkString(", ")}")
        Some(BspConnectionSpec(
          content = content,
          path = jsonPath,
          workspaceRoot = workspaceRoot
        ))
    catch
      case e: Exception =>
        logger.error(s"Failed to parse BSP spec from $jsonPath: ${e.getMessage}")
        None
}
