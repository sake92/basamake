package ba.sake.basamake.config

import ba.sake.tupson.{given, *}

final case class BasamakeConfig(
    bspOverrides: List[BspOverride] = Nil,
    /** Extra ignore patterns in gitignore syntax, relative to the project root.
      * Merged AFTER .gitignore rules — last match wins, so they can override or
      * negate .gitignore entries. Mirrors deder's watchIgnore. Note: patterns in
      * a nested .gitignore (deeper than the project root) are applied after these,
      * so they take precedence over config patterns. */
    ignorePatterns: List[String] = Nil,
    /** Opt-in: write the full symbol table to .basamake/symbol_table.txt (heavy
      * for large dependency trees — serializes the whole table). Off by default;
      * .basamake/index_sources.txt is always written. Option (not Boolean)
      * because tupson 0.20.0 ignores local defaults — Option has a global one. */
    debugSymbolTableDump: Option[Boolean] = None
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
    compileTimeoutSec: Option[Long] = None,   // default 600s (10 min)
    handshakeTimeoutSec: Option[Long] = None  // default 120s
) derives JsonRW
