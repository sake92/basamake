package ba.sake.basamake.bsp

import ba.sake.basamake.config.BasamakeConfig
import ba.sake.basamake.index.indexing.GitIgnoreEngine

/** Single owner of the gitignore-aware watch/ignore filter shared by .bsp
  * discovery and the file watcher. Rebuildable when .gitignore files change.
  * Exempts .bsp dirs (build-server config files are usually gitignored but
  * must stay visible). */
final class WatchFilter(workspaceRoot: os.Path, config: BasamakeConfig) {

  @volatile private var current: GitIgnoreEngine = build()

  private def build(): GitIgnoreEngine =
    new GitIgnoreEngine(workspaceRoot, config.ignorePatterns.toVector, exemptLastNames = Set(".bsp"))

  /** Engine for discovery (e.g. BspDiscovery.discover). */
  def engine: GitIgnoreEngine = current

  /** Rebuild after a .gitignore change. */
  def reload(): Unit = { current = build() }

  /** Watch/ignore decision for a path: outside the workspace → ignored;
    * `.git` segments and .basamake/logs always ignored; otherwise engine rules. */
  def isIgnored(path: os.Path): Boolean = {
    val relOpt = try Some(path.relativeTo(workspaceRoot)) catch { case _: Exception => None }
    relOpt match {
      case None => true
      case Some(rel) if rel.segments.isEmpty => false
      case Some(rel) =>
        if (rel.segments.toSeq.contains(".git")) true
        else if (rel.segments.toSeq.sliding(2).exists(_.toSeq == Seq(".basamake", "logs"))) true
        else current.isIgnored(path, os.isDir(path))
    }
  }
}
