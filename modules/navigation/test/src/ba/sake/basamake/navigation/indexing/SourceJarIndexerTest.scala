package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

class SourceJarIndexerTest extends FunSuite {

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
    val table = SourceJarIndexer.index(jar, fingerprint)

    assert(table.all.nonEmpty, "Should have indexed some definitions")
    val hasApacheSymbols = table.all.exists(_.symbol.contains("apache"))
    assert(hasApacheSymbols, "Should have indexed apache symbols")

    println(s"Indexed ${table.all.size} symbols from commons-net")
  }

  // JDK src.zip indexing takes >30s — skip in CI, use manual verification
  test("index JDK src.zip".ignore) {
    val srcZip = jdkSrcZip

    if (!os.exists(srcZip)) {
      println(s"Skipping test: $srcZip not found")
    } else {
      val fingerprint = "jdk_21.0.2_bd5a1f"
      val table = SourceJarIndexer.index(srcZip, fingerprint)

      assert(table.all.nonEmpty, "Should have indexed JDK definitions")
      val hasUUID = table.get("java/util/UUID#").isDefined
      assert(hasUUID, "Should have java.util.UUID")

      println(s"Indexed ${table.all.size} symbols from JDK")
    }
  }

  test("reload from existing index") {
    val jar = commonsNetSourcesJar
    require(os.exists(jar), s"Fixture jar missing: $jar")

    val fingerprint = "commons-net_reload_bd5a1"
    cleanCache(fingerprint)

    val table1 = SourceJarIndexer.index(jar, fingerprint)
    val table2 = SourceJarIndexer.index(jar, fingerprint) // should load from cache

    assert(table1.all.nonEmpty, "First index should have definitions")
    assertEquals(table1.all.size, table2.all.size)
  }

  private def cleanCache(fingerprint: String): Unit = {
    val cacheDir = os.home / ".basamake" / "deps" / fingerprint
    if (os.exists(cacheDir)) {
      os.remove.all(cacheDir)
    }
  }

  test("index small test jar") {
    val tempDir = os.temp.dir()
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

    val fingerprint = "test_com.example_test_1.0.0_bd5a1f"
    cleanCache(fingerprint)

    val table = SourceJarIndexer.index(jarPath, fingerprint)

    assert(table.all.nonEmpty, s"Should have indexed definitions. Got ${table.all.size}")
    val hasFoo = table.get("com/example/Foo#").isDefined
    assert(hasFoo, "Should have com.example.Foo")

    val hasBaz = table.get("com/example/Baz.").isDefined
    assert(hasBaz, "Should have com.example.Baz")

    println(s"Indexed ${table.all.size} symbols from test jar")
  }
}
