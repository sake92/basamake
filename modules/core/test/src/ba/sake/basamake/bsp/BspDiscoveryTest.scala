package ba.sake.basamake.bsp

import munit.FunSuite

class BspDiscoveryTest extends FunSuite:

  test("recursive scan finds nested .bsp dirs") {
    val tmp = os.temp.dir(prefix = "basamake-test")
    try
      // Create root .bsp/sbt.json
      val rootBsp = tmp / ".bsp"
      os.makeDir.all(rootBsp)
      os.write(rootBsp / "sbt.json", """{"name":"sbt","argv":["sbt","bsp"]}""")

      // Create nested examples/.bsp/scalacli.json
      val nestedBsp = tmp / "examples/.bsp"
      os.makeDir.all(nestedBsp)
      os.write(nestedBsp / "scalacli.json",
        """{"name":"scala-cli","argv":["scala-cli","bsp"]}""")

      val results = BspDiscovery.discover(tmp)
      assertEquals(results.size, 2)
      val names = results.map(_.content.name).toSet
      assertEquals(names, Set("sbt", "scala-cli"))
    finally
      deleteRecursively(tmp)
  }

  test("parseSingleSpec returns None for non-json files") {
    val tmp = os.temp.dir(prefix = "basamake-test")
    try
      val txtFile = tmp / "not-json.txt"
      os.write(txtFile, "hello")
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
    val tmp = os.temp.dir(prefix = "basamake-test")
    try
      val results = BspDiscovery.discover(tmp)
      assertEquals(results, Nil)
    finally
      deleteRecursively(tmp)
  }

  test("parseSingleSpec returns content.name from JSON") {
    val tmp = os.temp.dir(prefix = "basamake-test")
    try
      val bspDir = tmp / ".bsp"
      os.makeDir.all(bspDir)
      os.write(bspDir / "my-cool-tool.json",
        """{"name":"my-cool-tool","argv":["tool","bsp"]}""")

      val result = BspDiscovery.parseSingleSpec(bspDir / "my-cool-tool.json")
      assert(result.isDefined)
      assertEquals(result.get.content.name, "my-cool-tool")
    finally
      deleteRecursively(tmp)
  }

  test("invalid JSON returns None gracefully") {
    val tmp = os.temp.dir(prefix = "basamake-test")
    try
      val bspDir = tmp / ".bsp"
      os.makeDir.all(bspDir)
      os.write(bspDir / "bad.json", "not valid json at all {{{")

      val result = BspDiscovery.parseSingleSpec(bspDir / "bad.json")
      assert(result.isEmpty)
    finally
      deleteRecursively(tmp)
  }

  private def deleteRecursively(path: os.Path): Unit =
    if os.isDir(path) then
      os.list(path).foreach(deleteRecursively)
    os.remove.all(path)
