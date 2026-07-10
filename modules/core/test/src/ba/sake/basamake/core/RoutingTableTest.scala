package ba.sake.basamake.core

import ba.sake.basamake.routing.RoutingTable
import ba.sake.basamake.bsp.BspConnectionId
import munit.FunSuite

class RoutingTableTest extends FunSuite:

  test("longest prefix match wins") {
    val table = RoutingTable.empty
    table.update(BspConnectionId("sbt"), List("file:///ws/src/"))
    table.update(BspConnectionId("scalacli"), List("file:///ws/examples/"))

    assertEquals(table.reverseLookup("file:///ws/examples/foo.scala"), Some(BspConnectionId("scalacli")))
    assertEquals(table.reverseLookup("file:///ws/src/main/scala/Bar.scala"), Some(BspConnectionId("sbt")))
  }

  test("more specific prefix wins over less specific") {
    val table = RoutingTable.empty
    table.update(BspConnectionId("root"), List("file:///ws/"))
    table.update(BspConnectionId("sub"), List("file:///ws/sub/"))

    assertEquals(table.reverseLookup("file:///ws/sub/foo.scala"), Some(BspConnectionId("sub")))
  }

  test("remove clears all entries for a connection") {
    val table = RoutingTable.empty
    table.update(BspConnectionId("sbt"), List("file:///ws/src/", "file:///ws/test/"))
    table.remove(BspConnectionId("sbt"))

    assertEquals(table.reverseLookup("file:///ws/src/Foo.scala"), None)
  }

  test("reverse lookup returns all URIs for a connection") {
    val table = RoutingTable.empty
    table.update(BspConnectionId("sbt"), List("file:///ws/src/", "file:///ws/test/"))

    val uris = table.lookup(BspConnectionId("sbt"))
    assert(uris.contains("file:///ws/src/"))
    assert(uris.contains("file:///ws/test/"))
  }

  test("update overwrites previous entries for same connection") {
    val table = RoutingTable.empty
    table.update(BspConnectionId("sbt"), List("file:///ws/old/"))
    table.update(BspConnectionId("sbt"), List("file:///ws/new/"))

    assertEquals(table.reverseLookup("file:///ws/old/Foo.scala"), None)
    assertEquals(table.reverseLookup("file:///ws/new/Foo.scala"), Some(BspConnectionId("sbt")))
  }
