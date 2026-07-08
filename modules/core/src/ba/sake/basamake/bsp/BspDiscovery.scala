package ba.sake.basamake.bsp

import com.google.gson.JsonParser
import com.typesafe.scalalogging.StrictLogging
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object BspDiscovery extends StrictLogging:

  // Discover BSP connection specs from the workspace root.
  // M1: returns exactly one spec from .bsp/*.json.
  // M2: returns all discovered/explicitly-configured specs.
  def discover(workspaceRoot: Path): List[BspConnectionFile] =
    val bspDir = workspaceRoot.resolve(".bsp")
    if !Files.isDirectory(bspDir) then
      logger.warn(s"No .bsp directory found at $bspDir")
      return Nil

    val jsonFiles = Files
      .list(bspDir)
      .filter(p => p.getFileName.toString.endsWith(".json"))
      .iterator()
      .asScala
      .toList

    jsonFiles.flatMap(parseBspSpec)

  private def parseBspSpec(jsonPath: Path): Option[BspConnectionFile] =
    try
      val raw = Files.readString(jsonPath)
      val argv = extractJsonArray(raw, "argv")
      val workingDir = Option(jsonPath.getParent.getParent)
        .getOrElse(Path.of("."))

      if argv.isEmpty then
        logger.warn(s"No argv found in $jsonPath")
        None
      else
        logger.info(s"Discovered BSP connection from $jsonPath: ${argv.mkString(", ")}")
        Some(
          BspConnectionFile(
            path = jsonPath,
            argv = argv,
            workingDir = workingDir,
            debounceMs = 500L
          )
        )
    catch
      case e: Exception =>
        logger.error(s"Failed to parse BSP spec from $jsonPath", e)
        None

  private def extractJsonArray(raw: String, key: String): List[String] =
    try
      val json = JsonParser.parseString(raw).getAsJsonObject
      if json.has(key) then
        json.getAsJsonArray(key).iterator().asScala.map(_.getAsString).toList
      else
        List.empty
    catch
      case e: Exception =>
        logger.warn(s"Failed to extract '$key' from BSP JSON", e)
        List.empty
