package ba.sake.basamake.navigation.indexing

import munit.FunSuite
import ba.sake.basamake.navigation.{InMemorySymbolTable, SymbolDefinition}
import scala.meta.internal.semanticdb.Range

class LmdbSerializerTest extends FunSuite {

  test("roundtrip save/get with point queries") {
    val table = new InMemorySymbolTable()
    val indexDir = os.temp.dir() / "test.lmdb"
    val srcDir = indexDir / os.up / "src"
    table.add(SymbolDefinition(
      symbol = "foo.Bar#",
      shortName = "Bar",
      isType = true,
      range = Range(0, 0, 0, 10),
      path = srcDir / "Bar.java"
    ))
    table.add(SymbolDefinition(
      symbol = "foo.Bar.baz().",
      shortName = "baz",
      isType = false,
      range = Range(1, 2, 1, 5),
      path = srcDir / "Bar.java"
    ))

    LmdbSerializer.save(table, indexDir)

    val bar = LmdbSerializer.get(indexDir, "foo.Bar#")
    assertEquals(bar.map(_.symbol), Some("foo.Bar#"))
    assertEquals(bar.map(_.isType), Some(true))
    assertEquals(bar.map(_.path), Some(srcDir / "Bar.java"), "src-relative path must round-trip")
    assert(LmdbSerializer.get(indexDir, "foo.Bar.baz().").isDefined)
    assertEquals(LmdbSerializer.get(indexDir, "foo/Other#"), None, "missing key → None")
  }

  test("shortName is derived from the symbol, not stored") {
    val table = new InMemorySymbolTable()
    val indexDir = os.temp.dir() / "derive.lmdb"
    val srcDir = indexDir / os.up / "src"
    table.add(SymbolDefinition("java/lang/Object#", "Object", true, Range(0, 0, 0, 5), srcDir / "Object.java"))
    table.add(SymbolDefinition("java/lang/Object#clone().", "clone", false, Range(1, 0, 1, 5), srcDir / "Object.java"))
    table.add(SymbolDefinition("java/lang/Object#wait().(millis)", "millis", false, Range(2, 0, 2, 5), srcDir / "Object.java"))

    LmdbSerializer.save(table, indexDir)

    assertEquals(LmdbSerializer.get(indexDir, "java/lang/Object#").map(_.shortName), Some("Object"))
    assertEquals(LmdbSerializer.get(indexDir, "java/lang/Object#clone().").map(_.shortName), Some("clone"))
    assertEquals(LmdbSerializer.get(indexDir, "java/lang/Object#wait().(millis)").map(_.shortName), Some("wait"))
  }

  test("empty table roundtrip") {
    val table = new InMemorySymbolTable()
    val path = os.temp.dir() / "empty.lmdb"
    LmdbSerializer.save(table, path)
    assertEquals(LmdbSerializer.get(path, "anything"), None)
  }

  test("special characters in symbol") {
    val table = new InMemorySymbolTable()
    val indexDir = os.temp.dir() / "special.lmdb"
    val srcDir = indexDir / os.up / "src"
    table.add(SymbolDefinition(
      symbol = "foo.Bar.`<init>`().",
      shortName = "<init>",
      isType = false,
      range = Range(0, 0, 0, 10),
      path = srcDir / "Bar.scala"
    ))

    LmdbSerializer.save(table, indexDir)

    assert(LmdbSerializer.get(indexDir, "foo.Bar.`<init>`().").isDefined)
  }
}
