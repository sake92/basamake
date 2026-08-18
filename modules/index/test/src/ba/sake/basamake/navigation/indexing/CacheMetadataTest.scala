package ba.sake.basamake.index.indexing

import munit.FunSuite

class CacheMetadataTest extends FunSuite {

  private def tempDir(): os.Path = os.temp.dir(prefix = "cache-meta-test-")

  test("save/load roundtrip") {
    val dir = tempDir()
    val meta = CacheMetadata("/some/path.jar", 12345L, 67890L, List("com.example", "org.apache"), indexed = true, CacheMetadata.FormatVersion)
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
    val meta = CacheMetadata(src.toString, os.size(src), os.mtime(src), Nil, indexed = true, CacheMetadata.FormatVersion)
    assert(CacheMetadata.isValid(meta, src))
  }

  test("isValid: size change → false") {
    val dir = tempDir()
    val src = dir / "lib-sources.jar"
    os.write.over(src, "hello")
    val meta = CacheMetadata(src.toString, os.size(src), os.mtime(src), Nil, indexed = true, CacheMetadata.FormatVersion)
    os.write.over(src, "hello world longer")
    assert(!CacheMetadata.isValid(meta, src))
  }

  test("isValid: mtime change → false") {
    val dir = tempDir()
    val src = dir / "lib-sources.jar"
    os.write.over(src, "hello")
    val meta = CacheMetadata(src.toString, os.size(src), os.mtime(src), Nil, indexed = true, CacheMetadata.FormatVersion)
    Thread.sleep(10)
    os.write.over(src, "hello")
    assert(!CacheMetadata.isValid(meta, src))
  }

  test("isValid: missing source → false") {
    val dir = tempDir()
    val meta = CacheMetadata((dir / "gone.jar").toString, 1L, 2L, Nil, indexed = true, CacheMetadata.FormatVersion)
    assert(!CacheMetadata.isValid(meta, dir / "gone.jar"))
  }

  test("isValid: formatVersion mismatch → false (old-format cache must reindex)") {
    val dir = tempDir()
    val src = dir / "lib-sources.jar"
    os.write.over(src, "hello world")
    val meta = CacheMetadata(src.toString, os.size(src), os.mtime(src), Nil, indexed = true, formatVersion = 0)
    assert(!CacheMetadata.isValid(meta, src))
  }
}
