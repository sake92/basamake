package ba.sake.basamake.navigation.indexing

import munit.FunSuite

class CacheMetadataTest extends FunSuite {

  private def tempDir(): os.Path = os.temp.dir(prefix = "cache-meta-test-")

  test("save/load roundtrip") {
    val dir = tempDir()
    val meta = CacheMetadata("/some/path.jar", 12345L, 67890L, List("com.example", "org.apache"))
    CacheMetadata.save(dir, meta)
    assertEquals(CacheMetadata.load(dir), Some(meta))
  }

  test("load missing → None") {
    assertEquals(CacheMetadata.load(tempDir()), None)
  }

  test("load corrupt json → None") {
    val dir = tempDir()
    os.write.over(dir / CacheMetadata.FileName, "not json at all {")
    assertEquals(CacheMetadata.load(dir), None)
  }

  test("isValid: matching size+mtime → true") {
    val dir = tempDir()
    val src = dir / "lib-sources.jar"
    os.write.over(src, "hello world")
    val meta = CacheMetadata(src.toString, os.size(src), os.mtime(src), Nil)
    assert(CacheMetadata.isValid(meta, src))
  }

  test("isValid: size change → false") {
    val dir = tempDir()
    val src = dir / "lib-sources.jar"
    os.write.over(src, "hello")
    val meta = CacheMetadata(src.toString, os.size(src), os.mtime(src), Nil)
    os.write.over(src, "hello world longer")
    assert(!CacheMetadata.isValid(meta, src))
  }

  test("isValid: mtime change → false") {
    val dir = tempDir()
    val src = dir / "lib-sources.jar"
    os.write.over(src, "hello")
    val meta = CacheMetadata(src.toString, os.size(src), os.mtime(src), Nil)
    Thread.sleep(10)
    os.write.over(src, "hello")
    assert(!CacheMetadata.isValid(meta, src))
  }

  test("isValid: missing source → false") {
    val dir = tempDir()
    val meta = CacheMetadata((dir / "gone.jar").toString, 1L, 2L, Nil)
    assert(!CacheMetadata.isValid(meta, dir / "gone.jar"))
  }

  test("packagesOf: dotted packages sorted+distinct, default pkg skipped") {
    val table = new ba.sake.basamake.navigation.InMemorySymbolTable()
    def defOf(symbol: String) = ba.sake.basamake.navigation.SymbolDefinition(
      symbol, "x", isType = true,
      scala.meta.internal.semanticdb.Range(0, 0, 0, 0), os.pwd / "x.scala")
    table.add(defOf("org/apache/commons/net/FTPClient#"))
    table.add(defOf("org/apache/commons/net/FTPClient#connect()."))
    table.add(defOf("org/apache/commons/net/DNS#"))
    table.add(defOf("com/example/Util#"))
    table.add(defOf("NoPackage#"))

    assertEquals(CacheMetadata.packagesOf(table), List("com.example", "org.apache.commons.net"))
  }
}
