package ba.sake.basamake.bsp

import munit.FunSuite
import ba.sake.basamake.config.BasamakeConfig

class WatchFilterTest extends FunSuite {

  private def filterFor(root: os.Path): WatchFilter = new WatchFilter(root, BasamakeConfig.load(root))

  test("isIgnored: .bsp paths are never ignored (even when gitignored)") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".bsp/\n")
      os.makeDir.all(root / ".bsp")
      val watchFilter = filterFor(root)
      assert(!watchFilter.isIgnored(root / ".bsp" / "sbt.json"),
        ".bsp changes must still reach the watcher")
    } finally os.remove.all(root)
  }

  test("isIgnored: gitignored .worktrees and generated dirs are ignored") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".worktrees/\n*.class\n")
      os.makeDir.all(root / ".worktrees" / "wt")
      val watchFilter = filterFor(root)
      assert(watchFilter.isIgnored(root / ".worktrees" / "wt" / "Foo.scala"))
      assert(watchFilter.isIgnored(root / "out" / "Foo.class"))
      assert(!watchFilter.isIgnored(root / "src" / "Main.scala"))
    } finally os.remove.all(root)
  }

  test("isIgnored: .basamake/logs and paths outside the root are ignored") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      val watchFilter = filterFor(root)
      assert(watchFilter.isIgnored(root / ".basamake" / "logs" / "basamake.log"))
      assert(watchFilter.isIgnored(root / os.up / "elsewhere" / "x.scala"))
    } finally os.remove.all(root)
  }

  test("reload: .gitignore edit rebuilds the engine") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.class\n")
      os.makeDir.all(root / "src")
      val watchFilter = filterFor(root)
      assert(!watchFilter.isIgnored(root / "src" / "Main.scala"))
      // user adds a pattern that ignores scala sources
      os.write.over(root / ".gitignore", "*.scala\n")
      watchFilter.reload()
      assert(watchFilter.isIgnored(root / "src" / "Main.scala"),
        "engine should reload after .gitignore change")
    } finally os.remove.all(root)
  }

  test("isIgnored: .git internals and nested repos are ignored") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / ".git" / "objects")
      os.makeDir.all(root / "nested" / ".git")
      os.makeDir.all(root / "nested" / "src")
      os.makeDir.all(root / "src")
      val watchFilter = filterFor(root)
      assert(watchFilter.isIgnored(root / ".git" / "index.lock"))
      assert(watchFilter.isIgnored(root / ".git" / "objects" / "ab" / "cdef"))
      assert(watchFilter.isIgnored(root / "nested"))
      assert(watchFilter.isIgnored(root / "nested" / "src" / "Main.scala"))
      assert(!watchFilter.isIgnored(root / "src" / "Main.scala"))
      assert(!watchFilter.isIgnored(root / ".gitignore"))
    } finally os.remove.all(root)
  }
}
