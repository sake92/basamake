package ba.sake.basamake.lsp

import munit.FunSuite
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.{DidOpenTextDocumentParams, InitializeParams, TextDocumentItem}
import ba.sake.basamake.config.BasamakeConfig

class BspInstallOfferTest extends FunSuite {
  private def fixture(name: String): os.Path = {
    val root = os.temp.dir(prefix = s"basamake-$name-")
    os.makeDir.all(root / "src" / "main" / "scala")
    os.write(root / "src" / "main" / "scala" / "Main.scala", "object Main")
    root
  }

  test("nearest build marker wins for nested projects") {
    val root = fixture("nearest")
    try {
      os.write(root / "build.sbt", "")
      val nested = root / "nested"
      os.makeDir.all(nested / "src")
      os.write(nested / "deder.pkl", "")
      val source = nested / "src" / "Main.scala"
      os.write(source, "object Main")

      assertEquals(BspBuildTool.nearestFor(source, root),
        Some(BspInstallCandidate(BspBuildTool.Deder, nested, os.rel / "deder.pkl")))
    } finally os.remove.all(root)
  }

  test("detects deder, sbt, and mill markers") {
    val root = fixture("markers")
    try {
      val source = root / "src" / "main" / "scala" / "Main.scala"
      os.write(root / "deder.pkl", "")
      assertEquals(BspBuildTool.nearestFor(source, root).map(_.tool), Some(BspBuildTool.Deder))
      os.remove(root / "deder.pkl")
      os.write(root / "build.sbt", "")
      assertEquals(BspBuildTool.nearestFor(source, root).map(_.tool), Some(BspBuildTool.Sbt))
      os.remove(root / "build.sbt")
      os.write(root / "build.mill", "")
      assertEquals(BspBuildTool.nearestFor(source, root).map(_.tool), Some(BspBuildTool.Mill))
      os.remove(root / "build.mill")
      os.write(root / "build.mill.yaml", "")
      assertEquals(BspBuildTool.nearestFor(source, root).map(_.tool), Some(BspBuildTool.Mill))
    } finally os.remove.all(root)
  }

  test("installation commands use project wrappers when present") {
    val root = fixture("commands")
    try {
      os.write(root / "sbt", "")
      os.write(root / "mill", "")
      assertEquals(BspBuildTool.Deder.installCommand(root), List("deder", "bsp", "install"))
      assertEquals(BspBuildTool.Sbt.installCommand(root), List((root / "sbt").toString, "bspConfig"))
      assertEquals(BspBuildTool.Mill.installCommand(root), List((root / "mill").toString, "mill.bsp.BSP/install"))
    } finally os.remove.all(root)
  }

  test("explicit dismissal persists and prevents later BSP-install offers") {
    val root = fixture("prompt")
    try {
      os.write(root / "deder.pkl", "")
      os.write.over(root / ".basamake" / "config.json", "{\"enableJdkIndexing\":false}", createFolders = true)
      val source = root / "src" / "main" / "scala" / "Main.scala"
      val client = new TestLanguageClient
      client.respondToMessageRequestsWith("Do not offer again")
      val server = new BasamakeLanguageServer(root)
      server.connect(client)
      server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)

      def open(): Unit = server.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(source.toNIO.toUri.toString, "scala", 1, os.read(source))))
      open()
      val deadline = System.currentTimeMillis() + 5000
      while (client.shownMessageRequests.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(20)
      assertEquals(client.shownMessageRequests.size, 1)
      val request = client.shownMessageRequests.head
      assertEquals(request.getMessage, "No BSP configuration found for this Deder project. Install it?")
      assertEquals(request.getActions.get(0).getTitle, "Install")
      assertEquals(request.getActions.get(1).getTitle, "Do not offer again")

      val dismissedBy = System.currentTimeMillis() + 5000
      while (!BasamakeConfig.load(root).offerInstallBlacklist.contains("deder.pkl") && System.currentTimeMillis() < dismissedBy) Thread.sleep(20)
      assert(BasamakeConfig.load(root).offerInstallBlacklist.contains("deder.pkl"))
      server.cleanup()

      val restartedClient = new TestLanguageClient
      val restarted = new BasamakeLanguageServer(root)
      restarted.connect(restartedClient)
      restarted.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS)
      restarted.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(source.toNIO.toUri.toString, "scala", 1, os.read(source))))
      Thread.sleep(100)
      assertEquals(restartedClient.shownMessageRequests, Nil)

      // Manual config edits are picked up at the next file-open boundary.
      os.write.over(root / ".basamake" / "config.json", "{\"enableJdkIndexing\":false}")
      Thread.sleep(500) // allow the real workspace watcher to reload config
      restarted.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(source.toNIO.toUri.toString, "scala", 1, os.read(source))))
      val reenabledDeadline = System.currentTimeMillis() + 5000
      while (restartedClient.shownMessageRequests.isEmpty && System.currentTimeMillis() < reenabledDeadline) Thread.sleep(20)
      assertEquals(restartedClient.shownMessageRequests.size, 1)
      assert(restartedClient.shownMessageRequests.head.getMessage.contains("Deder project"))

      val nestedRoot = root / "nested"
      val nestedSource = nestedRoot / "src" / "Nested.scala"
      os.makeDir.all(nestedSource / os.up)
      os.write(nestedRoot / "build.mill", "")
      os.write(nestedSource, "object Nested")
      restarted.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(nestedSource.toNIO.toUri.toString, "scala", 1, os.read(nestedSource))))
      val nestedDeadline = System.currentTimeMillis() + 5000
      while (restartedClient.shownMessageRequests.size < 2 && System.currentTimeMillis() < nestedDeadline) Thread.sleep(20)
      assertEquals(restartedClient.shownMessageRequests.size, 2)
      assert(restartedClient.shownMessageRequests(1).getMessage.contains("Mill project"))
      restarted.cleanup()
    } finally os.remove.all(root)
  }
}
