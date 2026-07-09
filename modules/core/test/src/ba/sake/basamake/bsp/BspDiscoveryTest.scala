package ba.sake.basamake.bsp

import java.nio.file.{Files, Path}
import munit.FunSuite

class BspDiscoveryTest extends FunSuite:

  test("recursive scan finds nested .bsp dirs") {
    val tmp = Files.createTempDirectory("basamake-test")
    try
      // Create root .bsp/sbt.json
      val rootBsp = tmp.resolve(".bsp")
      Files.createDirectories(rootBsp)
      Files.writeString(rootBsp.resolve("sbt.json"),
        """{"name":"sbt","argv":["sbt","bsp"]}""")

      // Create nested examples/.bsp/scalacli.json
      val nestedBsp = tmp.resolve("examples").resolve(".bsp")
      Files.createDirectories(nestedBsp)
      Files.writeString(nestedBsp.resolve("scalacli.json"),
        """{"name":"scala-cli","argv":["scala-cli","bsp"]}""")

      val results = BspDiscovery.discover(tmp)
      assertEquals(results.size, 2)
      val names = results.map(_.content.name).toSet
      assertEquals(names, Set("sbt", "scala-cli"))
    finally
      deleteRecursively(tmp)
  }

  test("parseSingleSpec returns None for non-json files") {
    val tmp = Files.createTempDirectory("basamake-test")
    try
      val txtFile = tmp.resolve("not-json.txt")
      Files.writeString(txtFile, "hello")
      // parseSingleSpec should return None for non-.json file
      // (or for any file that doesn't parse as BspDiscoverySpec)
      val result = BspDiscovery.parseSingleSpec(txtFile)
      // The default implementation checks .json extension, so may or may not
      // try to parse. Either way, non-BSP JSON should fail gracefully.
      assert(result.isEmpty)
    finally
      deleteRecursively(tmp)
  }

  test("workspace with no .bsp dirs returns empty list") {
    val tmp = Files.createTempDirectory("basamake-test")
    try
      val results = BspDiscovery.discover(tmp)
      assertEquals(results, Nil)
    finally
      deleteRecursively(tmp)
  }

  test("parseSingleSpec returns content.name from JSON") {
    val tmp = Files.createTempDirectory("basamake-test")
    try
      val bspDir = tmp.resolve(".bsp")
      Files.createDirectories(bspDir)
      Files.writeString(bspDir.resolve("my-cool-tool.json"),
        """{"name":"my-cool-tool","argv":["tool","bsp"]}""")

      val result = BspDiscovery.parseSingleSpec(bspDir.resolve("my-cool-tool.json"))
      assert(result.isDefined)
      assertEquals(result.get.content.name, "my-cool-tool")
    finally
      deleteRecursively(tmp)
  }

  test("invalid JSON returns None gracefully") {
    val tmp = Files.createTempDirectory("basamake-test")
    try
      val bspDir = tmp.resolve(".bsp")
      Files.createDirectories(bspDir)
      Files.writeString(bspDir.resolve("bad.json"), "not valid json at all {{{")

      val result = BspDiscovery.parseSingleSpec(bspDir.resolve("bad.json"))
      assert(result.isEmpty)
    finally
      deleteRecursively(tmp)
  }

  private def deleteRecursively(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).forEach(deleteRecursively)
    Files.deleteIfExists(path)
