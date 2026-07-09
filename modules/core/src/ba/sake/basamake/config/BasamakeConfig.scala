package ba.sake.basamake.config

import java.nio.file.{Files, Path}
import ba.sake.tupson.{given, *}

/** Per-.bsp-file override. bspFile is relative path from workspace root. */
final case class BspOverride(
    bspFile: String,
    enabled: Boolean = true,
    debounceMs: Option[Long] = None
) derives JsonRW

/** Basamake configuration, loaded from .basamake/config.json. */
final case class BasamakeConfig(
    bspOverrides: List[BspOverride] = Nil
) derives JsonRW

object BasamakeConfig:

  /** Load config from .basamake/config.json if present, otherwise defaults (allow all). */
  def load(workspaceRoot: Path): BasamakeConfig =
    val configPath = workspaceRoot.resolve(".basamake").resolve("config.json")
    if Files.isRegularFile(configPath) then
      try
        val raw = Files.readString(configPath)
        raw.parseJson[BasamakeConfig]
      catch
        case e: Exception =>
          // Degrade gracefully — return defaults
          BasamakeConfig()
    else BasamakeConfig()
