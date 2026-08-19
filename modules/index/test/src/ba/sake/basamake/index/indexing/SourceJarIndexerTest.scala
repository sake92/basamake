package ba.sake.basamake.index.indexing

import munit.FunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

class SourceJarIndexerTest extends FunSuite, TestCacheRoot {

  // committed fixture — see test/resources/jars/
  private val commonsNetSourcesJar =
    os.pwd / "test" / "resources" / "jars" / "commons-net-3.9.0-sources.jar"

  // JDK sources — resolved from the running JVM, never a hardcoded path
  private val jdkSrcZip =
    os.Path(System.getProperty("java.home")) / "lib" / "src.zip"

  test("index commons-net source jar") {
    val jar = commonsNetSourcesJar
    require(os.exists(jar), s"Fixture jar missing: $jar")

    val fingerprint = "commons-net_3.9.0_bd5a1"
    cleanCache(fingerprint)
    SourceJarIndexer.index(jar, fingerprint, testCacheRoot)

    val indexPath = testCacheRoot / fingerprint / "index.lmdb"
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
      SourceJarIndexer.index(srcZip, fingerprint, testCacheRoot)

      val indexPath = testCacheRoot / fingerprint / "index.lmdb"
      assert(LmdbSerializer.get(indexPath, "java/util/UUID#").isDefined,
        "Should have java.util.UUID")
    }
  }

  test("reload from existing index") {
    val jar = commonsNetSourcesJar
    require(os.exists(jar), s"Fixture jar missing: $jar")

    val fingerprint = "commons-net_reload_bd5a1"
    cleanCache(fingerprint)

    SourceJarIndexer.index(jar, fingerprint, testCacheRoot)
    val first = LmdbSerializer.get(testCacheRoot / fingerprint / "index.lmdb", "org/apache/commons/net/SocketClient#")
    SourceJarIndexer.index(jar, fingerprint, testCacheRoot) // should load from cache
    val second = LmdbSerializer.get(testCacheRoot / fingerprint / "index.lmdb", "org/apache/commons/net/SocketClient#")

    assertEquals(first, second, "cache-hit path must yield the same definitions")
    assert(first.isDefined, "First index should have definitions")
  }

  test("extracts one source file on demand (per-file, not whole archive)") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir)

    val fingerprint = "test_extract_bd5a1f"
    cleanCache(fingerprint)
    SourceJarIndexer.index(jarPath, fingerprint, testCacheRoot)

    // indexing must NOT unpack sources eagerly
    val srcRoot = testCacheRoot / fingerprint / "src"
    assert(!os.exists(srcRoot), "no src/ before extraction is requested")

    SourceJarIndexer.extractEntry(jarPath, fingerprint, "Foo.java", testCacheRoot)
    assert(os.exists(srcRoot / "Foo.java"), "Foo.java should be extracted")
    assert(!os.exists(srcRoot / "Baz.scala"), "only the requested file is extracted")
    assert(os.read(srcRoot / "Foo.java").contains("class Foo"), "extracted content should match")

    SourceJarIndexer.extractEntry(jarPath, fingerprint, "Baz.scala", testCacheRoot)
    assert(os.exists(srcRoot / "Baz.scala"), "second file extracts on demand")

    // idempotent — a second call must not corrupt or duplicate
    SourceJarIndexer.extractEntry(jarPath, fingerprint, "Foo.java", testCacheRoot)
    assert(os.read(srcRoot / "Foo.java").contains("class Foo"), "extraction must be idempotent")
  }

  test("writes valid metadata.json — packages empty without a classes sibling") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir)

    val fingerprint = "test_metadata_bd5a1f"
    cleanCache(fingerprint)
    SourceJarIndexer.index(jarPath, fingerprint, testCacheRoot)

    val cacheDir = testCacheRoot / fingerprint
    val meta = CacheMetadata.load(cacheDir)
    assert(meta.isDefined, "metadata.json should exist")
    assertEquals(meta.get.sourcePath, jarPath.toString)
    assertEquals(meta.get.sourceSize, os.size(jarPath))
    assertEquals(meta.get.sourceMtime, os.mtime(jarPath))
    assert(meta.get.indexed, "a full index must be marked indexed")
    assertEquals(meta.get.packages, List.empty[String],
      "no classes sibling → packages come from nowhere and must stay empty (unfilterable)")
    assert(CacheMetadata.isValid(meta.get, jarPath), "metadata should be valid for unchanged jar")
  }

  test("full index of a sources+classes jar pair records classes-jar packages") {
    val tempDir = os.temp.dir()
    val sourcesJar = tempDir / "foo_3-1.0.0-sources.jar"
    val classesJar = tempDir / "foo_3-1.0.0.jar"

    val srcZip = new ZipOutputStream(new FileOutputStream(sourcesJar.toIO))
    try {
      srcZip.putNextEntry(new ZipEntry("com/example/Foo.java"))
      srcZip.write("package com.example;\npublic class Foo {}\n".getBytes("UTF-8"))
      srcZip.closeEntry()
    } finally srcZip.close()

    val clsZip = new ZipOutputStream(new FileOutputStream(classesJar.toIO))
    try {
      clsZip.putNextEntry(new ZipEntry("com/example/Foo.class")); clsZip.write(Array[Byte](1, 2)); clsZip.closeEntry()
    } finally clsZip.close()

    assertEquals(SourceJarIndexer.classesJarOf(sourcesJar), Some(classesJar))

    val fingerprint = "test_pair_packages_bd5a1f"
    cleanCache(fingerprint)
    SourceJarIndexer.index(sourcesJar, fingerprint, testCacheRoot)

    val meta = CacheMetadata.load(testCacheRoot / fingerprint).get
    assert(meta.indexed, "a full index must be marked indexed")
    assertEquals(meta.packages, List("com.example"), "packages must come from the REAL classes jar listing")
  }

  test("stale metadata triggers reindex") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir)

    val fingerprint = "test_stale_bd5a1f"
    cleanCache(fingerprint)
    SourceJarIndexer.index(jarPath, fingerprint, testCacheRoot)
    val before = CacheMetadata.load(testCacheRoot / fingerprint).get

    // change the jar → size/mtime mismatch → next index must rebuild
    os.write.append(jarPath, "trailing junk")
    SourceJarIndexer.index(jarPath, fingerprint, testCacheRoot)
    val after = CacheMetadata.load(testCacheRoot / fingerprint).get

    assert(after.sourceSize == os.size(jarPath), "metadata should reflect the new jar")
    assert(after.sourceSize != before.sourceSize, "reindex must have happened")
    assert(LmdbSerializer.get(testCacheRoot / fingerprint / "index.lmdb", "com/example/Foo#").isDefined,
      "reindexed index should still be queryable")
  }

  test("indexed=false metadata is not a cache hit — reindexes") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir)

    val fingerprint = "test_indexed_flag_bd5a1f"
    cleanCache(fingerprint)
    SourceJarIndexer.index(jarPath, fingerprint, testCacheRoot)

    val cacheDir = testCacheRoot / fingerprint
    val meta = CacheMetadata.load(cacheDir).get
    assert(meta.indexed, "a fresh index must be marked indexed")

    // rewrite the metadata as package-only (indexed = false): index() must NOT
    // take the cache-hit path even though everything else (size/mtime/LMDB) is valid
    CacheMetadata.save(cacheDir, meta.copy(indexed = false))
    SourceJarIndexer.index(jarPath, fingerprint, testCacheRoot)

    assertEquals(CacheMetadata.load(cacheDir).map(_.indexed), Some(true),
      "indexed=false metadata must force a reindex")
  }

  test("corrupt jar cleans partial cache and throws") {
    val tempDir = os.temp.dir()
    val jarPath = tempDir / "corrupt-sources.jar"
    os.write.over(jarPath, "this is definitely not a zip file")

    val fingerprint = "test_corrupt_bd5a1f"
    cleanCache(fingerprint)

    intercept[Exception] {
      SourceJarIndexer.index(jarPath, fingerprint, testCacheRoot)
    }
    assert(!os.exists(testCacheRoot / fingerprint), "partial cache dir must be cleaned up")
  }

  test("index small test jar") {
    val tempDir = os.temp.dir()
    val jarPath = buildSmallJar(tempDir)

    val fingerprint = "test_com.example_test_1.0.0_bd5a1f"
    cleanCache(fingerprint)

    SourceJarIndexer.index(jarPath, fingerprint, testCacheRoot)
    val indexPath = testCacheRoot / fingerprint / "index.lmdb"

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
    val cacheDir = testCacheRoot / fingerprint
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
    SourceJarIndexer.index(jarPath, fingerprint, testCacheRoot, (done, total, name) => events += ((done, total, name)))

    assertEquals(events.last, (2L, 2L, jarPath.last), "total must count source entries only")
    assertEquals(events.map(_._1).toList, List(1L, 2L), "done must increment per source entry")
    assert(events.forall(_._2 == 2L), "every event carries the pre-counted total")
  }

  test("classesJarOf: sources jar sibling in a coursier-style dir") {
    val dir = os.temp.dir()
    os.write(dir / "foo_3-1.0.0-sources.jar", "x")
    os.write(dir / "foo_3-1.0.0.jar", "x")
    assertEquals(SourceJarIndexer.classesJarOf(dir / "foo_3-1.0.0-sources.jar"), Some(dir / "foo_3-1.0.0.jar"))
    assertEquals(SourceJarIndexer.classesJarOf(dir / "foo_3-1.0.0.jar"), None, "only -sources.jar names map")
  }

  test("packagesOfClassesJar: zip directories are the packages") {
    val dir = os.temp.dir()
    val jar = dir / "foo_3-1.0.0.jar"
    val zip = new ZipOutputStream(new FileOutputStream(jar.toIO))
    try {
      zip.putNextEntry(new ZipEntry("com/example/Foo.class")); zip.write(Array[Byte](1, 2)); zip.closeEntry()
      zip.putNextEntry(new ZipEntry("org/bar/Baz.class")); zip.write(Array[Byte](1)); zip.closeEntry()
      zip.putNextEntry(new ZipEntry("module-info.class")); zip.write(Array[Byte](1)); zip.closeEntry()
      zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF")); zip.write(Array[Byte](1)); zip.closeEntry()
    } finally zip.close()
    assertEquals(SourceJarIndexer.packagesOfClassesJar(jar), Set("com.example", "org.bar"))
  }

  test("packagesOfClassesJar: jar without classes yields empty set") {
    val dir = os.temp.dir()
    val jar = dir / "empty.jar"
    val zip = new ZipOutputStream(new FileOutputStream(jar.toIO))
    try { zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF")); zip.write(Array[Byte](1)); zip.closeEntry() }
    finally zip.close()
    assertEquals(SourceJarIndexer.packagesOfClassesJar(jar), Set.empty[String])
  }
}
