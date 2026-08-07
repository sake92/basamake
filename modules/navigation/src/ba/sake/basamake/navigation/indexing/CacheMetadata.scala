package ba.sake.basamake.navigation.indexing

import ba.sake.tupson.{given, *}

/** Per-index cache metadata, stored as `~/.basamake/deps/<fingerprint>/metadata.json`.
  *
  * `sourceSize`/`sourceMtime` validate the cache against the source file on disk
  * (cheap staleness check — no content hashing). `packages` lists every dotted
  * package found in the index, used to route symbol lookups to the right indexes
  * without opening them (see IndexedSymbolTable). `formatVersion` guards the LMDB
  * on-disk format — mismatched/absent versions reindex instead of misreading.
  */
final case class CacheMetadata(
    sourcePath: String,
    sourceSize: Long,
    sourceMtime: Long,
    packages: List[String],
    formatVersion: Int
) derives JsonRW

object CacheMetadata {

  val FileName = "metadata.json"

  /** Bump when LmdbSerializer's on-disk value format changes (see LmdbSerializer).
    * No backward compat — a mismatch invalidates the cache and triggers a reindex. */
  val FormatVersion = 1

  /** Read metadata from the cache dir. None when missing or corrupt. */
  def load(cacheDir: os.Path): Option[CacheMetadata] = {
    val f = cacheDir / FileName
    if !os.exists(f) then None
    else
      try Some(os.read(f).parseJson[CacheMetadata])
      catch { case e: Exception => None }
  }

  /** Write metadata atomically (temp file + rename). */
  def save(cacheDir: os.Path, meta: CacheMetadata): Unit = {
    os.makeDir.all(cacheDir)
    val target = cacheDir / FileName
    val tmp = cacheDir / (FileName + ".tmp")
    os.write.over(tmp, ba.sake.tupson.toJson(meta))
    os.move(tmp, target, replaceExisting = true)
  }

  /** True when the cached index for `source` is still valid: metadata exists, the
    * LMDB format version matches, and the source file on disk has the same size
    * and mtime we indexed. */
  def isValid(meta: CacheMetadata, source: os.Path): Boolean =
    meta.formatVersion == FormatVersion &&
      os.exists(source) && os.size(source) == meta.sourceSize && os.mtime(source) == meta.sourceMtime

  /** Dotted packages of all global symbols in a table, sorted + distinct.
    * Symbols in the default package are skipped (they aren't routable). */
  def packagesOf(table: ba.sake.basamake.navigation.SymbolTable): List[String] = {
    table.all.iterator.flatMap(d => ba.sake.basamake.navigation.SymbolUtils.packageOf(d.symbol)).toList.distinct.sorted
  }
}
