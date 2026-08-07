package ba.sake.basamake.navigation.indexing

import ba.sake.basamake.navigation.InMemorySymbolTable
import ba.sake.basamake.navigation.scalasrc.ScalaDefinitionsExtractor
import ba.sake.basamake.navigation.javasrc.JavaDefinitionsExtractor
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging

/** Builds the `~/.basamake/deps/<fingerprint>/` cache for one source jar/zip:
  * indexes definitions into `index.lmdb` and records staleness metadata in
  * `metadata.json`. Source files are NOT unpacked here — `extractEntry` writes
  * individual files into `src/` lazily, on first lookup hit (the LSP Location
  * must point at a real file for the editor to open it).
  *
  * Cache-hit path: metadata valid (source size/mtime match) → load LMDB into memory.
  * Cache-miss path: wipe any partial dir, (re)build, save atomically (tmp + rename).
  */
object SourceJarIndexer extends StrictLogging {

  // overridable in tests — they must never write into the real home cache
  @volatile var cacheRoot: os.Path = os.home / ".basamake" / "deps"

  /** Index `source` under `fingerprint`, writing `index.lmdb` + `metadata.json`.
    * Returns nothing — lookups go through `LmdbSerializer.get` point queries,
    * the in-memory build table is dropped after `save`. */
  def index(source: os.Path, fingerprint: String): Unit = {
    val cacheDir = cacheRoot / fingerprint
    val indexPath = cacheDir / "index.lmdb"

    CacheMetadata.load(cacheDir) match {
      case Some(meta) if CacheMetadata.isValid(meta, source) && os.isDir(indexPath) =>
        logger.debug(s"Loading cached index for $source ($fingerprint)")
        return
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
              // the recorded def path is where the file WILL live once extracted
              val extractedPath = srcRoot / os.RelPath(entryPath)
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
      packages = CacheMetadata.packagesOf(table),
      formatVersion = CacheMetadata.FormatVersion
    ))
    logger.info(s"Indexed ${table.all.size} symbols from ${source.last}")
  }

  /** Unpack ONE source entry into `<cacheDir>/src/<entryPath>`. Idempotent — no-op
    * when the file already exists. Atomic per-file write (tmp sibling + rename). */
  def extractEntry(source: os.Path, fingerprint: String, entryPath: String): Unit = {
    val target = cacheRoot / fingerprint / "src" / os.RelPath(entryPath)
    if (os.exists(target)) return

    val zip = new ZipFile(source.toIO)
    try {
      val entry = zip.getEntry(entryPath)
      if (entry == null || entry.isDirectory) {
        logger.warn(s"Entry $entryPath not found in $source")
        return
      }
      val content = new String(zip.getInputStream(entry).readAllBytes(), "UTF-8")
      val tmp = target / os.up / (target.last + ".tmp")
      os.write.over(tmp, content, createFolders = true)
      os.move(tmp, target, replaceExisting = true)
    } finally zip.close()
  }

  private def isSourceEntry(name: String): Boolean =
    name.endsWith(".scala") || name.endsWith(".sbt") || name.endsWith(".java")
}
