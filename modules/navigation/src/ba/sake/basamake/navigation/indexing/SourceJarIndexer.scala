package ba.sake.basamake.navigation.indexing

import ba.sake.basamake.navigation.{SymbolTable, InMemorySymbolTable}
import ba.sake.basamake.navigation.scalasrc.ScalaDefinitionsExtractor
import ba.sake.basamake.navigation.javasrc.JavaDefinitionsExtractor
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging

/** Builds the `~/.basamake/deps/<fingerprint>/` cache for one source jar/zip:
  * extracts `.scala`/`.java`/`.sbt` entries to `src/`, indexes definitions into
  * `index.lmdb`, and records staleness metadata in `metadata.json`.
  *
  * Cache-hit path: metadata valid (source size/mtime match) → load LMDB into memory.
  * Cache-miss path: wipe any partial dir, (re)build, save atomically (tmp + rename).
  */
object SourceJarIndexer extends StrictLogging {

  def cacheRoot: os.Path = os.home / ".basamake" / "deps"

  /** Index `source` under `fingerprint`; returns the fully loaded in-memory table. */
  def index(source: os.Path, fingerprint: String): InMemorySymbolTable = {
    val cacheDir = cacheRoot / fingerprint
    val indexPath = cacheDir / "index.lmdb"

    CacheMetadata.load(cacheDir) match {
      case Some(meta) if CacheMetadata.isValid(meta, source) && os.exists(indexPath) =>
        logger.debug(s"Loading cached index for $source ($fingerprint)")
        return LmdbSerializer.load(indexPath)
      case _ => ()
    }

    logger.info(s"Indexing ${source.last} ($fingerprint) into $cacheDir")
    os.remove.all(cacheDir)
    val table = new InMemorySymbolTable()
    val scalaExtractor = new ScalaDefinitionsExtractor(table)
    val javaExtractor = new JavaDefinitionsExtractor(table)
    val srcRoot = cacheDir / "src"

    try {
      val zip = new ZipFile(source.toIO)
      try {
        zip.entries().asScala.foreach { entry =>
          if (!entry.isDirectory && isSourceEntry(entry.getName)) {
            try {
              val entryPath = entry.getName
              val content = new String(zip.getInputStream(entry).readAllBytes(), "UTF-8")
              val extractedPath = srcRoot / os.RelPath(entryPath)
              os.write.over(extractedPath, content, createFolders = true)
              if (entryPath.endsWith(".java"))
                javaExtractor.extractFromContent(entryPath, content, extractedPath)
              else
                scalaExtractor.extractFromContent(entryPath, content, extractedPath)
            } catch {
              case NonFatal(e) =>
                logger.warn(s"Skipping unindexable entry ${entry.getName} in $source: ${e.getMessage}")
            }
          }
        }
      } finally zip.close()
    } catch {
      case e: Exception =>
        os.remove.all(cacheDir)
        logger.error(s"Failed to index $source: ${e.getMessage}", e)
        throw e
    }

    LmdbSerializer.save(table, indexPath)
    CacheMetadata.save(cacheDir, CacheMetadata(
      sourcePath = source.toString,
      sourceSize = os.size(source),
      sourceMtime = os.mtime(source),
      packages = CacheMetadata.packagesOf(table)
    ))
    logger.info(s"Indexed ${table.all.size} symbols from ${source.last}")
    LmdbSerializer.load(indexPath)
  }

  private def isSourceEntry(name: String): Boolean =
    name.endsWith(".scala") || name.endsWith(".sbt") || name.endsWith(".java")
}
