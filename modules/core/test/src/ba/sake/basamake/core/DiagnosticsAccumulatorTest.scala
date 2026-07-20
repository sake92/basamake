package ba.sake.basamake.core

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.*

class DiagnosticsAccumulatorTest extends munit.FunSuite:
  private def mkDiag(msg: String) =
    val d = new Diagnostic()
    d.setRange(new Range(new Position(0,0), new Position(0,10)))
    d.setMessage(msg)
    d.setSeverity(DiagnosticSeverity.Error)
    d

  private val t1 = new BuildTargetIdentifier("t1")

  test("reset=true replaces") {
    val (s, _) = DiagnosticsAccumulator.apply(Map.empty, "file:///a.scala", t1, reset=true, List(mkDiag("e1")))
    assertEquals(s("file:///a.scala")(t1).size, 1)
    val (s2, _) = DiagnosticsAccumulator.apply(s, "file:///a.scala", t1, reset=true, List(mkDiag("e2")))
    assertEquals(s2("file:///a.scala")(t1).size, 1)
  }
  test("reset=false appends") {
    val (s, _) = DiagnosticsAccumulator.apply(Map.empty, "file:///a.scala", t1, reset=false, List(mkDiag("e1")))
    val (s2, _) = DiagnosticsAccumulator.apply(s, "file:///a.scala", t1, reset=false, List(mkDiag("e2")))
    assertEquals(s2("file:///a.scala")(t1).size, 2)
  }
  test("clearUri works") {
    val (s, _) = DiagnosticsAccumulator.apply(Map.empty, "file:///a.scala", t1, reset=true, List(mkDiag("e")))
    assertEquals(DiagnosticsAccumulator.clearUri(s, "file:///a.scala").size, 0)
  }
