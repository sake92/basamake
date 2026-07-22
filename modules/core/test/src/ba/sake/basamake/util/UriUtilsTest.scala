package ba.sake.basamake.util

import munit.FunSuite

class UriUtilsTest extends FunSuite {

  test("normalizeUri canonicalizes file uris") {
    val normalized = UriUtils.normalizeUri("file:/ws/sbt/src/main/scala/")
    assertEquals(normalized, "file:///ws/sbt/src/main/scala")
  }

  test("uriToPathOption parses file uri and raw path") {
    val fromUri = UriUtils.uriToPathOption("file:///tmp")
    val fromRaw = UriUtils.uriToPathOption("/tmp")

    assert(fromUri.nonEmpty)
    assert(fromRaw.nonEmpty)
  }

  test("canonicalFileUri strips jar prefix and entry suffix") {
    val canonical = UriUtils.canonicalFileUri("jar:file:///tmp/x.jar!/pkg/Foo.scala")
    assertEquals(canonical, "file:///tmp/x.jar")
  }
}
