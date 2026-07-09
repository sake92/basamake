package ba.sake.basamake.bsp

import com.typesafe.scalalogging.StrictLogging
import ba.sake.tupson.{given, *}

object BspDiscovery extends StrictLogging:

  /** Autodiscover ALL .bsp JSON files recursively under workspace root.
    * No filtering — the manager applies overrides post-discovery. */
  def discover(workspaceRoot: os.Path): List[BspConnectionSpec] =
    val jsonFiles = findBspJsonFiles(workspaceRoot)
    if jsonFiles.isEmpty then
      logger.warn(s"No .bsp directories found under $workspaceRoot")
      return Nil 
    jsonFiles.toList.flatMap(parseBspSpec)

  /** Parse a single .bsp JSON file. Public for the file watcher. */
  def parseSingleSpec(jsonPath: os.Path): Option[BspConnectionSpec] =
    parseBspSpec(jsonPath)

  /** Find all .bsp JSON files under the workspace root, recursively.
    * Returns absolute paths. Public for the manager's file-change diffing. */
  private def findBspJsonFiles(workspaceRoot: os.Path): Set[os.Path] =
    findBspDirs(workspaceRoot).flatMap: bspDir =>
      logger.debug(s"Searching for .bsp JSON files in $bspDir")
      os.list(bspDir)
        .filter(p => p.last.endsWith(".json"))
    .toSet

  /** Find all .bsp directories under the given root, recursively. */
  def findBspDirs(root: os.Path): List[os.Path] =
    os.walk(root, maxDepth = 10)
      .filter(p => os.isDir(p) && p.last == ".bsp")
      .toList

  private def parseBspSpec(jsonPath: os.Path): Option[BspConnectionSpec] =
    try
      val raw = os.read(jsonPath)
      val content = raw.parseJson[BspDiscoveryFile]
      val workingDir = jsonPath / os.up / os.up

      if content.argv.isEmpty then
        logger.warn(s"No argv found in $jsonPath")
        None
      else
        logger.debug(s"Discovered ${content.name} from $jsonPath: ${content.argv.mkString(", ")}")
        Some(BspConnectionSpec(
          content = content,
          path = jsonPath,
          workingDir = workingDir,
          debounceMs = 500L,
        ))
    catch
      case e: Exception =>
        logger.error(s"Failed to parse BSP spec from $jsonPath: ${e.getMessage}")
        None
