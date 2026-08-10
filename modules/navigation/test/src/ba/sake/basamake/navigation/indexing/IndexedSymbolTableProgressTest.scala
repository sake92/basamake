package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

class IndexedSymbolTableProgressTest extends FunSuite, TestCacheRoot {

  private def cacheDir(fp: String) = SourceJarIndexer.cacheRoot / os.RelPath(fp)

  private def cleanCache(fp: String): Unit = {
    if (os.exists(cacheDir(fp))) os.remove.all(cacheDir(fp))
  }

  private def eventually(cond: => Boolean, timeoutMs: Long = 20000): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(50)
    cond
  }

  /** jar with package com.example: class Foo, object Baz */
  private def buildJar(tempDir: os.Path, name: String): os.Path = {
    val jarPath = tempDir / name
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
      zip.putNextEntry(new ZipEntry("Foo.java")); zip.write(javaFile.getBytes("UTF-8")); zip.closeEntry()
      zip.putNextEntry(new ZipEntry("Baz.scala")); zip.write(scalaFile.getBytes("UTF-8")); zip.closeEntry()
    } finally zip.close()
    jarPath
  }

  test("jobs are queued by priority: scala-lang jars before normal jars") {
    val tempDir = os.temp.dir()
    val normalJar = buildJar(tempDir, "zzz-normal-sources.jar")
    val scalaJar = buildJar(tempDir, "scala3-library_3-3.8.4-sources.jar")
    cleanCache(Fingerprint.fromJarPath(normalJar))
    cleanCache(Fingerprint.fromJarPath(scalaJar))

    // workerCount=0 → no worker threads → the queue stays observable
    val deps = new IndexedSymbolTable(workerCount = 0)
    deps.ensureIndexed(List(normalJar, scalaJar))

    assertEquals(deps.queuedJobs,
      List("scala3-library_3-3.8.4-sources.jar", "zzz-normal-sources.jar"),
      "scala-lang jar must be picked before the normal jar despite later enqueue")
  }

  test("org.scala-lang group via sibling POM also gets priority") {
    val tempDir = os.temp.dir()
    // Fingerprint.fromJarPath reads <same-dir>/<artifact>.pom for the groupId
    val pomJar = buildJar(tempDir, "foo-sources.jar")
    os.write(tempDir / "foo.pom",
      """<project><groupId>org.scala-lang</groupId><artifactId>foo</artifactId><version>1.0</version></project>""")
    val normalJar = buildJar(tempDir, "zzz-normal-sources.jar")
    cleanCache(Fingerprint.fromJarPath(pomJar))
    cleanCache(Fingerprint.fromJarPath(normalJar))

    val deps = new IndexedSymbolTable(workerCount = 0)
    deps.ensureIndexed(List(normalJar, pomJar))

    assertEquals(deps.queuedJobs.head, "foo-sources.jar",
      "a jar whose POM group is org.scala-lang must beat a normal jar")
  }

  test("JDK job is queued first (priority 0) when src.zip exists") {
    val srcZip = os.Path(System.getProperty("java.home")) / "lib" / "src.zip"
    assume(os.exists(srcZip), "JDK src.zip required for this test")

    val tempDir = os.temp.dir()
    val normalJar = buildJar(tempDir, "zzz-normal-sources.jar")
    cleanCache(Fingerprint.fromJarPath(normalJar))

    val deps = new IndexedSymbolTable(workerCount = 0)
    deps.ensureJdkIndexed()
    deps.ensureIndexed(List(normalJar))

    assertEquals(deps.queuedJobs.head, "src.zip", "JDK must be first in the queue")
  }

  test("background indexing reports jar-level progress (0..N)") {
    val tempDir = os.temp.dir()
    val jarA = buildJar(tempDir, "a-sources.jar")
    val jarB = buildJar(tempDir, "b-sources.jar")
    cleanCache(Fingerprint.fromJarPath(jarA))
    cleanCache(Fingerprint.fromJarPath(jarB))

    val listener = new RecordingProgressListener
    val deps = new IndexedSymbolTable(progressListener = listener)
    deps.ensureIndexed(List(jarA, jarB))

    assert(eventually(listener.ofPhase(IndexingPhase.Dependencies).lastOption.exists(e => e._1 == e._2)),
      "both jars must complete")
    val evs = listener.ofPhase(IndexingPhase.Dependencies) // snapshot AFTER the wait
    assertEquals(evs.head, (0L, 1L, "a-sources.jar"), "first event: 0/1 while the first jar is enqueued")
    val last = evs.last
    assertEquals(last._1, 2L, "done must reach 2")
    assertEquals(last._2, 2L, "total must reach 2")
    assert(last._3.startsWith("Indexed "), s"final message should say Indexed, got ${last._3}")
  }
}
