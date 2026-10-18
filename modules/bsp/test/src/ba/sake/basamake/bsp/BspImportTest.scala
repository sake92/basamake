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

  private def awaitCondition(clue: String, seconds: Int = 10)(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    while (!condition && System.nanoTime() < deadline) Thread.sleep(20)
    assert(condition, clue)
  }

  /** Real projects gitignore `.basamake/` — the config watcher must still notice
    * config.json edits (the path is exempted from the watch filter). */
  private def ignoreBasamake(root: os.Path): Unit =
    os.write(root / ".gitignore", ".basamake/\n.bsp/\n")

  private def writeAutoCompile(root: os.Path, autoCompile: Option[Boolean]): Unit = {
    val entry = autoCompile match {
      case Some(v) => s"""{"bspFile":".bsp/fake.json","enabled":true,"autoCompile":$v}"""
      case None    => """{"bspFile":".bsp/fake.json","enabled":true}"""
    }
    os.write.over(root / ".basamake" / "config.json", s"""{"bspOverrides":[$entry]}""", createFolders = true)
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

  test("an already configured build imports at startup without ever prompting") {
    val root = fixture()
    os.write(root / ".basamake" / "config.json",
      """{"bspOverrides":[{"bspFile":".bsp/fake.json","enabled":true,"autoCompile":false}]}""",
      createFolders = true)
    val client = new CapturingLanguageClient
    val manager = BspManagerTestSupport.managerFor(root, client)
    try {
      manager.poke((root / "Main.scala").toNIO.toUri.toString, compile = true)
      awaitCondition("a decided build connects") {
        client.logged.stream().anyMatch(_.getMessage.startsWith("Connected to"))
      }
      assertEquals(client.requests.size(), 0, "a recorded import decision suppresses the prompt")
      assert(!os.read.lines(root / "requests.txt").contains("buildTarget/compile"),
        "the recorded autoCompile=false mode is honored")
    } finally {
      manager.shutdown()
      os.remove.all(root)
    }
  }

  test("a hand-written autoCompile answers a pending prompt and is honored") {
    val root = fixture()
    ignoreBasamake(root)
    val client = new CapturingLanguageClient
    client.response = new CompletableFuture[MessageActionItem]()
    val manager = BspManagerTestSupport.managerFor(root, client)
    val uri = (root / "Main.scala").toNIO.toUri.toString
    try {
      manager.poke(uri, compile = true)
      awaitCondition("prompt offered while no decision is recorded") { client.requests.size() == 1 }

      writeAutoCompile(root, Some(false))
      awaitCondition("config watcher cancels the pending prompt") { client.response.isCancelled }
      awaitCondition("drained poke connects after the manual decision") {
        client.logged.stream().anyMatch(_.getMessage.startsWith("Connected to"))
      }
      assertEquals(client.requests.size(), 1, "no second prompt")
      assert(!os.read.lines(root / "requests.txt").contains("buildTarget/compile"),
        "hand-written autoCompile=false is honored")
    } finally {
      manager.shutdown()
      os.remove.all(root)
    }
  }

  test("deleting autoCompile keeps the session imported and re-arms the prompt on re-attach") {
    val root = fixture()
    ignoreBasamake(root)
    writeAutoCompile(root, Some(false))
    val client = new CapturingLanguageClient
    val manager = BspManagerTestSupport.managerFor(root, client)
    val uri = (root / "Main.scala").toNIO.toUri.toString
    try {
      manager.poke(uri, compile = true)
      awaitCondition("configured build connects") {
        client.logged.stream().anyMatch(_.getMessage.startsWith("Connected to"))
      }
      Thread.sleep(700)
      assertEquals(client.requests.size(), 0, "recorded decision suppresses the prompt")

      // Deletion is not a decision: the running session stays imported...
      writeAutoCompile(root, None)
      Thread.sleep(700) // let the config watcher apply it
      assertEquals(BasamakeConfig.load(root).bspOverrides.map(_.autoCompile), List(None))
      manager.poke(uri, compile = true)
      Thread.sleep(300)
      assertEquals(client.requests.size(), 0, "deleting the key does NOT re-arm the prompt mid-session")

      // ...but the next attach rebuilds the state from the config file, so an
      // undecided build asks again.
      val bspFile = root / ".bsp" / "fake.json"
      val spec = os.read(bspFile).parseJson[BspDiscoveryFile]
      os.write.over(bspFile, spec.copy(argv = spec.argv :+ "extra-arg").toJson)
      awaitCondition("prompt re-armed after detach + re-attach", seconds = 20) {
        manager.poke(uri, compile = false)
        Thread.sleep(100)
        client.requests.size() == 1
      }
    } finally {
      manager.shutdown()
      os.remove.all(root)
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
