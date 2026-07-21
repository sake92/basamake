package ba.sake.basamake.navigation

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import munit.FunSuite
import org.eclipse.lsp4j.{Location, Position, Range}

class NavigationIndexPrecomputeTest extends FunSuite {

  private val targetId = new BuildTargetIdentifier("target://precompute")

  private def range(line: Int): Range =
    new Range(new Position(line, 0), new Position(line, 5))

  private def slice(
      uri: String,
      defs: Map[String, List[Location]] = Map.empty,
      refs: Map[String, List[Location]] = Map.empty
  ): SemanticdbFileSlice =
    SemanticdbFileSlice(
      sourceUri = uri,
      occurrences = Nil,
      symbolDefinitions = defs,
      symbolReferences = refs,
      documentSymbols = Nil
    )

  test("build indexes workspace and dependency slices by sourceUri") {
    val ws = slice("file:///ws/A.scala")
    val dep1 = slice("file:///dep/B.scala")
    val dep2 = slice("file:///ws/A.scala") // dep slice sharing a uri (edge)

    val state = NavigationIndex.TargetState.build(
      targetId,
      Map(ws.sourceUri -> ws),
      List(dep1, dep2)
    )

    assertEquals(state.slicesByUri("file:///ws/A.scala"), List(ws, dep2))
    assertEquals(state.slicesByUri("file:///dep/B.scala"), List(dep1))
  }

  test("build merges definitions and references across workspace and dependency slices") {
    val locA = new Location("file:///ws/A.scala", range(1))
    val locB = new Location("file:///dep/B.scala", range(2))
    val locRef = new Location("file:///ws/A.scala", range(3))

    val ws = slice(
      "file:///ws/A.scala",
      defs = Map("sym" -> List(locA)),
      refs = Map("sym" -> List(locRef))
    )
    val dep = slice(
      "file:///dep/B.scala",
      defs = Map("sym" -> List(locB))
    )

    val state = NavigationIndex.TargetState.build(
      targetId,
      Map(ws.sourceUri -> ws),
      List(dep)
    )

    assertEquals(state.mergedDefinitions("sym").toSet, Set(locA, locB))
    assertEquals(state.mergedReferences("sym"), List(locRef))
  }

  test("build sorts orderedWorkspace by sourceUri and keeps dependency list order") {
    val a = slice("file:///ws/A.scala")
    val c = slice("file:///ws/C.scala")
    val b = slice("file:///ws/B.scala")
    val dep2 = slice("file:///dep/2.scala")
    val dep1 = slice("file:///dep/1.scala")

    val state = NavigationIndex.TargetState.build(
      targetId,
      Map(c.sourceUri -> c, a.sourceUri -> a, b.sourceUri -> b),
      List(dep2, dep1)
    )

    assertEquals(state.orderedWorkspace.map(_.sourceUri), List(a.sourceUri, b.sourceUri, c.sourceUri))
    assertEquals(state.orderedDependency.map(_.sourceUri), List(dep2.sourceUri, dep1.sourceUri))
  }

  test("build handles empty inputs") {
    val state = NavigationIndex.TargetState.build(targetId, Map.empty, Nil)
    assertEquals(state.slicesByUri, Map.empty)
    assertEquals(state.mergedDefinitions, Map.empty)
    assertEquals(state.mergedReferences, Map.empty)
    assertEquals(state.orderedWorkspace, Nil)
    assertEquals(state.orderedDependency, Nil)
  }
}
