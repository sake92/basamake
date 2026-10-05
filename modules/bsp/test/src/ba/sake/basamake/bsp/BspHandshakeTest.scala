package ba.sake.basamake.bsp

import munit.FunSuite
import com.google.gson.JsonParser
import java.io.{BufferedInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable

class BspHandshakeTest extends FunSuite {

  test("Scala-only BSP handshake never requests Java options") {
    val root = os.temp.dir(prefix = "bsp-handshake-")
    os.makeDir.all(root / ".bsp")
    var process: Option[java.lang.Process] = None
    try {
      val javaExe = os.Path(System.getProperty("java.home")) / "bin" / "java"
      val classpath = System.getProperty("java.class.path")
        .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)
        .map(entry => if (entry.isEmpty) os.pwd.toString else os.Path(entry).toString)
        .mkString(java.io.File.pathSeparator)
      val spec = BspConnectionSpec(BspDiscoveryFile("fake", List(javaExe.toString, "-cp", classpath,
        "ba.sake.basamake.bsp.BspHandshakeFakeServer", (root / "requests.txt").toString)),
        root / ".bsp" / "fake.json", handshakeTimeoutSec = 5, workspaceRoot = root)
      val result = BspHandshake.execute(spec, new BspEvents {
        def onDiagnostics(p: ch.epfl.scala.bsp4j.PublishDiagnosticsParams, connId: BspConnectionId): Unit = ()
      }, BspConnectionId("test"))
      process = Some(result.process)
      val requests = os.read.lines(root / "requests.txt")
      assert(requests.contains("buildTarget/scalacOptions"))
      assert(!requests.contains("buildTarget/javacOptions"))
    } finally {
      process.foreach { p => p.destroyForcibly(); p.waitFor() }
      os.remove.all(root)
    }
  }

  test("process that exits immediately during handshake → exception thrown") {
    // A process that prints nothing and exits 0 — not a BSP server, so buildInitialize
    // either times out or hits EOF. Either way: handshake throws, process is killed.
    val spec = BspConnectionSpec(
      content = BspDiscoveryFile("fake", List("true")),
      path = os.pwd,
      compileTimeoutSec = 5,
      handshakeTimeoutSec = 5,
      workspaceRoot = os.pwd
    )
    var thrown: Option[Exception] = None
    try
      BspHandshake.execute(spec, events = new BspEvents {
        def onDiagnostics(p: ch.epfl.scala.bsp4j.PublishDiagnosticsParams, connId: BspConnectionId): Unit = ()
      }, connId = BspConnectionId("test"))
    catch
      case e: Exception => thrown = Some(e)
    assert(thrown.isDefined, "handshake should throw for a process that exits immediately")
  }

  // ── describeHandshakeFailure ─────────────────────────────────

  private val fakeLogDir = os.Path("/ws/.basamake/bsp/sbt_abc")
  private val fakeBspFile = "sbt/.bsp/sbt.json"

  test("describeHandshakeFailure: TimeoutException → descriptive message with stderr log + config hint") {
    val msg = BspHandshake.describeHandshakeFailure(new java.util.concurrent.TimeoutException(), 120, fakeLogDir, fakeBspFile)
    assert(msg.contains("timed out after 120s"), s"unexpected: $msg")
    assert(msg.contains("stderr.log"), s"unexpected: $msg")
    assert(msg.contains("config.json"), s"message must point at the config override: $msg")
    assert(msg.contains("""{"bspOverrides": [{"bspFile": "sbt/.bsp/sbt.json", "handshakeTimeoutSec": 300}]}"""),
      s"message must show a ready-to-paste config snippet: $msg")
  }

  test("describeHandshakeFailure: ExecutionException wrapping TimeoutException → descriptive") {
    val ee = new java.util.concurrent.ExecutionException(new java.util.concurrent.TimeoutException())
    val msg = BspHandshake.describeHandshakeFailure(ee, 60, fakeLogDir, fakeBspFile)
    assert(msg.contains("timed out after 60s"), s"unexpected: $msg")
  }

  test("describeHandshakeFailure: other exceptions keep their message") {
    assertEquals(BspHandshake.describeHandshakeFailure(new RuntimeException("boom"), 120, fakeLogDir, fakeBspFile), "boom")
  }

  test("describeHandshakeFailure: null message → exception class name (not 'null')") {
    assertEquals(BspHandshake.describeHandshakeFailure(new RuntimeException(), 120, fakeLogDir, fakeBspFile), "RuntimeException")
  }
}

object BspHandshakeFakeServer {
  def main(args: Array[String]): Unit = {
    val in = new BufferedInputStream(System.in)
    val log = Path.of(args(0))
    while (true) {
      val headers = mutable.Map.empty[String, String]
      var line = readLine(in)
      while (line != null && line.nonEmpty) {
        val Array(key, value) = line.split(":", 2)
        headers += key.toLowerCase -> value.trim
        line = readLine(in)
      }
      if (line == null) return

      val request = JsonParser.parseString(new String(in.readNBytes(headers("content-length").toInt), UTF_8)).getAsJsonObject
      val method = request.get("method").getAsString
      Files.writeString(log, method + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      if (request.has("id")) {
        val result = method match {
          case "build/initialize" => """{"displayName":"fake","version":"1","bspVersion":"2.1.0","capabilities":{}}"""
          case "workspace/buildTargets" => """{"targets":[{"id":{"uri":"file:///test#main"},"tags":[],"languageIds":["scala"],"dependencies":[],"capabilities":{"canCompile":true}}]}"""
          case _ => """{"items":[]}"""
        }
        val response = s"""{"jsonrpc":"2.0","id":${request.get("id")},"result":$result}""".getBytes(UTF_8)
        System.out.write(s"Content-Length: ${response.length}\r\n\r\n".getBytes(UTF_8))
        System.out.write(response)
        System.out.flush()
      }
    }
  }

  private def readLine(in: BufferedInputStream): String = {
    val bytes = new ByteArrayOutputStream
    var next = in.read()
    while (next != -1 && next != '\n') {
      if (next != '\r') bytes.write(next)
      next = in.read()
    }
    if (next == -1 && bytes.size == 0) null else bytes.toString(UTF_8)
  }
}
