package ba.sake.basamake.navigation

import munit.FunSuite

class NavigationUriUtilsTest extends FunSuite {

  test("normalizeUri canonicalizes file uris") {
    val normalized = NavigationUriUtils.normalizeUri("file:/ws/sbt/src/main/scala/")
    assertEquals(normalized, "file:///ws/sbt/src/main/scala")
  }

  test("uriToPathOption parses file uri and raw path") {
    val fromUri = NavigationUriUtils.uriToPathOption("file:///tmp")
    val fromRaw = NavigationUriUtils.uriToPathOption("/tmp")

    assert(fromUri.nonEmpty)
    assert(fromRaw.nonEmpty)
  }

  test("canonicalFileUri strips jar prefix and entry suffix") {
    val canonical = NavigationUriUtils.canonicalFileUri("jar:file:///tmp/x.jar!/pkg/Foo.scala")
    assertEquals(canonical, "file:///tmp/x.jar")
  }
}
