package ba.sake.basamake.manager

import ba.sake.basamake.core.*
import ba.sake.basamake.util.Log
import java.nio.file.{Files, Path}

object Discovery:

  // Discover BSP connection specs from the workspace root.
  // M1: returns exactly one spec from .bsp/*.json.
  // M2: returns all discovered/explicitly-configured specs.
  def discover(workspaceRoot: Path): List[ConnectionSpec] =
    val bspDir = workspaceRoot.resolve(".bsp")
    if !Files.isDirectory(bspDir) then
      Log.warn(s"No .bsp directory found at $bspDir")
      return Nil

    import scala.jdk.CollectionConverters.*
    val jsonFiles = Files
      .list(bspDir)
      .filter(p => p.getFileName.toString.endsWith(".json"))
      .iterator()
      .asScala
      .toList

    jsonFiles.flatMap(parseBspSpec)

  private def parseBspSpec(jsonPath: Path): Option[ConnectionSpec] =
    try
      val raw = Files.readString(jsonPath)
      val argv = extractJsonArray(raw, "argv")
      val workingDir = Option(jsonPath.getParent.getParent)
        .getOrElse(Path.of("."))

      if argv.isEmpty then
        Log.warn(s"No argv found in $jsonPath")
        None
      else
        Log.info(s"Discovered BSP connection from $jsonPath: ${argv.mkString(", ")}")
        Some(
          ConnectionSpec(
            path = jsonPath,
            argv = argv,
            workingDir = workingDir,
            debounceMs = 500L
          )
        )
    catch
      case e: Exception =>
        Log.error(s"Failed to parse BSP spec from $jsonPath", e)
        None

  private def extractJsonArray(raw: String, key: String): List[String] =
    val pattern = s""""$key"\\s*:\\s*\\[(.*?)\\]""".r
    pattern.findFirstMatchIn(raw) match
      case Some(m) =>
        val inner = m.group(1)
        "\"(.*?)\"".r
          .findAllMatchIn(inner)
          .map(_.group(1))
          .toList
      case None =>
        List.empty
