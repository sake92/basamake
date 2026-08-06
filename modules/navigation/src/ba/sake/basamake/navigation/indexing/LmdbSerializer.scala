package ba.sake.basamake.navigation.indexing

import org.lmdbjava.*
import ba.sake.basamake.navigation.{SymbolTable, InMemorySymbolTable, SymbolDefinition}
import scala.meta.internal.semanticdb.Range
import java.io.{ByteArrayOutputStream, ByteArrayInputStream, DataOutputStream, DataInputStream}
import java.nio.ByteBuffer

object LmdbSerializer {

  def save(table: SymbolTable, path: os.Path): Unit = {
    os.makeDir.all(path)
    val env = Env.create()
      .setMapSize(100L * 1024 * 1024) // 100MB
      .setMaxDbs(1)
      .open(path.toIO)

    try {
      val db = env.openDbi("symbols", DbiFlags.MDB_CREATE)
      val txn = env.txnWrite()

      table.all.foreach { d =>
        val keyBytes = d.symbol.getBytes("UTF-8")
        val keyBuf = ByteBuffer.allocateDirect(keyBytes.length)
        keyBuf.put(keyBytes).flip()
        val valueBytes = serialize(d)
        val valBuf = ByteBuffer.allocateDirect(valueBytes.length)
        valBuf.put(valueBytes).flip()
        db.put(txn, keyBuf, valBuf)
      }

      txn.commit()
    } finally {
      env.close()
    }
  }

  def load(path: os.Path): SymbolTable = {
    val table = new InMemorySymbolTable()
    val env = Env.create()
      .setMapSize(100L * 1024 * 1024)
      .setMaxDbs(1)
      .open(path.toIO, EnvFlags.MDB_RDONLY_ENV)

    try {
      val db = env.openDbi("symbols")
      val txn = env.txnRead()
      val cursor = db.iterate(txn)

      import scala.jdk.CollectionConverters.*
      cursor.iterator().asScala.foreach { entry =>
        val bytes = entry.`val`()
        val arr = new Array[Byte](bytes.remaining())
        bytes.get(arr)
        val d = deserialize(arr)
        table.add(d)
      }

      cursor.close()
      txn.close()
    } finally {
      env.close()
    }

    table
  }

  private def serialize(d: SymbolDefinition): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val dos = new DataOutputStream(bos)

    dos.writeUTF(d.symbol)
    dos.writeUTF(d.shortName)
    dos.writeBoolean(d.isType)
    dos.writeInt(d.range.startLine)
    dos.writeInt(d.range.startCharacter)
    dos.writeInt(d.range.endLine)
    dos.writeInt(d.range.endCharacter)
    dos.writeUTF(d.path.toString)

    dos.flush()
    bos.toByteArray
  }

  private def deserialize(bytes: Array[Byte]): SymbolDefinition = {
    val bis = new ByteArrayInputStream(bytes)
    val dis = new DataInputStream(bis)

    val symbol = dis.readUTF()
    val shortName = dis.readUTF()
    val isType = dis.readBoolean()
    val range = Range(
      startLine = dis.readInt(),
      startCharacter = dis.readInt(),
      endLine = dis.readInt(),
      endCharacter = dis.readInt()
    )
    val path = os.Path(dis.readUTF())

    SymbolDefinition(symbol, shortName, isType, range, path)
  }
}
