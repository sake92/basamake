package ba.sake.basamake

import java.io.PrintStream
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.lsp4j.launch.LSPLauncher
import mainargs.*
import ba.sake.basamake.lsp.BasamakeLanguageServer

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
    println(s"Workspace: $workspace")
    val autoFlushOut = PrintStream(System.out, true, "UTF-8")
    val server = BasamakeLanguageServer(os.Path(workspace))
    val launcher = LSPLauncher.createServerLauncher(server, System.in, autoFlushOut)
    server.connect(launcher.getRemoteProxy)

    // starts async message processing; future completes when stdin closes
    val future = launcher.startListening()

    // Block until LSP transport closes (stdin EOF) or exit() calls System.exit.
    // When VS Code closes, stdin reaches EOF, the future completes.
    try future.get()
    finally () // TODO cleanup
  }
  
}
