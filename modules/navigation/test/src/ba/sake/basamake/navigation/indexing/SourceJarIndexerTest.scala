package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

class SourceJarIndexerTest extends FunSuite, TestCacheRoot {

  // committed fixture — see modules/navigation/test/resources/jars/
  private val commonsNetSourcesJar =
    os.pwd / "modules" / "navigation" / "test" / "resources" / "jars" / "commons-net-3.9.0-sources.jar"

  // JDK sources — resolved from the running JVM, never a hardcoded path
  private val jdkSrcZip =
    os.Path(System.getProperty("java.home")) / "lib" / "src.zip"

  test("index commons-net source jar") {
    val jar = commonsNetSourcesJar
    require(os.exists(jar), s"Fixture jar missing: $jar")

    val fingerprint = "commons-net_3.9.0_bd5a1"
    cleanCache(fingerprint)
    SourceJarIndexer.index(jar, fingerprint)

    val indexPath = SourceJarIndexer.cacheRoot / fingerprint / "index.lmdb"
    assert(LmdbSerializer.get(indexPath, "org/apache/commons/net/SocketClient#").isDefined,
      "Should have indexed FTPClient")

    println(s"Indexed commons-net into $indexPath")
  }

  // JDK src.zip indexing takes >30s — skip in CI, use manual verification
  test("index JDK src.zip".ignore) {
    val srcZip = jdkSrcZip

    if (!os.exists(srcZip)) {
      println(s"Skipping test: $srcZip not found")
    } else {
      val fingerprint = "jdk_21.0.2_bd5a1f"
      SourceJarIndexer.index(srcZip, fingerprint)

      val indexPath = SourceJarIndexer.cacheRoot / fingerprint / "index.lmdb"
      assert(LmdbSerializer.get(indexPath, "java/util/UUID#").isDefined,
        "Should have java.util.UUID")
    }
  }

  test("reload from existing index") {
    val jar = commonsNetSourcesJar
    require(os.exists(jar), s"Fixture jar missing: $jar")

    val fingerprint = "commons-net_reload_bd5a1"
    cleanCache(fingerprint)

    SourceJarIndexer.index(jar, fingerprint)
    val first = LmdbSerializer.get(SourceJarIndexer.cacheRoot / fingerprint / "index.lmdb", "org/apache/commons/net/SocketClient#")
    SourceJarIndexer.index(jar, fingerprint) // should load from cache
    val second = LmdbSerializer.get(SourceJarIndexer.cacheRoot / fingerprint / "index.lmdb", "org/apache/commons/net/SocketClient#")

    assertEquals(first, second, "cache-hit path must yield the same definitions")
    assert(first.isDefined, "First index should have definitions")
  }

