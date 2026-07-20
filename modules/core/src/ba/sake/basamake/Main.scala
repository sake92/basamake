package ba.sake.basamake

import java.io.PrintStream
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.launch.LSPLauncher
import ba.sake.basamake.lsp.BasamakeLanguageServer
import ba.sake.basamake.util.LoggingUtils

object Main extends StrictLogging {

  def main(args: Array[String]): Unit = {
    val workspacePath = parseWorkspace(args)
    LoggingUtils.configureFileLogging(workspacePath)

    logger.info("Basamake LSP server starting")
    logger.info(s"Workspace: $workspacePath")
    logger.info(s"Java: ${System.getProperty("java.version")}")

    // auto-flush LSP messages
    val autoFlushOut = PrintStream(System.out, true, "UTF-8")
    val server = BasamakeLanguageServer()
    val shutdownHook = Thread(() => server.cleanup(), "basamake-shutdown-hook")
    Runtime.getRuntime.addShutdownHook(shutdownHook)
    val launcher = LSPLauncher.createServerLauncher(server, System.in, autoFlushOut)
    server.connect(launcher.getRemoteProxy)
    logger.info("LSP launcher created, listening on stdio...")

    // starts async message processing; future completes when stdin closes
    val future = launcher.startListening()
    logger.info("LSP message processor started")

    // Block until LSP transport closes (stdin EOF) or exit() calls System.exit.
    // When VS Code closes, stdin reaches EOF, the future completes.
    try future.get()
    finally server.cleanup()
    logger.info("LSP server stopped")
  }

  // TODO use mainargs
  private def parseWorkspace(args: Array[String]): os.Path =
    args.sliding(2).collectFirst:
      case Array("--workspace", path) => os.Path(path)
    .getOrElse(os.pwd)
}