package ba.sake.basamake.bsp

import com.typesafe.scalalogging.StrictLogging
import ba.sake.tupson.{given, *}

object BspDiscovery extends StrictLogging {

  /** Discover ALL .bsp JSON files recursively under workspace root.
    */
  def discover(workspaceRoot: os.Path): List[BspConnectionSpec] = {
    val jsonFiles = findBspJsonFiles(workspaceRoot)
    if jsonFiles.isEmpty then
      logger.warn(s"No .bsp directories found under $workspaceRoot")
    jsonFiles.toList.sortBy(_.toString).flatMap(parseBspSpec(_, workspaceRoot))
  }

  /** Parse a single .bsp JSON file. Public for the file watcher. */
  def parseSingleSpec(jsonPath: os.Path, workspaceRoot: os.Path): Option[BspConnectionSpec] =
    parseBspSpec(jsonPath, workspaceRoot)

  private def findBspJsonFiles(workspaceRoot: os.Path): Set[os.Path] =
    findBspDirs(workspaceRoot).flatMap { bspDir =>
      logger.debug(s"Searching for .bsp JSON files in $bspDir")
      os.list(bspDir).filter(p => p.last.endsWith(".json"))
    }
    .toSet

  private def findBspDirs(root: os.Path): List[os.Path] =
    os.walk(root, maxDepth = 10)
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
