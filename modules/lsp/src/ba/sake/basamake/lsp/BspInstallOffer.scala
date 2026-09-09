package ba.sake.basamake.lsp

import java.nio.charset.StandardCharsets
import com.typesafe.scalalogging.StrictLogging

/** Each supported build tool owns its project markers and install command.
  * Detection uses the nearest matching ancestor, so nested builds win. */
private[lsp] enum BspBuildTool(val displayName: String, val markers: List[os.RelPath]) {
  case Deder extends BspBuildTool("Deder", List(os.rel / "deder.pkl"))
  case Sbt extends BspBuildTool("sbt", List(os.rel / "build.sbt", os.rel / "project" / "build.properties"))
  case Mill extends BspBuildTool("Mill", List(
    os.rel / "build.mill",
    os.rel / "build.mill.yaml",
    os.rel / "build.sc",
    os.rel / "mill"
  ))

  def installCommand(root: os.Path): List[String] = this match {
    case Deder => List("deder", "bsp", "install")
    case Sbt => List(BspBuildTool.commandOrWrapper(root, "sbt"), "bspConfig")
    case Mill => List(BspBuildTool.commandOrWrapper(root, "mill"), "mill.bsp.BSP/install")
  }
}

private[lsp] final case class BspInstallCandidate(
    tool: BspBuildTool,
    root: os.Path,
    marker: os.RelPath
) {
  def configKey(workspaceRoot: os.Path): String = (root / marker).relativeTo(workspaceRoot).toString
}

private[lsp] object BspBuildTool {
  private[lsp] def commandOrWrapper(root: os.Path, name: String): String = {
    val wrapper = root / name
    if (os.isFile(wrapper)) wrapper.toString else name
  }

  def nearestFor(file: os.Path, workspaceRoot: os.Path): Option[BspInstallCandidate] = {
    var current = if (os.isDir(file)) file else file / os.up
    while (current.startsWith(workspaceRoot)) {
      toolAt(current) match {
        case found @ Some(_) => return found
        case None =>
      }
      if (current == workspaceRoot) return None
      current = current / os.up
    }
    None
  }

  private def toolAt(root: os.Path): Option[BspInstallCandidate] = {
    BspBuildTool.values.iterator.flatMap { tool =>
      tool.markers.find(marker => os.isFile(root / marker)).map(marker => BspInstallCandidate(tool, root, marker))
    }.toSeq.headOption
  }
}

/** Runs an explicitly user-approved BSP setup command. Process output is only
  * logged; it must never inherit the language server's stdout transport. */
private[lsp] object BspInstaller extends StrictLogging {
  def install(tool: BspBuildTool, root: os.Path): Either[String, Unit] = {
    val command = tool.installCommand(root)
    try {
      logger.info(s"Installing BSP support for ${tool.displayName} in $root: ${command.mkString(" ")}")
      val process = new ProcessBuilder(command*).directory(root.toIO).redirectErrorStream(true).start()
      val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val exit = process.waitFor()
      if (exit == 0) {
        if (output.nonEmpty) logger.info(s"BSP installation output for $root:\n$output")
        Right(())
      } else {
        logger.warn(s"BSP installation failed for $root (exit $exit): $output")
        Left(s"${tool.displayName} BSP installation failed (exit $exit). See Basamake logs for details.")
      }
    } catch {
      case e: Exception =>
        logger.warn(s"BSP installation could not start for $root: ${e.getMessage}", e)
        Left(s"Could not run ${tool.displayName} BSP installation: ${e.getMessage}")
    }
  }
}
