package ba.sake.basamake

import com.typesafe.scalalogging.StrictLogging
import ba.sake.basamake.lsp.BasamakeLanguageServer
import ba.sake.basamake.util.LoggingUtils
import org.eclipse.lsp4j.launch.LSPLauncher
import java.nio.file.{Path, Paths}

object Main extends StrictLogging:

  def main(args: Array[String]): Unit =
    // Parse --workspace from args before any logging
    val workspacePath = parseWorkspace(args)
    LoggingUtils.configureFileLogging(workspacePath)

    logger.info("Basamake LSP server starting")
    logger.info(s"Workspace: $workspacePath")
    logger.info(s"Java: ${System.getProperty("java.version")}")

    // Wrap stdout in auto-flush so LSP messages go out immediately
    val autoFlushOut = new java.io.PrintStream(System.out, true, "UTF-8")

    val server = BasamakeLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.in, autoFlushOut)
    server.connect(launcher.getRemoteProxy)

    logger.info("LSP launcher created, listening on stdio...")

    launcher.startListening() // starts async message processing on lsp4j thread pool
    logger.info("LSP message processor started")

    // Keep JVM alive until exit() calls System.exit
    java.util.concurrent.locks.LockSupport.park()
    logger.info("LSP server stopped")

  private def parseWorkspace(args: Array[String]): Path =
    args.sliding(2).collectFirst:
      case Array("--workspace", path) => Paths.get(path)
    .getOrElse(Paths.get("."))
