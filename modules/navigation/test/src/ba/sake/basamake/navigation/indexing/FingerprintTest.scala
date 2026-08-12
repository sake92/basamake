package ba.sake.basamake.navigation.indexing

import munit.FunSuite

class FingerprintTest extends FunSuite {

  test("fixture jar without sibling pom → artifact_version + hash") {
    // repo fixture jar — no POM next to it, so the filename scheme is used
    val jar = os.pwd / "modules" / "navigation" / "test" / "resources" / "jars" / "commons-net-3.9.0-sources.jar"
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

  test("pom-derived groupId (coursier layout, parent + own groupId)") {
    val base = os.temp.dir() / "com/fasterxml/jackson/core/jackson-core/2.12.1"
    os.makeDir.all(base)
    val jar = base / "jackson-core-2.12.1-sources.jar"
    os.write.over(jar, "dummy")
    os.write.over(base / "jackson-core-2.12.1.pom",
      """<project>
        |  <modelVersion>4.0.0</modelVersion>
        |  <parent>
        |    <groupId>com.fasterxml.jackson</groupId>
        |    <artifactId>jackson-base</artifactId>
        |    <version>2.12.1</version>
        |  </parent>
        |  <groupId>com.fasterxml.jackson.core</groupId>
        |  <artifactId>jackson-core</artifactId>
        |  <version>2.12.1</version>
        |</project>""".stripMargin)

    val fp = Fingerprint.fromJarPath(jar)
    assert(fp.startsWith("com_fasterxml_jackson_core/jackson-core_2.12.1_"), s"unexpected fingerprint: $fp")
    assert(fp.matches("""^com_fasterxml_jackson_core/jackson-core_2\.12\.1_[0-9a-f]{8}$"""), s"unexpected fingerprint: $fp")
  }

  test("pom with only parent groupId → fallback to filename scheme") {
    val base = os.temp.dir() / "org/example/foo/1.0.0"
    os.makeDir.all(base)
    val jar = base / "foo-1.0.0-sources.jar"
    os.write.over(jar, "dummy")
    os.write.over(base / "foo-1.0.0.pom",
      """<project>
        |  <parent>
        |    <groupId>org.example.parent</groupId>
        |    <artifactId>parent</artifactId>
        |    <version>1.0.0</version>
        |  </parent>
        |  <artifactId>foo</artifactId>
        |  <version>1.0.0</version>
        |</project>""".stripMargin)

    val fp = Fingerprint.fromJarPath(jar)
    assert(fp.startsWith("foo_1.0.0_"), s"inherited groupId must fall back, got: $fp")
  }

  test("jdk fingerprint from java.home + version") {
    val fp = Fingerprint.fromJdk(os.Path("/home/sake/.sdkman/candidates/java/21.0.2"), "21.0.2")
    assert(fp.matches("""^jdk-21\.0\.2_[0-9a-f]{8}$"""), s"unexpected fingerprint: $fp")
  }

  test("fromJarPath is memoized: a POM change after the first call does not change the fingerprint") {
    val base = os.temp.dir() / "com/example/memo/1.0.0"
    os.makeDir.all(base)
    val jar = base / "memo-1.0.0-sources.jar"
    os.write.over(jar, "dummy")
    val pom = base / "memo-1.0.0.pom"
    os.write.over(pom,
      "<project><groupId>com.example</groupId><artifactId>memo</artifactId><version>1.0.0</version></project>")

    val fp1 = Fingerprint.fromJarPath(jar)
    assert(fp1.startsWith("com_example/memo_1.0.0_"), s"unexpected fingerprint: $fp1")

    // jar paths + their POMs are immutable in the coursier cache — the memo
    // must NOT re-parse the POM on the next call (a per-lookup DOM parse of
    // every candidate jar was the dominant goto-def cost)
    os.write.over(pom,
      "<project><groupId>org.changed</groupId><artifactId>memo</artifactId><version>1.0.0</version></project>")
    val fp2 = Fingerprint.fromJarPath(jar)
    assertEquals(fp1, fp2, "fingerprint must come from the memo, not a fresh POM parse")
  }
}
