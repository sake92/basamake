package ba.sake.basamake.index

import munit.FunSuite
import scala.meta.internal.semanticdb.Range

class SymbolTableTest extends FunSuite {

  private val path1 = os.pwd / "file1.scala"
  private val path2 = os.pwd / "file2.scala"
  private val sentinel = new Range(0, 0, 0, 0)

  test("add then get returns same SymbolDefinition") {
    val table = new InMemorySymbolTable
    val sd = SymbolDefinition("_empty_/Foo#", "Foo", isType = true, sentinel, path1)
    table.add(sd)
    assertEquals(table.get("_empty_/Foo#"), Some(sd))
  }

  test("removeByPath removes only that path's symbols") {
    val table = new InMemorySymbolTable
    val sd1 = SymbolDefinition("_empty_/Foo#", "Foo", isType = true, sentinel, path1)
    val sd2 = SymbolDefinition("_empty_/Bar#", "Bar", isType = true, sentinel, path2)
    table.add(sd1)
    table.add(sd2)
    assertEquals(table.get("_empty_/Foo#").map(_.path), Some(path1))
    assertEquals(table.get("_empty_/Bar#").map(_.path), Some(path2))

    table.removeByPath(path1)
    assertEquals(table.get("_empty_/Foo#"), None)
    assertEquals(table.get("_empty_/Bar#").map(_.path), Some(path2))
  }

  test("two files adding same symbol: last-write-wins, removeByPath(fileB) then re-add restores") {
    val table = new InMemorySymbolTable
    val sdA = SymbolDefinition("_empty_/X#", "X", isType = true, new Range(1, 0, 1, 1), path1)
    val sdB = SymbolDefinition("_empty_/X#", "X", isType = true, sentinel, path2)

    // First add from fileA
    table.add(sdA)
    assertEquals(table.get("_empty_/X#"), Some(sdA))

    // Second add from fileB — last-write-wins
    table.add(sdB)
    assertEquals(table.get("_empty_/X#"), Some(sdB))

    // Remove fileB — symbol should be gone (last-write-wins tracking lost it)
    table.removeByPath(path2)
    assertEquals(table.get("_empty_/X#"), None)

    // Re-add fileA's entry — should be back
    table.add(sdA)
    assertEquals(table.get("_empty_/X#"), Some(sdA))
  }

  test("all returns all added definitions") {
    val table = new InMemorySymbolTable
    val sd1 = SymbolDefinition("_empty_/Foo#", "Foo", isType = true, sentinel, path1)
    val sd2 = SymbolDefinition("_empty_/Bar#", "Bar", isType = true, sentinel, path2)
    table.add(sd1)
    table.add(sd2)
    assertEquals(table.all.size, 2)
    assert(table.all.contains(sd1))
    assert(table.all.contains(sd2))
  }
}
