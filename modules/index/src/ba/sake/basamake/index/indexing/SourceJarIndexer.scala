package ba.sake.basamake.index.indexing

import ba.sake.basamake.index.scalasrc.ScalaDefinitionsExtractor
import ba.sake.basamake.index.javasrc.JavaDefinitionsExtractor
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging

/** Builds the `<cacheRoot>/<fingerprint>/` cache for one source jar/zip (cacheRoot
  * is passed by the caller — the LSP server derives it from config, defaulting
  * to `~/.cache/basamake/deps`):
  * indexes definitions into `index.lmdb` and records staleness metadata in
  * `metadata.json`. Source files are NOT unpacked here — `extractEntry` writes
  * individual files into `src/` lazily, on first lookup hit (the LSP Location
  * must point at a real file for the editor to open it).
  *
  * Cache-hit path: metadata valid (source size/mtime match) → load LMDB into memory.
  * Cache-miss path: wipe any partial dir, (re)build, save atomically (tmp + rename).
  */
object SourceJarIndexer extends StrictLogging {

  // Serialize index() calls per fingerprint: several servers can race to index
  // the SAME jar/JDK into the same cache dir (each LSP server spawns its own
  // IndexedSymbolTable). Without the lock they'd wipe each other's partial work
  // (index() does os.remove.all + writes the shared <fp>/index.lmdb.tmp) and the
  // cache would never complete - while every waiter re-enqueues and churns. With
  // the lock, losers wait, then hit the cache-valid check and skip.
  // NOTE: ReentrantLock, NOT synchronized - index() runs on virtual threads and
  // a waiter blocked on `synchronized` PINNS a carrier thread (cannot unmount),
  // so N concurrent servers would occupy N carriers for the whole index; lock()
  // parks instead, freeing the carrier. (A JDK index takes ~40-60s cold.)
  private val indexLocks = new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.locks.ReentrantLock]()

  /** XDG-compliant cache root: `$XDG_CACHE_HOME/basamake/deps` on Linux/mac
    * (default `~/.cache/basamake/deps`), `%LOCALAPPDATA%\basamake\deps` on Windows. */
  def defaultCacheRoot: os.Path = {
    val base =
      if (scala.util.Properties.isWin)
        Option(System.getenv("LOCALAPPDATA")).map(os.Path(_)).getOrElse(os.home / "AppData" / "Local")
      else
        Option(System.getenv("XDG_CACHE_HOME")).map(os.Path(_)).getOrElse(os.home / ".cache")
    base / "basamake" / "deps"
  }

  /** Index `source` under `fingerprint`, writing `index.lmdb` + `metadata.json`.
    * Definitions are STREAMED into LMDB while the zip is parsed — no in-memory
    * symbol table is ever built (the JDK index alone is 570k symbols; building a
    * table for it cost ~500MB of heap). Lookups go through `LmdbSerializer.get`
    * point queries. */
  def index(
      source: os.Path,
      fingerprint: String,
      cacheRoot: os.Path,
      progress: (Long, Long, String) => Unit = (_, _, _) => ()
  ): Unit = {
    val lock = indexLocks.computeIfAbsent(fingerprint, _ => new java.util.concurrent.locks.ReentrantLock())
    lock.lock()
    try {
      indexLocked(source, fingerprint, cacheRoot, progress)
    } finally {
      lock.unlock()
    }
  }

  private def indexLocked(
      source: os.Path,
      fingerprint: String,
      cacheRoot: os.Path,
      progress: (Long, Long, String) => Unit
  ): Unit = {
    val cacheDir = cacheRoot / os.RelPath(fingerprint)
    val indexPath = cacheDir / "index.lmdb"

    // Defensive: the cache dir must exist before any check or LMDB open below
    // (LMDB's mdb_env_open fails with ENOENT when the env directory is missing).
    os.makeDir.all(cacheDir)

    CacheMetadata.load(cacheDir) match {
      case Some(meta) if meta.indexed && CacheMetadata.isValid(meta, source) && os.isDir(indexPath) =>
        // (Empty-packages caches — e.g. the old JDK metadata, stub sources jars —
        // are backfilled by IndexedSymbolTable.accuratePackages, which runs
        // before this path is ever reached.)
        logger.debug(s"Loading cached index for $source ($fingerprint)")
        return
      case _ => ()
    }

    logger.info(s"Indexing ${displayName(source)} ($fingerprint) into $cacheDir")
    os.remove.all(cacheDir)
    val srcRoot = cacheDir / "src"

    val sink = try {
      val zip = new ZipFile(source.toIO)
      try {
        // pre-count source entries for honest progress totals
        // (zip.size() includes directories and non-source files)
        val totalEntries = zip.entries().asScala.count(e => !e.isDirectory && isSourceEntry(e.getName)).toLong
        var doneEntries = 0L
        LmdbSerializer.streamingSave(indexPath, cacheDir) { sink =>
          val scalaExtractor = new ScalaDefinitionsExtractor(sink)
          val javaExtractor = new JavaDefinitionsExtractor(sink)
          zip.entries().asScala.foreach { entry =>
            if (!entry.isDirectory && isSourceEntry(entry.getName)) {
              doneEntries += 1
              try {
                val entryPath = entry.getName
                // the recorded def path is where the file WILL live once extracted
                val extractedPath = srcRoot / os.RelPath(entryPath)
                val entryIs = zip.getInputStream(entry)
                if (entryPath.endsWith(".java"))
                  javaExtractor.extract(entryPath, entryIs, extractedPath)
                else
                  scalaExtractor.extract(entryPath, entryIs, extractedPath)
              } catch {
                case NonFatal(e) =>
                  logger.warn(s"Skipping unindexable entry ${entry.getName} in $source: ${e.getMessage}")
              }
              progress(doneEntries, totalEntries, displayName(source))
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

    CacheMetadata.save(cacheDir, CacheMetadata(
      sourcePath = source.toString,
      sourceSize = os.size(source),
      sourceMtime = os.mtime(source),
      packages = packagesOfSource(source).toList.sorted,
      indexed = true,
      formatVersion = CacheMetadata.FormatVersion
    ))
    if (sink.count == 0) {
      // e.g. some scala3-library artifacts publish EMPTY -sources jars (stub
      // with only META-INF/MANIFEST.MF) — the index is built but resolves nothing
      logger.warn(s"Indexed 0 symbols from ${displayName(source)} — the sources jar may be an empty stub")
    } else {
      logger.info(s"Indexed ${sink.count} symbols from ${displayName(source)}")
    }
  }

  /** User-facing source name: maven coordinates (`groupId:artifactId:version`)
    * when the jar sits in a coursier-style maven cache, else the file name
    * (e.g. the JDK `src.zip`). */
  def displayName(source: os.Path): String =
    Fingerprint.mavenCoordinates(source) match {
      case Some((group, artifact, version)) => s"$group:$artifact:$version"
      case None                             => source.last
    }

  /** Unpack ONE source entry into `<cacheDir>/src/<entryPath>`. Idempotent — no-op
    * when the file already exists. Atomic per-file write (tmp sibling + rename). */
  def extractEntry(source: os.Path, fingerprint: String, entryPath: String, cacheRoot: os.Path): Unit = {
    val target = cacheRoot / os.RelPath(fingerprint) / "src" / os.RelPath(entryPath)
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

  /** The classes jar sibling of a sources jar in a coursier-style cache dir:
    * `foo_3-1.0.0-sources.jar` → `foo_3-1.0.0.jar`. None when the sources jar
    * does not follow the convention or the sibling does not exist. */
  def classesJarOf(sourcesJar: os.Path): Option[os.Path] = {
    val name = sourcesJar.last
    if name.endsWith("-sources.jar") then {
      val sibling = sourcesJar / os.up / (name.stripSuffix("-sources.jar") + ".jar")
      if os.exists(sibling) then Some(sibling) else None
    } else None
  }

  /** Packages from a CLASSES jar: the zip's class-file directories ARE the
    * packages (the JVM requires dir == package). Directory listing only — no
    * decompression, no parsing. Root classes (`module-info.class`) and
    * `META-INF/` entries (incl. multi-release versions) carry no package. */
  def packagesOfClassesJar(classesJar: os.Path): Set[String] = {
    val zip = new ZipFile(classesJar.toIO)
    try {
      zip.entries().asScala
        .map(_.getName)
        .filter(_.endsWith(".class"))
        .flatMap { e =>
          val segs = e.split('/').toList.dropRight(1)
          if segs.nonEmpty && segs.head != "META-INF" then Some(segs.mkString(".")) else None
        }.toSet
    } finally zip.close()
  }

  /** Packages from a SOURCE archive without a classes-jar sibling (e.g. the JDK
    * `src.zip`): the directories holding source files ARE the packages.
    * JDK-style archives are module-layout (`<module>/<pkg>/.../<file>.java`) —
    * the leading module segment is stripped (detected by `module-info.java`
    * files at depth 1). No decompression, no parsing. */
  def packagesOfSourceZip(source: os.Path): Set[String] = {
    val zip = new ZipFile(source.toIO)
    try {
      val entries = zip.entries().asScala
      val isJdkLayout = entries.exists(e => e.getName.matches("^[^/]+/module-info.java$"))
      zip.entries().asScala
        .filter(e => !e.isDirectory && isSourceEntry(e.getName))
        .flatMap { e =>
          var segs = e.getName.split('/').toList.dropRight(1)
          if (isJdkLayout && segs.nonEmpty) segs = segs.tail // strip the module dir
          if segs.nonEmpty && segs.head != "META-INF" then Some(segs.mkString(".")) else None
        }.toSet
    } finally zip.close()
  }

  /** Packages of a source jar: prefer the classes-jar sibling (authoritative),
    * else derive from the source archive itself (JDK src.zip, lone sources). */
  def packagesOfSource(source: os.Path): Set[String] =
    classesJarOf(source).map(packagesOfClassesJar).getOrElse(packagesOfSourceZip(source))
}
