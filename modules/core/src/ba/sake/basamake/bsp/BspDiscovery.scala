package ba.sake.basamake.bsp

import com.typesafe.scalalogging.StrictLogging
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import ba.sake.tupson.{given, *}

object BspDiscovery extends StrictLogging:

  /** Autodiscover ALL .bsp JSON files recursively under workspace root.
    * No filtering — the manager applies overrides post-discovery. */
  def discover(workspaceRoot: Path): List[BspConnectionSpec] =
    val bspDirs = findBspDirs(workspaceRoot)
    if bspDirs.isEmpty then
      logger.warn(s"No .bsp directories found under $workspaceRoot")
      return Nil

    val allSpecs = bspDirs.flatMap: bspDir =>
      val jsonFiles = Files.list(bspDir)
        .filter(p => p.getFileName.toString.endsWith(".json"))
        .iterator().asScala.toList
      jsonFiles.flatMap(parseBspSpec)

    logger.info(s"Discovered ${allSpecs.size} BSP connection(s)")
    allSpecs

  /** Parse a single .bsp JSON file. Public for the file watcher. */
  def parseSingleSpec(jsonPath: Path): Option[BspConnectionSpec] =
    parseBspSpec(jsonPath)

  /** Find all .bsp directories under the given root, recursively. */
  private def findBspDirs(root: Path): List[Path] =
    if !Files.isDirectory(root) then return Nil
    val dirs = scala.collection.mutable.ListBuffer[Path]()
    Files.walk(root).forEach: p =>
      if Files.isDirectory(p) && p.getFileName.toString == ".bsp" then dirs += p
    dirs.toList

  private def parseBspSpec(jsonPath: Path): Option[BspConnectionSpec] =
    try
      val raw = Files.readString(jsonPath)
      val content = raw.parseJson[BspDiscoveryFile]

      val bspDir = jsonPath.getParent
      val workingDir = Option(bspDir.getParent).getOrElse(Path.of("."))

      val fileName = jsonPath.getFileName.toString

      if content.argv.isEmpty then
        logger.warn(s"No argv found in $jsonPath")
        None
      else
        logger.info(s"Discovered ${content.name} from $jsonPath: ${content.argv.mkString(", ")}")
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
