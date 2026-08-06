package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import ba.sake.basamake.navigation.{SymbolTable, InMemorySymbolTable, SymbolDefinition}
import scala.meta.internal.semanticdb.Range

class LmdbSerializerTest extends FunSuite {

  test("roundtrip save/load") {
    val table = new InMemorySymbolTable()
    table.add(SymbolDefinition(
      symbol = "foo.Bar#",
      shortName = "Bar",
      isType = true,
      range = Range(0, 0, 0, 10),
      path = os.pwd / "Bar.java"
    ))
    table.add(SymbolDefinition(
      symbol = "foo.Bar.baz().",
      shortName = "baz",
      isType = false,
      range = Range(1, 2, 1, 5),
      path = os.pwd / "Bar.java"
    ))

    val path = os.temp.dir() / "test.lmdb"
    LmdbSerializer.save(table, path)
    val loaded = LmdbSerializer.load(path)

    assertEquals(loaded.get("foo.Bar#").map(_.symbol), Some("foo.Bar#"))
    assertEquals(loaded.get("foo.Bar#").map(_.shortName), Some("Bar"))
    assertEquals(loaded.get("foo.Bar#").map(_.isType), Some(true))
    assertEquals(loaded.get("foo.Bar.baz().").map(_.symbol), Some("foo.Bar.baz()."))
    assertEquals(loaded.all.size, 2)
  }

  test("empty table roundtrip") {
    val table = new InMemorySymbolTable()
    val path = os.temp.dir() / "empty.lmdb"
    LmdbSerializer.save(table, path)
    val loaded = LmdbSerializer.load(path)
    assertEquals(loaded.all.size, 0)
  }

  test("special characters in symbol") {
    val table = new InMemorySymbolTable()
    table.add(SymbolDefinition(
      symbol = "foo.Bar.`<init>`().",
      shortName = "<init>",
      isType = false,
      range = Range(0, 0, 0, 10),
      path = os.pwd / "Bar.scala"
    ))

    val path = os.temp.dir() / "special.lmdb"
    LmdbSerializer.save(table, path)
    val loaded = LmdbSerializer.load(path)

    assertEquals(loaded.get("foo.Bar.`<init>`().").map(_.symbol), Some("foo.Bar.`<init>`()."))
  }
}
