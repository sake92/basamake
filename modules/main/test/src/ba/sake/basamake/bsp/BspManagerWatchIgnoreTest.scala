package ba.sake.basamake.bsp

import munit.FunSuite

class BspManagerWatchIgnoreTest extends FunSuite {

  test("watchIgnored: .bsp paths are never ignored (even when gitignored)") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".bsp/\n")
      os.makeDir.all(root / ".bsp")
      val mgr = BspManager.forTesting(root)
      mgr.initializeForTestingOnlyDiscover()
      assert(!mgr.watchIgnored(root / ".bsp" / "sbt.json"),
        ".bsp changes must still reach the watcher")
    } finally os.remove.all(root)
  }

  test("watchIgnored: gitignored .worktrees and generated dirs are ignored") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".worktrees/\n*.class\n")
      os.makeDir.all(root / ".worktrees" / "wt")
      val mgr = BspManager.forTesting(root)
      mgr.initializeForTestingOnlyDiscover()
      assert(mgr.watchIgnored(root / ".worktrees" / "wt" / "Foo.scala"))
      assert(mgr.watchIgnored(root / "out" / "Foo.class"))
      assert(!mgr.watchIgnored(root / "src" / "Main.scala"))
    } finally os.remove.all(root)
  }

  test("watchIgnored: .basamake/logs and paths outside the root are ignored") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      val mgr = BspManager.forTesting(root)
      mgr.initializeForTestingOnlyDiscover()
      assert(mgr.watchIgnored(root / ".basamake" / "logs" / "basamake.log"))
      assert(mgr.watchIgnored(root / os.up / "elsewhere" / "x.scala"))
    } finally os.remove.all(root)
  }

  test("onFileChanged: .gitignore edit reloads the engine") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.class\n")
      os.makeDir.all(root / "src")
      val mgr = BspManager.forTesting(root)
      mgr.initializeForTestingOnlyDiscover()
      assert(!mgr.watchIgnored(root / "src" / "Main.scala"))
      // user adds a pattern that ignores scala sources
      os.write.over(root / ".gitignore", "*.scala\n")
      mgr.onFileChanged(Set(root / ".gitignore"))
      assert(mgr.watchIgnored(root / "src" / "Main.scala"),
        "engine should reload after .gitignore change")
    } finally os.remove.all(root)
  }

  test("watchIgnored: .git internals and nested repos are ignored") {
    val root = os.temp.dir(prefix = "bsp-watch-")
    try {
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / ".git" / "objects")
      os.makeDir.all(root / "nested" / ".git")
      os.makeDir.all(root / "nested" / "src")
      os.makeDir.all(root / "src")
      val mgr = BspManager.forTesting(root)
      mgr.initializeForTestingOnlyDiscover()
      assert(mgr.watchIgnored(root / ".git" / "index.lock"))
      assert(mgr.watchIgnored(root / ".git" / "objects" / "ab" / "cdef"))
      assert(mgr.watchIgnored(root / "nested"))
      assert(mgr.watchIgnored(root / "nested" / "src" / "Main.scala"))
      assert(!mgr.watchIgnored(root / "src" / "Main.scala"))
      assert(!mgr.watchIgnored(root / ".gitignore"))
    } finally os.remove.all(root)
  }
}
