package ba.sake.basamake.config

import ba.sake.tupson.{given, *}

final case class BasamakeConfig(
    bspOverrides: List[BspOverride] = Nil
) derives JsonRW

object BasamakeConfig {

  def load(workspaceRoot: os.Path): BasamakeConfig = {
    val configPath = workspaceRoot / ".basamake/config.json"
    if os.isFile(configPath) then
      try
        val raw = os.read(configPath)
        raw.parseJson[BasamakeConfig]
      catch case e: Exception =>
        BasamakeConfig()
    else BasamakeConfig()
  }
}

/** Per .bsp file override. bspFile is relative path from workspace root. */
final case class BspOverride(
    bspFile: String,
    enabled: Boolean = true,
    compileTimeoutSec: Option[Long] = None  // default 600s (10 min)
) derives JsonRW
