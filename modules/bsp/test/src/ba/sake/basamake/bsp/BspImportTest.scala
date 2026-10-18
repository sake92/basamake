package ba.sake.basamake.bsp

import java.util.concurrent.{CompletableFuture, TimeUnit}
import org.eclipse.lsp4j.MessageActionItem
import ba.sake.basamake.config.BasamakeConfig
import ba.sake.tupson.{given, *}
import munit.FunSuite

class BspImportTest extends FunSuite {
  private def fixture(): os.Path = {
    val root = os.temp.dir(prefix = "bsp-import-")
    val javaExe = os.Path(System.getProperty("java.home")) / "bin" / "java"
    val classpathEntries = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
    val classpath = classpathEntries.map(p => os.Path(p).toString).mkString(java.io.File.pathSeparator)
    val config = BspDiscoveryFile("fake", List(javaExe.toString, "-cp", classpath,
      "ba.sake.basamake.bsp.BspHandshakeFakeServer", (root / "requests.txt").toString))
    os.write(root / ".bsp" / "fake.json", config.toJson, createFolders = true)
    os.write(root / "Main.scala", "object Main")
    root
  }

  private def awaitCondition(clue: String)(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition && System.nanoTime() < deadline) Thread.sleep(20)
    assert(condition, clue)
  }

  test("startup and watcher events stay idle; concurrent editor events ask once; dismissal keeps BSP stopped") {
    val root = fixture()
    val client = new CapturingLanguageClient
    client.response = new CompletableFuture[MessageActionItem]()
    val manager = BspManagerTestSupport.managerFor(root, client)
    val uri = (root / "Main.scala").toNIO.toUri.toString
    try {
      manager.onWatchedFilesChanged(List(uri), Nil)
      Thread.sleep(200)
      assertEquals(client.requests.size(), 0)
      assert(!os.exists(root / "requests.txt"))
      val pokes = (1 to 10).map(_ => Thread.ofVirtual().start(() => manager.poke(uri, compile = true)))
      pokes.foreach(_.join(5000))
      assertEquals(client.requests.size(), 1)
      assertEquals(client.requests.get(0).getActions.get(0).getTitle, "Compile on change")
      assertEquals(client.requests.get(0).getActions.get(1).getTitle, "Don't compile on change")
      assert(!os.exists(root / "requests.txt"), "pending import cannot start BSP")
      client.response.complete(null)
      manager.poke(uri, compile = false)
      manager.onWatchedFilesChanged(Nil, Nil, List(uri))
      Thread.sleep(200)
      assertEquals(client.requests.size(), 1, "dismissal lasts for this connection's session")
      assert(!os.exists(root / "requests.txt"))
    } finally {
      manager.shutdown()
      os.remove.all(root)
    }
  }

  for (mode <- List("Compile on change", "Don't compile on change")) {
    test(s"$mode saves the choice and imports BSP metadata") {
      val root = fixture()
      val client = new CapturingLanguageClient
      client.response = CompletableFuture.completedFuture(new MessageActionItem(mode))
      val manager = BspManagerTestSupport.managerFor(root, client)
      try {
        manager.poke((root / "Main.scala").toNIO.toUri.toString, compile = true)
        awaitCondition("BSP handshake finishes after mode selection") {
          client.logged.stream().anyMatch(_.getMessage.startsWith("Connected to"))
        }
        assertEquals(BasamakeConfig.load(root).bspOverrides.head.autoCompile, Some(mode == "Compile on change"))
        assert(os.read.lines(root / "requests.txt").contains("workspace/buildTargets"))
      } finally {
        manager.shutdown()
        os.remove.all(root)
      }
    }

    test(s"installing with $mode discovers and imports the build without another prompt") {
      val root = fixture()
      val bspFile = root / ".bsp" / "fake.json"
      val bspConfig = os.read(bspFile)
      os.remove(bspFile)
      val client = new CapturingLanguageClient
      val manager = BspManagerTestSupport.managerFor(root, client)
      try {
        os.write(bspFile, bspConfig)
        assert(manager.importInstalledBuild((root / "Main.scala").toNIO.toUri.toString,
          autoCompile = mode == "Compile on change"))
        awaitCondition("installed build connects") {
          client.logged.stream().anyMatch(_.getMessage.startsWith("Connected to"))
        }
        assertEquals(client.requests.size(), 0, "the install prompt already selected the import mode")
        assertEquals(BasamakeConfig.load(root).bspOverrides.head.autoCompile, Some(mode == "Compile on change"))
      } finally {
        manager.shutdown()
        os.remove.all(root)
      }
    }
  }

  test("shutdown cancels a pending import and prevents late startup") {
    val root = fixture()
    val client = new CapturingLanguageClient
    client.response = new CompletableFuture[MessageActionItem]()
    val manager = BspManagerTestSupport.managerFor(root, client)
    try {
      manager.poke((root / "Main.scala").toNIO.toUri.toString, compile = true)
      manager.shutdown()
      assert(client.response.isCancelled)
      assert(!client.response.complete(new MessageActionItem("Compile on change")))
      assert(!os.exists(root / "requests.txt"))
    } finally {
      manager.shutdown()
      os.remove.all(root)
    }
  }
}
