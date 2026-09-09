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
    assertEquals(cfg.debugSymbolTableDump, None, "dump is opt-in and defaults off")
  }

  test("load: parses debugSymbolTableDump opt-in flag") {
    val proj = root / "dump"
    os.makeDir.all(proj / ".basamake")
    os.write(proj / ".basamake" / "config.json", """{"debugSymbolTableDump": true}""")
    val cfg = BasamakeConfig.load(proj)
    assertEquals(cfg.debugSymbolTableDump, Some(true))
  }

  test("load: missing config file → defaults") {
    val proj = root / "empty"
    os.makeDir.all(proj)
    val cfg = BasamakeConfig.load(proj)
    assertEquals(cfg.ignorePatterns, Nil)
    assertEquals(cfg.bspOverrides, Nil)
    assertEquals(cfg.debugSymbolTableDump, None)
    assertEquals(cfg.offerInstallBlacklist, Nil)
  }

  test("blacklistInstallOffer: persists an explicit BSP-install dismissal per marker") {
    val proj = root / "install-offer"
    assert(BasamakeConfig.blacklistInstallOffer(proj, "a/b/c/build.mill"))
    assert(BasamakeConfig.blacklistInstallOffer(proj, "build.sbt"))
    assertEquals(BasamakeConfig.load(proj).offerInstallBlacklist, List("a/b/c/build.mill", "build.sbt"))
  }

  test("ensureBspDefaults: writes missing defaults without overwriting user values") {
    val proj = root / "default-template"
    os.makeDir.all(proj)

    val cfg = BasamakeConfig.ensureBspDefaults(proj, List(".bsp/mill.json", "app/.bsp/sbt.json"))
    assertEquals(
      cfg.bspOverrides,
      List(
        BspOverride(".bsp/mill.json", true, Some(600), Some(120)),
        BspOverride("app/.bsp/sbt.json", true, Some(600), Some(120))
      )
    )

    os.write.over(proj / ".basamake" / "config.json",
      """{"bspOverrides":[{"bspFile":".bsp/mill.json","enabled":false,"compileTimeoutSec":42}],"ignorePatterns":["keep/"]}""")
    val updated = BasamakeConfig.ensureBspDefaults(proj, List(".bsp/mill.json", "app/.bsp/sbt.json"))
    assertEquals(updated.bspOverrides.head, BspOverride(".bsp/mill.json", false, Some(42), None))
    assertEquals(updated.bspOverrides(1), BspOverride("app/.bsp/sbt.json", true, Some(600), Some(120)))
    assertEquals(updated.ignorePatterns, List("keep/"))
  }
}
