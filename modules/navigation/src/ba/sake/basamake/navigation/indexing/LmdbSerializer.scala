package ba.sake.basamake.navigation.indexing

import org.lmdbjava.*
import ba.sake.basamake.navigation.{SymbolTable, SymbolDefinition, SymbolUtils}
import scala.meta.internal.semanticdb.Range
import java.io.{ByteArrayOutputStream, ByteArrayInputStream, DataOutputStream, DataInputStream}
import java.nio.ByteBuffer

/** LMDB persistence for dependency indexes. Writes are STREAMED: definitions go
  * straight into LMDB as they are extracted (`streamingSave` + [[SymbolSink]]) —
  * the JDK index alone is 570k symbols, and materializing them in an in-memory
  * table cost ~500MB of heap. Writes go to a sibling `.tmp` directory first and
  * rename into place — a crash mid-write can never corrupt an existing index
  * (worst case: metadata is stale and we reindex).
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

  // lmdbjava requires DIRECT buffers for keys/values. Allocating a fresh one per
  // symbol made a JDK index save allocate ~1.1M direct buffers (native memory
  // churn + GC load). Writes reuse two growable buffers; `get` reuses a
  // per-thread key buffer.
  private val keyBufLocal = new ThreadLocal[ByteBuffer]()
  private val InitialBufCapacity = 256

  /** Reusable direct buffer that grows on demand (capacity only ever increases). */
  private final class ReusableDirectBuffer {
    private var capacity = InitialBufCapacity
    private var buf = ByteBuffer.allocateDirect(capacity)
    /** Fill with `bytes`, flipped for LMDB use. Grows (rarely) when needed. */
    def fill(bytes: Array[Byte]): ByteBuffer = {
      if (bytes.length > capacity) {
        capacity = bytes.length
        buf = ByteBuffer.allocateDirect(capacity)
      }
      buf.clear()
      buf.put(bytes).flip()
      buf
    }
  }

  /** Write-only `SymbolTable` that puts each definition into LMDB immediately —
    * extractors stream through it while parsing, no in-memory table is built.
    * Mirrors `InMemorySymbolTable.add` semantics: local symbols are skipped.
    * Exposes `count` + `packages` (for CacheMetadata) after the fill completes. */
  final class SymbolSink private[LmdbSerializer] (
      txn: Txn[ByteBuffer],
      db: Dbi[ByteBuffer],
      cacheDir: os.Path
  ) extends SymbolTable {
    private val keyBuf = new ReusableDirectBuffer
    private val valBuf = new ReusableDirectBuffer
    private val packagesSet = scala.collection.mutable.Set.empty[String]
    private var counter = 0

    override def add(symDef: SymbolDefinition): Unit = {
      if (SymbolUtils.isLocalSymbol(symDef.symbol)) return
      val keyBytes = symDef.symbol.getBytes("UTF-8")
      val valueBytes = serialize(symDef, cacheDir)
      db.put(txn, keyBuf.fill(keyBytes), valBuf.fill(valueBytes))
      counter += 1
      SymbolUtils.packageOf(symDef.symbol).foreach(packagesSet.add)
    }

    override def get(symbol: String): Option[SymbolDefinition] = None
    override def byPath(path: os.Path): Set[SymbolDefinition] = Set.empty
    override def removeByPath(path: os.Path): Unit = ()
    override def keys: Set[String] = Set.empty
    override def all: Set[SymbolDefinition] = Set.empty

    def count: Int = counter
    def packages: Set[String] = packagesSet.toSet
  }

  /** Stream `fill`'s definitions straight into LMDB under `indexPath` (atomic
    * tmp + rename). Returns the sink so the caller can read `count`/`packages`
    * for metadata. Never builds the full symbol set in memory. */
  def streamingSave(indexPath: os.Path, cacheDir: os.Path)(fill: SymbolSink => Unit): SymbolSink = {
    val tmpPath = indexPath / os.up / (indexPath.last + ".tmp")
    os.remove.all(tmpPath)
    os.makeDir.all(tmpPath)
    try {
      // MDB_WRITEMAP: dirty pages are written straight into the mmap (OS page
      // cache) instead of malloc'd copies held for the whole txn — for the JDK
      // index (~120MB, 570k symbols, one giant txn) that removes ~120MB of
      // native buffering during the save. MDB_NOSYNC: skip the per-commit fsync
      // of the whole file. Both are safe here — durability is provided by the
      // tmp dir + rename publish below; a crash mid-write leaves only a stale
      // tmp dir that the finally block wipes (and metadata.json is only written
      // after the rename, so the cache stays invalid and gets reindexed).
      val env = Env.create()
        .setMapSize(MapSize)
        .setMaxDbs(1)
        .open(tmpPath.toIO, EnvFlags.MDB_WRITEMAP, EnvFlags.MDB_NOSYNC)

      val sink = try {
        val db = env.openDbi("symbols", DbiFlags.MDB_CREATE)
        val txn = env.txnWrite()
        try {
          val s = new SymbolSink(txn, db, cacheDir)
          fill(s)
          txn.commit()
          s
        } finally txn.close() // aborts if the commit didn't happen
      } finally {
        env.close()
      }

      // rename into place: remove the old dir first (rename can't replace non-empty
      // dirs). Only reached on success — a failure keeps the old index untouched.
      os.remove.all(indexPath)
      os.move(tmpPath, indexPath)
      sink
    } finally {
      // on failure remove the partial write (no-op on success — dir was renamed away)
      os.remove.all(tmpPath)
    }
  }

  /** Convenience wrapper for callers that already hold a table (tests). */
  def save(table: SymbolTable, path: os.Path): Unit = {
    streamingSave(path, path / os.up) { sink => table.all.foreach(sink.add) }
    ()
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
        val keyBuf = keyBufferFor(keyBytes)
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

  /** Per-thread reusable key buffer (grows on demand). Safe: the value buffer
    * returned by `db.get` aliases THIS buffer's memory, but callers copy the
    * bytes out before the next get on the same thread. */
  private def keyBufferFor(keyBytes: Array[Byte]): ByteBuffer = {
    var buf = keyBufLocal.get()
    if (buf == null || buf.capacity() < keyBytes.length) {
      buf = ByteBuffer.allocateDirect(keyBytes.length)
      keyBufLocal.set(buf)
    }
    buf.clear()
    buf.put(keyBytes).flip()
    buf
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
