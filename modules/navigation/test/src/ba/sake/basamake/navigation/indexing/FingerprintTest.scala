package ba.sake.basamake.navigation.indexing

import munit.FunSuite

class FingerprintTest extends FunSuite {

  test("maven coursier path → artifact_version + hash") {
    val jar = os.Path("/home/sake/.cache/coursier/v1/https/repo1.maven.org/maven2/commons-net/commons-net/3.9.0/commons-net-3.9.0-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    assert(fp.startsWith("commons-net_3.9.0_"), s"unexpected fingerprint: $fp")
    assert(fp.matches("""^commons-net_3\.9\.0_[0-9a-f]{8}$"""), s"unexpected fingerprint: $fp")
  }

  test("cross-versioned scala jar (foo_3-1.2.3)") {
    val jar = os.Path("/home/sake/.cache/coursier/v1/https/repo1.maven.org/maven2/org/typelevel/cats_3/2.9.0/cats_3-2.9.0-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    assert(fp.startsWith("cats_3_2.9.0_"), s"unexpected fingerprint: $fp")
  }

  test("version with dash (1.0.0-M1) → whole version kept") {
    val jar = os.Path("/tmp/whatever/org/example/foo/1.0.0-M1/foo-1.0.0-M1-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    assert(fp.startsWith("foo_1.0.0-M1_"), s"unexpected fingerprint: $fp")
  }

  test("non-maven filename → stripped name fallback") {
    val jar = os.Path("/tmp/random-libs/my-lib-sources.jar")
    val fp = Fingerprint.fromJarPath(jar)
    assert(fp.startsWith("my-lib_"), s"unexpected fingerprint: $fp")
  }

  test("plain jar without -sources suffix") {
    val jar = os.Path("/tmp/libs/lib.jar")
    val fp = Fingerprint.fromJarPath(jar)
    assert(fp.startsWith("lib_"), s"unexpected fingerprint: $fp")
  }

  test("hash differs for identical coordinates in different repos") {
    val a = Fingerprint.fromJarPath(os.Path("/repo1/org/foo/bar/1.0/bar-1.0-sources.jar"))
    val b = Fingerprint.fromJarPath(os.Path("/repo2/org/foo/bar/1.0/bar-1.0-sources.jar"))
    assert(a != b, "same coords in different repos must not collide")
    assert(a.startsWith("bar_1.0_") && b.startsWith("bar_1.0_"))
  }

  test("deterministic for the same path") {
    val jar = os.Path("/tmp/x/org/foo/bar/1.0/bar-1.0-sources.jar")
    assertEquals(Fingerprint.fromJarPath(jar), Fingerprint.fromJarPath(jar))
  }

  test("jdk fingerprint from java.home + version") {
    val fp = Fingerprint.fromJdk(os.Path("/home/sake/.sdkman/candidates/java/21.0.2"), "21.0.2")
    assert(fp.matches("""^jdk-21\.0\.2_[0-9a-f]{8}$"""), s"unexpected fingerprint: $fp")
  }
}
