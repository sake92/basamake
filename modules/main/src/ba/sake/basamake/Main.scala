package ba.sake.basamake

import java.io.PrintStream
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.launch.LSPLauncher
import mainargs.*
import ba.sake.basamake.lsp.BasamakeLanguageServer
import ba.sake.basamake.util.{LoggingUtils, ProjectRoot}

object Main extends StrictLogging {

  def main(args: Array[String]): Unit = Parser(this).runOrExit(args)

  @main
  def run(@arg(doc = "Path to workspace directory, defaults to current working directory")
          workspace: String = os.pwd.toString,
          stdio: Flag = Flag(true),
          rest: Leftover[String]
          ) = {

    if rest.value.nonEmpty then
      println(s"Unknown arguments: ${rest.value.mkString(" ")}")

    val openedDir = os.Path(workspace)
    val projectRoot = ProjectRoot.resolve(openedDir)
    val marker =
      if os.exists(projectRoot / ".git") then ".git"
      else if os.isDir(projectRoot / ".basamake") then ".basamake"
      else "fallback (opened folder)"
    LoggingUtils.configureFileLogging(projectRoot)
    logger.info(s"Basamake LSP server starting; opened: $openedDir, project root: $projectRoot (marker: $marker)")
    logger.info(s"Java: ${System.getProperty("java.version")}")

    val autoFlushOut = PrintStream(System.out, true, "UTF-8")
    val server = BasamakeLanguageServer(projectRoot)
    val launcher = LSPLauncher.createServerLauncher(server, System.in, autoFlushOut)
    server.connect(launcher.getRemoteProxy)

    // JVM shutdown hook — fires on SIGTERM/SIGINT/VS Code closing the LSP process.
    // Mirrors the old Main: ensures deder processes don't outlive the LSP server.
    Runtime.getRuntime.addShutdownHook(new Thread(() => server.cleanup(), "basamake-shutdown-hook"))

    // starts async message processing; future completes when stdin closes
    val future = launcher.startListening()

    // Block until LSP transport closes (stdin EOF) or exit() calls System.exit.
    try future.get()
    finally server.cleanup()
  }
  
}
