package ba.sake.basamake.util

import munit.FunSuite

class ProjectRootTest extends FunSuite {

  private def withRoot[T](f: os.Path => T): T = {
    val root = os.temp.dir(prefix = "proot-")
    try f(root)
    finally os.remove.all(root)
  }

  test("existing .basamake in an ancestor → that ancestor (sbt subfolder case)") {
    withRoot { root =>
      os.makeDir.all(root / "examples" / "hello" / ".basamake")
      os.makeDir.all(root / "examples" / "hello" / "sbt")
      val opened = root / "examples" / "hello" / "sbt"
      assertEquals(ProjectRoot.resolve(opened), root / "examples" / "hello")
    }
  }

  test(".git dir → git root") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / "sub" / "deep")
      assertEquals(ProjectRoot.resolve(root / "sub" / "deep"), root)
    }
  }

  test(".git file (worktree) → the worktree itself, not the main repo") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / ".worktrees" / "feat" / "src")
      os.write(root / ".worktrees" / "feat" / ".git", "gitdir: /main/.git/worktrees/feat\n")
      assertEquals(ProjectRoot.resolve(root / ".worktrees" / "feat" / "src"),
        root / ".worktrees" / "feat")
    }
  }

  test("fresh repo without .basamake → git root") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / "modules" / "main" / "src")
      assertEquals(ProjectRoot.resolve(root / "modules" / "main" / "src"), root)
    }
  }

  test("non-git folder without marker → opened folder") {
    withRoot { root =>
      os.makeDir.all(root / "a" / "b")
      assertEquals(ProjectRoot.resolve(root / "a" / "b"), root / "a" / "b")
    }
  }

  test("opened folder with both .git and .basamake → itself") {
    withRoot { root =>
      os.makeDir.all(root / ".git")
      os.makeDir.all(root / ".basamake")
      assertEquals(ProjectRoot.resolve(root), root)
    }
  }
}
