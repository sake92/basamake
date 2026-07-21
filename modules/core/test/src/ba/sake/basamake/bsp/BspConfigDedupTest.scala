package ba.sake.basamake.bsp

import munit.FunSuite

class BspConfigDedupTest extends FunSuite {

  private val testDir = os.temp.dir(prefix = "bsp-dedup-")

  test("identical BSP file content (same name+argv) is equal (dedup check)") {
    val json = """{"name":"sbt","version":"1.0","bspVersion":"2.1.0","languages":["scala"],"argv":["java","-jar","sbt-launch.jar","-bsp"]}"""
    val path1 = testDir / "sbt1.json"
    val path2 = testDir / "sbt2.json"
    os.write(path1, json)
    os.write(path2, json)

    val spec1 = BspDiscovery.parseSingleSpec(path1).get
    val spec2 = BspDiscovery.parseSingleSpec(path2).get

    // content comparison — the dedup check
    assertEquals(spec1.content, spec2.content,
      "Same name+argv must be equal for content-based dedup")
  }

  test("different BSP file content (different argv) is not equal (reload proceeds)") {
    val json1 = """{"name":"sbt","version":"1.0","bspVersion":"2.1.0","languages":["scala"],"argv":["java","-jar","sbt-launch.jar","-bsp"]}"""
    val json2 = """{"name":"sbt","version":"2.0","bspVersion":"2.1.0","languages":["scala"],"argv":["java","-jar","new-sbt.jar","-bsp"]}"""
    val path1 = testDir / "sbt-1.json"
    val path2 = testDir / "sbt-2.json"
    os.write(path1, json1)
    os.write(path2, json2)

    val spec1 = BspDiscovery.parseSingleSpec(path1).get
    val spec2 = BspDiscovery.parseSingleSpec(path2).get

    // different argv → not equal → reload should proceed
    assertNotEquals(spec1.content, spec2.content,
      "Different argv must NOT be equal — reload must proceed")
  }

  test("content comparison skips non-BspDiscoveryFile fields (path, debounceMs, compileTimeoutSec)") {
    val json = """{"name":"test","version":"1.0","bspVersion":"2.1.0","languages":["scala"],"argv":["echo","hello"]}"""
    val path1 = testDir / "a.json"
    val path2 = testDir / "b.json"
    os.write(path1, json)
    os.write(path2, json)

    val spec1 = BspDiscovery.parseSingleSpec(path1).get.copy(debounceMs = 100L, compileTimeoutSec = 999L)
    val spec2 = BspDiscovery.parseSingleSpec(path2).get.copy(debounceMs = 500L, compileTimeoutSec = 600L)

    // content must be equal even though debounceMs/compileTimeoutSec differ
    assertEquals(spec1.content, spec2.content,
      "Content dedup must ignore non-content fields (debounceMs, compileTimeoutSec)")
    // but the full specs are different (different paths, debounceMs)
    assertNotEquals(spec1, spec2,
      "Full specs should differ because of different paths")
  }

  override def afterAll(): Unit = {
    os.remove.all(testDir)
  }
}
