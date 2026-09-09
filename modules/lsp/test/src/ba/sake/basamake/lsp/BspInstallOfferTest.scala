package ba.sake.basamake.lsp

import munit.FunSuite
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.{DidOpenTextDocumentParams, InitializeParams, TextDocumentItem}

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

      assertEquals(BspBuildTool.nearestFor(source, root), Some(BspBuildTool.Deder -> nested))
    } finally os.remove.all(root)
  }

  test("detects deder, sbt, and mill markers") {
    val root = fixture("markers")
    try {
      val source = root / "src" / "main" / "scala" / "Main.scala"
      os.write(root / "deder.pkl", "")
      assertEquals(BspBuildTool.nearestFor(source, root), Some(BspBuildTool.Deder -> root))
      os.remove(root / "deder.pkl")
      os.write(root / "build.sbt", "")
      assertEquals(BspBuildTool.nearestFor(source, root), Some(BspBuildTool.Sbt -> root))
      os.remove(root / "build.sbt")
      os.write(root / "build.mill", "")
      assertEquals(BspBuildTool.nearestFor(source, root), Some(BspBuildTool.Mill -> root))
      os.remove(root / "build.mill")
      os.write(root / "build.mill.yaml", "")
      assertEquals(BspBuildTool.nearestFor(source, root), Some(BspBuildTool.Mill -> root))
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

  test("opening an unconfigured supported project offers BSP installation once") {
    val root = fixture("prompt")
    try {
      os.write(root / "deder.pkl", "")
      os.write.over(root / ".basamake" / "config.json", "{\"enableJdkIndexing\":false}", createFolders = true)
      val source = root / "src" / "main" / "scala" / "Main.scala"
      val client = new TestLanguageClient
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

      open()
      Thread.sleep(100)
      assertEquals(client.shownMessageRequests.size, 1)
      server.cleanup()
    } finally os.remove.all(root)
  }
}
