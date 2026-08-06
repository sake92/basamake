package ba.sake.basamake.navigation

import munit.FunSuite
import scala.meta.internal.semanticdb.Range

class CompositeSymbolTableTest extends FunSuite {

  private def defOf(symbol: String, path: os.Path) =
    SymbolDefinition(symbol, symbol, isType = symbol.endsWith("#"), Range(0, 0, 0, 0), path)

  test("get: workspace table wins when both have the symbol") {
    val ws = new InMemorySymbolTable
    val deps = new InMemorySymbolTable
    val wsPath = os.pwd / "ws.scala"
    val depPath = os.pwd / "dep.scala"
    ws.add(defOf("com/example/Foo#", wsPath))
    deps.add(defOf("com/example/Foo#", depPath))

    val composite = new CompositeSymbolTable(ws, deps)
    assertEquals(composite.get("com/example/Foo#").map(_.path), Some(wsPath))
  }

  test("get: falls back to deps table") {
    val ws = new InMemorySymbolTable
    val deps = new InMemorySymbolTable
    val depPath = os.pwd / "dep.scala"
    deps.add(defOf("com/example/Bar#", depPath))

    val composite = new CompositeSymbolTable(ws, deps)
    assertEquals(composite.get("com/example/Bar#").map(_.path), Some(depPath))
  }

  test("get: miss in both → None") {
    val composite = new CompositeSymbolTable(new InMemorySymbolTable, new InMemorySymbolTable)
    assertEquals(composite.get("com/example/Missing#"), None)
  }

  test("byPath: union of both tables") {
    val ws = new InMemorySymbolTable
    val deps = new InMemorySymbolTable
    val path = os.pwd / "shared.scala"
    ws.add(defOf("com/example/A#", path))
    deps.add(defOf("com/example/B#", path))

    val composite = new CompositeSymbolTable(ws, deps)
    assertEquals(composite.byPath(path).map(_.symbol).toSet, Set("com/example/A#", "com/example/B#"))
  }

  test("add/removeByPath/keys/all only touch the workspace table") {
    val ws = new InMemorySymbolTable
    val deps = new InMemorySymbolTable
    val depPath = os.pwd / "dep.scala"
    deps.add(defOf("com/example/Dep#", depPath))

    val composite = new CompositeSymbolTable(ws, deps)
    val wsPath = os.pwd / "ws.scala"
    composite.add(defOf("com/example/Ws#", wsPath))

    assertEquals(composite.keys, Set("com/example/Ws#"))
    assertEquals(composite.all.map(_.symbol), Set("com/example/Ws#"))
    assert(ws.get("com/example/Ws#").isDefined, "workspace table must receive the add")
    assert(deps.get("com/example/Ws#").isEmpty, "deps table must NOT receive the add")

    composite.removeByPath(wsPath)
    assertEquals(composite.get("com/example/Ws#"), None)
    assert(deps.get("com/example/Dep#").isDefined, "deps table must be untouched by removeByPath")
  }
}
