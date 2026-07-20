package ba.sake.basamake.manager

import munit.FunSuite

class BuildServerManagerTest extends FunSuite:

  private final class FakeProcess(initiallyAlive: Boolean) extends Process:
    private var alive = initiallyAlive
    var destroyForciblyCalls = 0

    override def getOutputStream = new java.io.ByteArrayOutputStream()
    override def getInputStream = new java.io.ByteArrayInputStream(Array.emptyByteArray)
    override def getErrorStream = new java.io.ByteArrayInputStream(Array.emptyByteArray)
    override def waitFor(): Int = 0
    override def waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
    override def exitValue(): Int = 0
    override def destroy(): Unit = ()
    override def isAlive(): Boolean = alive
    override def destroyForcibly(): Process =
      destroyForciblyCalls += 1
      alive = false
      this

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

  test("terminateProcess kills alive process") {
    val p = FakeProcess(initiallyAlive = true)
    val killed = BuildServerManager.terminateProcess(p)
    assert(killed)
    assertEquals(p.destroyForciblyCalls, 1)
  }

  test("terminateProcess is no-op for dead process") {
    val p = FakeProcess(initiallyAlive = false)
    val killed = BuildServerManager.terminateProcess(p)
    assert(!killed)
    assertEquals(p.destroyForciblyCalls, 0)
  }

  test("terminateProcesses kills only alive processes and returns killed count") {
    val p1 = FakeProcess(initiallyAlive = true)
    val p2 = FakeProcess(initiallyAlive = false)
    val p3 = FakeProcess(initiallyAlive = true)

    val killed = BuildServerManager.terminateProcesses(List(p1, p2, p3))
    assertEquals(killed, 2)
    assertEquals(p1.destroyForciblyCalls, 1)
    assertEquals(p2.destroyForciblyCalls, 0)
    assertEquals(p3.destroyForciblyCalls, 1)
  }

  test("currentProcessDescendants includes spawned child process") {
    val p = new java.lang.ProcessBuilder("bash", "-lc", "sleep 60").start()
    try {
      val descendants = BuildServerManager.currentProcessDescendants()
      assert(descendants.exists(_.pid() == p.pid()))
    } finally {
      p.destroyForcibly()
      p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
    }
  }
