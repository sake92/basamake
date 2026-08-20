package ba.sake.basamake.index.indexing

import munit.FunSuite
import ba.sake.basamake.index.{InMemorySymbolTable, SymbolDefinition}
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

    LmdbSerializer.streamingSave(indexDir, indexDir / os.up) { sink => table.all.foreach(sink.add) }

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

    LmdbSerializer.streamingSave(indexDir, indexDir / os.up) { sink => table.all.foreach(sink.add) }

    assertEquals(LmdbSerializer.get(indexDir, "java/lang/Object#").map(_.shortName), Some("Object"))
    assertEquals(LmdbSerializer.get(indexDir, "java/lang/Object#clone().").map(_.shortName), Some("clone"))
    // parameter symbols derive their OWN name (millis), not the method's
    assertEquals(LmdbSerializer.get(indexDir, "java/lang/Object#wait().(millis)").map(_.shortName), Some("millis"))
  }

  test("empty table roundtrip") {
    val table = new InMemorySymbolTable()
    val path = os.temp.dir() / "empty.lmdb"
    LmdbSerializer.streamingSave(path, path / os.up) { sink => table.all.foreach(sink.add) }
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

    LmdbSerializer.streamingSave(indexDir, indexDir / os.up) { sink => table.all.foreach(sink.add) }

    assert(LmdbSerializer.get(indexDir, "foo.Bar.`<init>`().").isDefined)
  }

  test("streamingSave writes definitions straight into LMDB + collects count") {
    val indexDir = os.temp.dir() / "stream.lmdb"
    val cacheDir = indexDir / os.up
    val srcDir = cacheDir / "src"

    val sink = LmdbSerializer.streamingSave(indexDir, cacheDir) { s =>
      s.add(SymbolDefinition("foo/Bar#", "Bar", true, Range(0, 0, 0, 10), srcDir / "Bar.java"))
      s.add(SymbolDefinition("foo/Bar.baz().", "baz", false, Range(1, 2, 1, 5), srcDir / "Bar.java"))
      // local symbols are skipped, mirroring InMemorySymbolTable.add
      s.add(SymbolDefinition("local0", "x", false, Range(0, 0, 0, 1), srcDir / "Bar.java"))
      s.add(SymbolDefinition("java/lang/Object#", "Object", true, Range(0, 0, 0, 5), srcDir / "Object.java"))
    }

    assertEquals(sink.count, 3, "local symbols must be skipped by the sink")
    assertEquals(LmdbSerializer.get(indexDir, "foo/Bar#").map(_.path), Some(srcDir / "Bar.java"))
    assert(LmdbSerializer.get(indexDir, "foo/Bar.baz().").isDefined)
    assert(LmdbSerializer.get(indexDir, "java/lang/Object#").isDefined)
    assert(LmdbSerializer.get(indexDir, "local0").isEmpty, "local symbols never hit the index")
  }

  test("streamingSave with empty fill writes a valid empty index") {
    val indexDir = os.temp.dir() / "stream-empty.lmdb"
    val cacheDir = indexDir / os.up

    val sink = LmdbSerializer.streamingSave(indexDir, cacheDir)(_ => ())

    assertEquals(sink.count, 0)
    assertEquals(LmdbSerializer.get(indexDir, "anything"), None)
  }

  test("getPrefix returns all symbols under a prefix (method overloads)") {
    val tmp = os.temp.dir(prefix = "lmdb-prefix-")
    try {
      val indexPath = tmp / "index.lmdb"
      val defs = Vector(
        SymbolDefinition("pkg/Foo.pure().", "pure", isType = false, Range(0, 0, 1, 0), tmp / "src" / "Foo.scala"),
        SymbolDefinition("pkg/Foo.pure(+1).", "pure", isType = false, Range(0, 0, 1, 0), tmp / "src" / "Foo.scala"),
        SymbolDefinition("pkg/Foo.pureEffect().", "pureEffect", isType = false, Range(0, 0, 1, 0), tmp / "src" / "Foo.scala")
      )
      LmdbSerializer.streamingSave(indexPath, tmp)(sink => defs.foreach(sink.add))
      val hits = LmdbSerializer.getPrefix(indexPath, "pkg/Foo.pure(")
      assertEquals(hits.map(_.symbol).toSet, Set("pkg/Foo.pure().", "pkg/Foo.pure(+1)."),
        "prefix scan must return all overloads but NOT other names sharing the name prefix")
    } finally os.remove.all(tmp)
  }
}
