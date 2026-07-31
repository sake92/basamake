package ba.sake.basamake.bsp

import munit.FunSuite

class BspHandshakeTest extends FunSuite {

  test("process that exits immediately during handshake → exception thrown") {
    // A process that prints nothing and exits 0 — not a BSP server, so buildInitialize
    // either times out or hits EOF. Either way: handshake throws, process is killed.
    val spec = BspConnectionSpec(
      content = BspDiscoveryFile("fake", List("true")),
      path = os.pwd,
      compileTimeoutSec = 5,
      workspaceRoot = os.pwd
    )
    var thrown: Option[Exception] = None
    try
      BspHandshake.execute(spec, eventSink = new BspEventSink {
        def onDiagnostics(p: ch.epfl.scala.bsp4j.PublishDiagnosticsParams): Unit = ()
        def onTargetChanged(p: ch.epfl.scala.bsp4j.DidChangeBuildTarget): Unit = ()
      }, timeoutSec = 5)
    catch
      case e: Exception => thrown = Some(e)
    assert(thrown.isDefined, "handshake should throw for a process that exits immediately")
  }
}
