package ba.sake.basamake.navigation.indexing

import org.lmdbjava.*
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition}
import scala.meta.internal.semanticdb.Range
import java.io.{ByteArrayOutputStream, ByteArrayInputStream, DataOutputStream, DataInputStream}
import java.nio.ByteBuffer

/** LMDB persistence for dependency indexes. `save` writes to a sibling `.tmp`
  * directory first and renames into place — a crash mid-write can never corrupt
  * an existing index (worst case: metadata is stale and we reindex).
  *
  * Lazy point-queries only — `get` opens the env per call, does a B-tree lookup
  * for ONE symbol and closes. Nothing is ever loaded into memory; the RAM saving
  * is the whole point of LMDB here.
  *
  * Value format (v1, see `CacheMetadata.FormatVersion`):
  *   - the symbol is NOT stored in the value — it IS the LMDB key
  *   - the path is always stored src-relative (`java.base/java/lang/Object.java`)
  *     and resolved against `<cacheDir>/src/` at load; no backward compat — a
  *     mismatched formatVersion makes the cache invalid and triggers a reindex
  *   - shortName is not stored either — it's derived from the symbol on read
  */
object LmdbSerializer {

  // 1GB — the JDK src.zip index (~570k symbols) exceeds 100MB. LMDB mapsize is
  // address space only; data.mdb still grows on disk as needed. Must stay >= the
  // largest saved index, also for read-only opens.
  private val MapSize = 1L * 1024 * 1024 * 1024 // 1GB

  def save(table: SymbolTable, path: os.Path): Unit = {
    val tmpPath = path / os.up / (path.last + ".tmp")
    val cacheDir = path / os.up
    os.remove.all(tmpPath)
    os.makeDir.all(tmpPath)
    try {
      val env = Env.create()
        .setMapSize(MapSize)
        .setMaxDbs(1)
        .open(tmpPath.toIO)

      try {
        val db = env.openDbi("symbols", DbiFlags.MDB_CREATE)
        val txn = env.txnWrite()

        try {
          table.all.foreach { d =>
            val keyBytes = d.symbol.getBytes("UTF-8")
            val keyBuf = ByteBuffer.allocateDirect(keyBytes.length)
            keyBuf.put(keyBytes).flip()
            val valueBytes = serialize(d, cacheDir)
            val valBuf = ByteBuffer.allocateDirect(valueBytes.length)
            valBuf.put(valueBytes).flip()
            db.put(txn, keyBuf, valBuf)
          }
          txn.commit()
        } finally txn.close() // aborts if the commit didn't happen
      } finally {
        env.close()
      }

      // rename into place: remove the old dir first (rename can't replace non-empty
      // dirs). Only reached on success — a failure keeps the old index untouched.
      os.remove.all(path)
      os.move(tmpPath, path)
    } finally {
      // on failure remove the partial write (no-op on success — dir was renamed away)
      os.remove.all(tmpPath)
    }
  }

  /** Point lookup of one symbol — opens the env per call (mmap, ~µs), never loads
    * the index into memory. Throws when the env is corrupt/missing (caller decides
    * how to recover — IndexedSymbolTable wipes and reindexes). */
  def get(path: os.Path, symbol: String): Option[SymbolDefinition] = {
    val env = Env.create()
      .setMapSize(MapSize)
      .setMaxDbs(1)
      .open(path.toIO, EnvFlags.MDB_RDONLY_ENV)

    try {
      val db = env.openDbi("symbols")
      val txn = env.txnRead()
      try {
        val keyBytes = symbol.getBytes("UTF-8")
        val keyBuf = ByteBuffer.allocateDirect(keyBytes.length)
        keyBuf.put(keyBytes).flip()
        val valBuf = db.get(txn, keyBuf)
        if (valBuf == null) None
        else {
          val arr = new Array[Byte](valBuf.remaining())
          valBuf.get(arr)
          Some(deserialize(arr, symbol, path))
        }
      } finally txn.close()
    } finally {
      env.close()
    }
  }

  /** Value payload: isType, range, path. The symbol is the key, shortName is derived. */
  private def serialize(d: SymbolDefinition, cacheDir: os.Path): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val dos = new DataOutputStream(bos)

    dos.writeBoolean(d.isType)
    dos.writeInt(d.range.startLine)
    dos.writeInt(d.range.startCharacter)
    dos.writeInt(d.range.endLine)
    dos.writeInt(d.range.endCharacter)
    // strict: dep defs always live under <cacheDir>/src/ — fail fast on anything else
    dos.writeUTF(d.path.relativeTo(cacheDir / "src").toString)

    dos.flush()
    bos.toByteArray
  }

  private def deserialize(bytes: Array[Byte], symbol: String, indexDir: os.Path): SymbolDefinition = {
    val bis = new ByteArrayInputStream(bytes)
    val dis = new DataInputStream(bis)

    val isType = dis.readBoolean()
    val range = Range(
      startLine = dis.readInt(),
      startCharacter = dis.readInt(),
      endLine = dis.readInt(),
      endCharacter = dis.readInt()
    )
    // (RelPath: the stored string is multi-segment, e.g. "java/lang/Object.java")
    val path = indexDir / os.up / "src" / os.RelPath(dis.readUTF())

    SymbolDefinition(symbol, shortNameOf(symbol), isType, range, path)
  }

  /** Derive the short name from the symbol: strip `(params`, trailing `#`/`.` and
    * the owner prefix. E.g. `java/lang/Object#clone().` → `clone`, `java/lang/Object#`
    * → `Object`. */
  private def shortNameOf(symbol: String): String = {
    val base = symbol.takeWhile(_ != '(').stripSuffix("#").stripSuffix(".")
    val afterHash = base.substring(base.lastIndexOf('#') + 1)
    val idx = afterHash.lastIndexOf('/')
    if (idx >= 0) afterHash.substring(idx + 1) else afterHash
  }
}
