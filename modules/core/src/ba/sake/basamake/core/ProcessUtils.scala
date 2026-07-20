package ba.sake.basamake.core

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object ProcessUtils {
  def terminateProcessTree(process: java.lang.Process): Int = {
    if process == null then return 0

    var signaled = 0
    try {
      signaled = terminateHandleTree(process.toHandle)
      try process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      catch case _: InterruptedException => Thread.currentThread().interrupt()
    } catch {
      case NonFatal(_) =>
        if process.isAlive then
          process.destroyForcibly()
          signaled = 1
    }
    signaled
  }

  def terminateProcessHandleTree(root: java.lang.ProcessHandle): Int = {
    if root == null then return 0
    terminateHandleTree(root)
  }

  private def terminateHandleTree(root: java.lang.ProcessHandle): Int = {
    val descendants = root.descendants().iterator().asScala.toList.reverse
    var signaled = 0

    descendants.foreach { handle =>
      if handle.isAlive then
        handle.destroy()
        if handle.isAlive then handle.destroyForcibly()
        signaled += 1
    }

    // Don't try to kill the current JVM process
    if root.isAlive && root.pid() != java.lang.ProcessHandle.current().pid() then
      root.destroy()
      if root.isAlive then root.destroyForcibly()
      signaled += 1

    signaled
  }
}
