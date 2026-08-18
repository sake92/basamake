package ba.sake.basamake.bsp

import munit.FunSuite
import ba.sake.basamake.index.indexing.GitIgnoreEngine

class BspDiscoveryTest extends FunSuite {

  private def engine(root: os.Path): GitIgnoreEngine =
    new GitIgnoreEngine(root, exemptLastNames = Set(".bsp"))

  private val sbtJson =
    """{"name":"sbt","version":"1","bspVersion":"2.1.0","languages":["scala"],"argv":["true"]}"""

  test("gitignored .bsp dir is still discovered") {
    val root = os.temp.dir(prefix = "bsp-disc-")
    try {
      os.write(root / ".gitignore", ".bsp/\n")
      os.makeDir.all(root / ".bsp")
      os.write(root / ".bsp" / "sbt.json", sbtJson)
      val specs = BspDiscovery.discover(root, engine(root))
      assertEquals(specs.map(_.content.name), List("sbt"))
    } finally os.remove.all(root)
  }

  test(".bsp inside gitignored .worktrees is not discovered") {
    val root = os.temp.dir(prefix = "bsp-disc-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".worktrees/\n")
      os.makeDir.all(root / ".bsp")
      os.write(root / ".bsp" / "sbt.json", sbtJson)
      os.makeDir.all(root / ".worktrees" / "wt" / ".bsp")
      os.write(root / ".worktrees" / "wt" / ".bsp" / "sbt.json", sbtJson)
      val specs = BspDiscovery.discover(root, engine(root))
      assertEquals(specs.map(_.content.name), List("sbt"))
    } finally os.remove.all(root)
  }

  test(".bsp inside node_modules is not discovered") {
    val root = os.temp.dir(prefix = "bsp-disc-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "node_modules/\n")
      os.makeDir.all(root / ".bsp")
      os.write(root / ".bsp" / "sbt.json", sbtJson)
      os.makeDir.all(root / "node_modules" / "pkg" / ".bsp")
      os.write(root / "node_modules" / "pkg" / ".bsp" / "sbt.json", sbtJson)
      val specs = BspDiscovery.discover(root, engine(root))
      assertEquals(specs.map(_.content.name), List("sbt"))
    } finally os.remove.all(root)
  }

  test(".bsp inside a nested git repo is not discovered") {
    val root = os.temp.dir(prefix = "bsp-disc-")
    try {
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / ".bsp")
      os.write(root / ".bsp" / "sbt.json", sbtJson)
      os.makeDir.all(root / "nested" / ".git")
      os.makeDir.all(root / "nested" / ".bsp")
      os.write(root / "nested" / ".bsp" / "sbt.json", sbtJson)
      val specs = BspDiscovery.discover(root, engine(root))
      assertEquals(specs.map(_.content.name), List("sbt"))
    } finally os.remove.all(root)
  }

  test("nothing is discovered inside .git internals (submodule git dirs)") {
    val root = os.temp.dir(prefix = "bsp-disc-")
    try {
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / ".bsp")
      os.write(root / ".bsp" / "sbt.json", sbtJson)
      os.makeDir.all(root / ".git" / "modules" / "sub" / ".bsp")
      os.write(root / ".git" / "modules" / "sub" / ".bsp" / "sbt.json", sbtJson)
      val specs = BspDiscovery.discover(root, engine(root))
      assertEquals(specs.map(_.content.name), List("sbt"))
    } finally os.remove.all(root)
  }
}
