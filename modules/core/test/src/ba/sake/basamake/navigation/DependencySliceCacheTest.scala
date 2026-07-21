package ba.sake.basamake.navigation

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import munit.FunSuite

class DependencySliceCacheTest extends FunSuite {

  private def bumpMtime(path: os.Path): Unit =
    Files.setLastModifiedTime(
      path.toNIO,
      FileTime.fromMillis(Files.getLastModifiedTime(path.toNIO).toMillis + 60_000)
    )

  private def writeSource(dir: os.Path, name: String, obj: String): os.Path = {
    val f = dir / name
    os.write(f, s"object $obj { def m = 1 }")
    f
  }

  test("second index call returns cached slice instances (no re-parse)") {
    val tmp = os.temp.dir(prefix = "depcache-hit")
    try {
      val dep = tmp / "dep1"
      os.makeDir(dep)
      writeSource(dep, "A.scala", "Alpha")
      val cache = new DependencySliceCache()
      val uri = dep.toNIO.toUri.toString

      val r1 = DependencySourceIndexing.indexDependencySources(tmp, List(uri), cache)
      val r2 = DependencySourceIndexing.indexDependencySources(tmp, List(uri), cache)

      assert(r1.nonEmpty)
      assertEquals(r1.size, r2.size)
      assert(r1.zip(r2).forall { case (a, b) => (a `eq` b) }, "expected cached instances on second call")
    } finally os.remove.all(tmp)
  }

  test("touching a file invalidates only its own dep") {
    val tmp = os.temp.dir(prefix = "depcache-granular")
    try {
      val dep1 = tmp / "dep1"
      val dep2 = tmp / "dep2"
      os.makeDir(dep1)
      os.makeDir(dep2)
      val f1 = writeSource(dep1, "A.scala", "Alpha")
      writeSource(dep2, "B.scala", "Beta")
      val cache = new DependencySliceCache()
      val uri1 = dep1.toNIO.toUri.toString
      val uri2 = dep2.toNIO.toUri.toString

      val r1 = DependencySourceIndexing.indexDependencySources(tmp, List(uri1, uri2), cache)

      bumpMtime(f1)
      val r2 = DependencySourceIndexing.indexDependencySources(tmp, List(uri1, uri2), cache)

      val r1dep1 = r1.filter(_.symbolDefinitions.keys.exists(_.contains("Alpha")))
      val r2dep1 = r2.filter(_.symbolDefinitions.keys.exists(_.contains("Alpha")))
      val r1dep2 = r1.filter(_.symbolDefinitions.keys.exists(_.contains("Beta")))
      val r2dep2 = r2.filter(_.symbolDefinitions.keys.exists(_.contains("Beta")))

      assert(r1dep1.nonEmpty && r2dep1.nonEmpty && r1dep2.nonEmpty && r2dep2.nonEmpty)
      assert(!r1dep1.zip(r2dep1).forall { case (a, b) => a `eq` b }, "touched dep must be re-parsed")
      assert(r1dep2.zip(r2dep2).forall { case (a, b) => a `eq` b }, "untouched dep must stay cached")
    } finally os.remove.all(tmp)
  }

  test("parallel indexing of many files yields complete results") {
    val tmp = os.temp.dir(prefix = "depcache-parallel")
    try {
      val dep = tmp / "dep"
      os.makeDir(dep)
      val count = 60
      (1 to count).foreach(i => writeSource(dep, s"F$i.scala", s"Obj$i"))
      val cache = new DependencySliceCache()
      val uri = dep.toNIO.toUri.toString

      val r = DependencySourceIndexing.indexDependencySources(tmp, List(uri), cache)

      val symbols = r.flatMap(_.symbolDefinitions.keys).toSet
      val missing = (1 to count).map(i => s"Obj$i").filterNot(symbols.contains)
      assertEquals(missing.toList, Nil)
    } finally os.remove.all(tmp)
  }

  test("archive dep invalidated on jar mtime bump") {
    val tmp = os.temp.dir(prefix = "depcache-jar")
    try {
      val jarPath = tmp / "lib-sources.jar"
      val jarFile = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(jarPath.toIO))
      try {
        val entry = new java.util.zip.ZipEntry("Gamma.scala")
        jarFile.putNextEntry(entry)
        jarFile.write("object Gamma { def g = 1 }".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        jarFile.closeEntry()
      } finally jarFile.close()

      val cache = new DependencySliceCache()
      val jarUri = s"jar:${jarPath.toNIO.toUri.toString}!/Gamma.scala"

      val r1 = DependencySourceIndexing.indexDependencySources(tmp, List(jarUri), cache)
      assert(r1.nonEmpty)

      bumpMtime(jarPath)
      val r2 = DependencySourceIndexing.indexDependencySources(tmp, List(jarUri), cache)

      assert(!r1.zip(r2).forall { case (a, b) => a `eq` b }, "jar mtime bump must invalidate cache entry")
    } finally os.remove.all(tmp)
  }
}