  test("extracts one source file on demand (per-file, not whole archive)") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir)

    val fingerprint = "test_extract_bd5a1f"
    cleanCache(fingerprint)
    SourceJarIndexer.index(jarPath, fingerprint)

    // indexing must NOT unpack sources eagerly
    val srcRoot = SourceJarIndexer.cacheRoot / fingerprint / "src"
    assert(!os.exists(srcRoot), "no src/ before extraction is requested")

    SourceJarIndexer.extractEntry(jarPath, fingerprint, "Foo.java")
    assert(os.exists(srcRoot / "Foo.java"), "Foo.java should be extracted")
    assert(!os.exists(srcRoot / "Baz.scala"), "only the requested file is extracted")
    assert(os.read(srcRoot / "Foo.java").contains("class Foo"), "extracted content should match")

    SourceJarIndexer.extractEntry(jarPath, fingerprint, "Baz.scala")
    assert(os.exists(srcRoot / "Baz.scala"), "second file extracts on demand")

    // idempotent — a second call must not corrupt or duplicate
    SourceJarIndexer.extractEntry(jarPath, fingerprint, "Foo.java")
    assert(os.read(srcRoot / "Foo.java").contains("class Foo"), "extraction must be idempotent")
  }

  test("writes valid metadata.json with packages") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir)

    val fingerprint = "test_metadata_bd5a1f"
    cleanCache(fingerprint)
    SourceJarIndexer.index(jarPath, fingerprint)

    val cacheDir = SourceJarIndexer.cacheRoot / fingerprint
    val meta = CacheMetadata.load(cacheDir)
    assert(meta.isDefined, "metadata.json should exist")
    assertEquals(meta.get.sourcePath, jarPath.toString)
    assertEquals(meta.get.sourceSize, os.size(jarPath))
    assertEquals(meta.get.sourceMtime, os.mtime(jarPath))
    assert(meta.get.packages.contains("com.example"), s"packages should contain com.example, got ${meta.get.packages}")
    assert(CacheMetadata.isValid(meta.get, jarPath), "metadata should be valid for unchanged jar")
  }

  test("stale metadata triggers reindex") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir)

    val fingerprint = "test_stale_bd5a1f"
    cleanCache(fingerprint)
    SourceJarIndexer.index(jarPath, fingerprint)
    val before = CacheMetadata.load(SourceJarIndexer.cacheRoot / fingerprint).get

    // change the jar → size/mtime mismatch → next index must rebuild
    os.write.append(jarPath, "trailing junk")
    SourceJarIndexer.index(jarPath, fingerprint)
    val after = CacheMetadata.load(SourceJarIndexer.cacheRoot / fingerprint).get

    assert(after.sourceSize == os.size(jarPath), "metadata should reflect the new jar")
    assert(after.sourceSize != before.sourceSize, "reindex must have happened")
    assert(LmdbSerializer.get(SourceJarIndexer.cacheRoot / fingerprint / "index.lmdb", "com/example/Foo#").isDefined,
      "reindexed index should still be queryable")
  }

  test("corrupt jar cleans partial cache and throws") {
    val tempDir = os.temp.dir()
    val jarPath = tempDir / "corrupt-sources.jar"
    os.write.over(jarPath, "this is definitely not a zip file")

    val fingerprint = "test_corrupt_bd5a1f"
    cleanCache(fingerprint)

    intercept[Exception] {
      SourceJarIndexer.index(jarPath, fingerprint)
    }
    assert(!os.exists(SourceJarIndexer.cacheRoot / fingerprint), "partial cache dir must be cleaned up")
  }

  test("index small test jar") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir)

    val fingerprint = "test_com.example_test_1.0.0_bd5a1f"
    cleanCache(fingerprint)

    SourceJarIndexer.index(jarPath, fingerprint)
    val indexPath = SourceJarIndexer.cacheRoot / fingerprint / "index.lmdb"

    assert(LmdbSerializer.get(indexPath, "com/example/Foo#").isDefined, "Should have com.example.Foo")
    assert(LmdbSerializer.get(indexPath, "com/example/Baz.").isDefined, "Should have com.example.Baz")

    println(s"Indexed test jar into $indexPath")
  }

  private def buildSmallJar(tempDir: os.Path): os.Path = {
    val jarPath = tempDir / "test-sources.jar"

    val javaFile = """package com.example;
public class Foo {
    public void bar() {}
}
"""

    val scalaFile = """package com.example
object Baz {
  def qux(): Unit = ()
}
"""

    val zip = new ZipOutputStream(new FileOutputStream(jarPath.toIO))
    try {
      val javaEntry = new ZipEntry("Foo.java")
      zip.putNextEntry(javaEntry)
      zip.write(javaFile.getBytes("UTF-8"))
      zip.closeEntry()

      val scalaEntry = new ZipEntry("Baz.scala")
      zip.putNextEntry(scalaEntry)
      zip.write(scalaFile.getBytes("UTF-8"))
      zip.closeEntry()
    } finally {
      zip.close()
    }
    jarPath
  }

  private def cleanCache(fingerprint: String): Unit = {
    val cacheDir = SourceJarIndexer.cacheRoot / fingerprint
    if (os.exists(cacheDir)) {
      os.remove.all(cacheDir)
    }
  }

  test("progress callback reports per-source-entry done/total") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir) // 2 source entries (Foo.java, Baz.scala)

    val fingerprint = "test_progress_bd5a1f"
    cleanCache(fingerprint)

    val events = scala.collection.mutable.ListBuffer[(Long, Long, String)]()
    SourceJarIndexer.index(jarPath, fingerprint, (done, total, name) => events += ((done, total, name)))

    assertEquals(events.last, (2L, 2L, jarPath.last), "total must count source entries only")
    assertEquals(events.map(_._1).toList, List(1L, 2L), "done must increment per source entry")
    assert(events.forall(_._2 == 2L), "every event carries the pre-counted total")
  }
}
