package ba.sake.basamake.bsp

import ba.sake.tupson.{given, *}
import ba.sake.basamake.index.indexing.SemanticdbDirs
import com.typesafe.scalalogging.StrictLogging

/** Reads .basamake/bsp/<name>_<hash>/data.json files persisted by
  * BspConnection.writeTargetData and collects (sourceRootDir, semanticdbDir)
  * pairs + warm per-target dependency source jars (source root → jars). Speeds
  * up subsequent startups without walking the whole workspace. Empty when no
  * data.json files exist. */
object BspWarmStart extends StrictLogging {

  def load(workspaceRoot: os.Path): (List[SemanticdbDirs], List[(os.Path, List[os.Path])]) = {
    val bspDir = workspaceRoot / ".basamake" / "bsp"
    if (!os.exists(bspDir) || !os.isDir(bspDir)) return (Nil, Nil)
    try {
      val dataFiles = os.walk(bspDir, maxDepth = 2).filter(_.last == "data.json")
      var roots = List.empty[SemanticdbDirs]
      var warmDeps = List.empty[(os.Path, List[os.Path])]
      dataFiles.foreach { f =>
        try {
          val data = os.read(f).parseJson[BspTargetData]
          data.targets.foreach { t =>
            roots = SemanticdbDirs(t.sourceRootDir, t.semanticdbDir) :: roots
            val deps = t.dependencySources.flatMap(s => try Some(os.Path(s)) catch { case _: Exception => None })
            if (deps.nonEmpty) warmDeps = (t.sourceRootDir, deps) :: warmDeps
          }
        } catch {
          case e: Exception =>
            logger.error(s"Skipping ${f.relativeTo(workspaceRoot)}: ${e.getMessage}")
        }
      }
      (roots, warmDeps.distinct)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load data.json files: ${e.getMessage}")
        (Nil, Nil)
    }
  }
}
