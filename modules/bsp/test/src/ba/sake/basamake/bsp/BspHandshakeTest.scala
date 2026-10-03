package ba.sake.basamake.bsp

import munit.FunSuite

class BspHandshakeTest extends FunSuite {

  test("Scala-only BSP handshake never requests Java options") {
    val root = os.temp.dir(prefix = "bsp-handshake-")
    val script = root / "server.py"
    os.makeDir.all(root / ".bsp")
    os.write(script, """import json, sys
while True:
    headers = {}
    while True:
        line = sys.stdin.buffer.readline()
        if not line: sys.exit(0)
        if line == b'\r\n': break
        key, value = line.decode().split(':', 1)
        headers[key.lower()] = value.strip()
    request = json.loads(sys.stdin.buffer.read(int(headers['content-length'])))
    method = request['method']
    with open('requests.txt', 'a') as log: log.write(method + '\n')
    if 'id' not in request: continue
    if method == 'build/initialize':
        result = {'displayName':'fake', 'version':'1', 'bspVersion':'2.1.0', 'capabilities':{}}
    elif method == 'workspace/buildTargets':
        result = {'targets':[{'id':{'uri':'file:///test#main'}, 'tags':[], 'languageIds':['scala'], 'dependencies':[], 'capabilities':{'canCompile':True}}]}
    else:
        result = {'items':[]}
    data = json.dumps({'jsonrpc':'2.0', 'id':request['id'], 'result':result}).encode()
    sys.stdout.buffer.write(('Content-Length: %d\r\n\r\n' % len(data)).encode() + data)
    sys.stdout.buffer.flush()
""")
    var process: Option[java.lang.Process] = None
    try {
      val spec = BspConnectionSpec(BspDiscoveryFile("fake", List("python3", script.toString)),
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
