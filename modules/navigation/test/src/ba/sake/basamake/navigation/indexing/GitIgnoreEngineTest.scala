package ba.sake.basamake.navigation.indexing

import munit.FunSuite

class GitIgnoreEngineTest extends FunSuite {

  private def withRoot[T](f: os.Path => T): T = {
    val root = os.temp.dir(prefix = "gignore-")
    try f(root)
    finally os.remove.all(root)
  }

  test("root .gitignore: dir pattern prunes subtree") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "build/\n")
      val engine = new GitIgnoreEngine(root)
      assert(engine.isIgnored(root / "build", isDir = true))
      assert(engine.isIgnored(root / "build" / "out" / "x.scala", isDir = false))
      assert(!engine.isIgnored(root / "src", isDir = true))
      assert(!engine.isIgnored(root / "src" / "Main.scala", isDir = false))
    }
  }

  test("nested .gitignore applies relative to its own dir") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.class\n")
      os.makeDir.all(root / "sub")
      os.write(root / "sub" / ".gitignore", "!Important.class\n/deep/\n")
      val engine = new GitIgnoreEngine(root)
      // root pattern matches any depth via filename
      assert(engine.isIgnored(root / "Other.class", isDir = false))
      assert(engine.isIgnored(root / "sub" / "Other.class", isDir = false))
      // nested negation wins over root pattern
      assert(!engine.isIgnored(root / "sub" / "Important.class", isDir = false))
      // nested anchored dir pattern applies only below sub/
      assert(engine.isIgnored(root / "sub" / "deep", isDir = true))
      assert(!engine.isIgnored(root / "deep", isDir = true))
      assert(!engine.isIgnored(root / "sub" / "x" / "deep", isDir = true))
    }
  }

  test("ancestor chain: repo-root .gitignore above the walk root is honored") {
    withRoot { base =>
      os.makeDir.all(base / ".git")
      os.write(base / ".gitignore", "ignored/\n")
      val root = base / "proj"
      os.makeDir.all(root / "src")
      val engine = new GitIgnoreEngine(root)
      assert(engine.isIgnored(root / "ignored", isDir = true))
      assert(!engine.isIgnored(root / "src", isDir = true))
    }
  }

  test("no .git in ancestor chain: only the walk root's own .gitignore is honored") {
    withRoot { base =>
      os.write(base / ".gitignore", "ignored/\n")
      val root = base / "proj"
      os.makeDir.all(root / "src")
      os.write(root / ".gitignore", "src/\n")
      val engine = new GitIgnoreEngine(root)
      // base/.gitignore must NOT apply (no git boundary)
      assert(!engine.isIgnored(root / "ignored", isDir = true))
      assert(engine.isIgnored(root / "src", isDir = true))
    }
  }

  test("worktree: .git as a file stops the ancestor chain") {
    withRoot { base =>
      os.makeDir.all(base / ".git")
      os.write(base / ".gitignore", "ignored/\n")
      val root = base / "wt"
      os.makeDir.all(root / "src")
      os.write(root / ".git", "gitdir: /main/.git/worktrees/wt\n")
      val engine = new GitIgnoreEngine(root)
      // boundary is the worktree itself — base/.gitignore must NOT apply
      assert(!engine.isIgnored(root / "ignored", isDir = true))
      assert(!engine.isIgnored(root / "src", isDir = true))
    }
  }

  test("no gitignore anywhere: nothing is ignored") {
    withRoot { root =>
      os.makeDir.all(root / "src")
      val engine = new GitIgnoreEngine(root)
      assert(!engine.isIgnored(root / "src" / "Main.scala", isDir = false))
      assert(!engine.isIgnored(root / "node_modules", isDir = true))
    }
  }

  test("extraRootPatterns merge: config patterns override gitignore (last match wins)") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.log\n")
      val engine = new GitIgnoreEngine(root, extraRootPatterns = Vector("!important.log"))
      assert(engine.isIgnored(root / "debug.log", isDir = false))
      assert(!engine.isIgnored(root / "important.log", isDir = false))
    }
  }

  test("exemptLastNames: exempted names are never ignored") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".bsp/\n")
      val exempt = new GitIgnoreEngine(root, exemptLastNames = Set(".bsp"))
      assert(!exempt.isIgnored(root / ".bsp", isDir = true))
      assert(!exempt.isIgnored(root / ".bsp" / "sbt.json", isDir = false))
      val strict = new GitIgnoreEngine(root)
      assert(strict.isIgnored(root / ".bsp", isDir = true))
    }
  }

  test("reload: re-parses base layers after .gitignore change") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", "*.class\n")
      val engine = new GitIgnoreEngine(root)
      assert(engine.isIgnored(root / "Foo.class", isDir = false))
      assert(!engine.isIgnored(root / "Foo.scala", isDir = false))
      os.write.over(root / ".gitignore", "*.scala\n")
      engine.reload()
      assert(!engine.isIgnored(root / "Foo.class", isDir = false))
      assert(engine.isIgnored(root / "Foo.scala", isDir = false))
    }
  }

  test("file under an ignored dir is ignored (ancestor pruning)") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.write(root / ".gitignore", ".worktrees/\n")
      val engine = new GitIgnoreEngine(root)
      assert(engine.isIgnored(root / ".worktrees" / "wt" / "Foo.scala", isDir = false))
      assert(engine.isIgnored(root / ".worktrees" / "wt", isDir = true))
    }
  }

  test("paths outside the root are ignored (safe default)") {
    withRoot { root =>
      val engine = new GitIgnoreEngine(root)
      assert(engine.isIgnored(root / os.up / "elsewhere" / "x.scala", isDir = false))
    }
  }

  test("outside-root check wins over exemption (exempt names only apply inside the root)") {
    withRoot { root =>
      val engine = new GitIgnoreEngine(root, exemptLastNames = Set(".bsp"))
      assert(engine.isIgnored(root / os.up / "elsewhere" / ".bsp", isDir = true))
    }
  }
}
