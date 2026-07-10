package ba.sake.basamake.manager

import munit.FunSuite

class BuildServerManagerTest extends FunSuite:

  test("classifyBspChanges detects new/deleted/modified for one churn step") {
    val known = Set(os.Path("/ws/.bsp/sbt.json", os.pwd))
    val current = Set(os.Path("/ws/.bsp/scalacli.json", os.pwd))
    val changed = Set(
      os.Path("/ws/.bsp/sbt.json", os.pwd),
      os.Path("/ws/.bsp/scalacli.json", os.pwd)
    )

    val (newFiles, deletedFiles, modifiedFiles) =
      BuildServerManager.classifyBspChanges(known, current, changed)

    assertEquals(newFiles, Set(os.Path("/ws/.bsp/scalacli.json", os.pwd)))
    assertEquals(deletedFiles, Set(os.Path("/ws/.bsp/sbt.json", os.pwd)))
    assertEquals(modifiedFiles, Set.empty)
  }

  test("classifyBspChanges keeps modified files only in intersection") {
    val known = Set(
      os.Path("/ws/.bsp/sbt.json", os.pwd),
      os.Path("/ws/examples/.bsp/scalacli.json", os.pwd)
    )
    val current = known
    val changed = Set(
      os.Path("/ws/examples/.bsp/scalacli.json", os.pwd),
      os.Path("/ws/unrelated.txt", os.pwd)
    )

    val (newFiles, deletedFiles, modifiedFiles) =
      BuildServerManager.classifyBspChanges(known, current, changed)

    assertEquals(newFiles, Set.empty)
    assertEquals(deletedFiles, Set.empty)
    assertEquals(modifiedFiles, Set(os.Path("/ws/examples/.bsp/scalacli.json", os.pwd)))
  }

  test("classifyBspChanges stays consistent over repeated delete/re-add churn") {
    val sbt = os.Path("/ws/.bsp/sbt.json", os.pwd)
    val scalaCli = os.Path("/ws/.bsp/scalacli.json", os.pwd)

    var known = Set(sbt)

    for i <- 1 to 12 do
      val current = if i % 2 == 0 then Set(sbt) else Set(scalaCli)
      val changed = Set(sbt, scalaCli)
      val (newFiles, deletedFiles, modifiedFiles) =
        BuildServerManager.classifyBspChanges(known, current, changed)

      assertEquals(newFiles, current -- known)
      assertEquals(deletedFiles, known -- current)
      assertEquals(modifiedFiles, known.intersect(current))

      known = current
  }
