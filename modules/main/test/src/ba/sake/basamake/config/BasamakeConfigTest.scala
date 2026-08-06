package ba.sake.basamake.config

import munit.FunSuite

class BasamakeConfigTest extends FunSuite {

  private val root = os.temp.dir(prefix = "bconfig-")

  override def afterAll(): Unit = os.remove.all(root)

  test("load: parses ignorePatterns from config.json") {
    val proj = root / "proj"
    os.makeDir.all(proj / ".basamake")
    os.write(proj / ".basamake" / "config.json",
      """{"ignorePatterns": ["node_modules/", "!node_modules/keep.scala"]}""")
    val cfg = BasamakeConfig.load(proj)
    assertEquals(cfg.ignorePatterns, List("node_modules/", "!node_modules/keep.scala"))
    assertEquals(cfg.bspOverrides, Nil)
  }

  test("load: missing config file → defaults") {
    val proj = root / "empty"
    os.makeDir.all(proj)
    val cfg = BasamakeConfig.load(proj)
    assertEquals(cfg.ignorePatterns, Nil)
    assertEquals(cfg.bspOverrides, Nil)
  }
}
