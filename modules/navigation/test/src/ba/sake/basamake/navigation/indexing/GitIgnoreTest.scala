package ba.sake.basamake.navigation.indexing

import munit.FunSuite

class GitIgnoreTest extends FunSuite {

  private val root = os.temp.dir(prefix = "gitignore-")

  override def afterAll(): Unit = os.remove.all(root)

  // ========= readGitignorePatterns =========

  test("readGitignorePatterns: strips comments and empty lines") {
    val gitignore = root / ".gitignore"
    os.write(gitignore,
      """|# This is a comment
         |
         |*.class
         |# another comment
         |build/
         |""".stripMargin)
    val patterns = GitIgnore.readGitignorePatterns(gitignore)
    assertEquals(patterns, Vector("*.class", "build/"))
  }

  test("readGitignorePatterns: preserves ! prefix") {
    val gitignore = root / ".gitignore-preserves"
    os.write(gitignore,
      """|*.class
         |!Important.class
         |""".stripMargin)
    val patterns = GitIgnore.readGitignorePatterns(gitignore)
    assertEquals(patterns, Vector("*.class", "!Important.class"))
  }

  test("readGitignorePatterns: returns empty for non-existent file") {
    val patterns = GitIgnore.readGitignorePatterns(root / "nonexistent")
    assertEquals(patterns, Vector.empty)
  }

  test("readGitignorePatterns: handles file without trailing newline") {
    val gitignore = root / ".gitignore-notrail"
    os.write(gitignore, "*.class")
    val patterns = GitIgnore.readGitignorePatterns(gitignore)
    assertEquals(patterns, Vector("*.class"))
  }

  // ========= isIgnoredByGitignore =========

  test("isIgnoredByGitignore: simple glob matches filename") {
    val patterns = Seq("*.class")
    assert(GitIgnore.isIgnoredByGitignore("Foo.class", isDir = false, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("Foo.scala", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: directory-only pattern (trailing /)") {
    val patterns = Seq("build/")
    assert(GitIgnore.isIgnoredByGitignore("build", isDir = true, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("build", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: directory-only pattern does not match file with same name") {
    val patterns = Seq("logs/")
    assert(!GitIgnore.isIgnoredByGitignore("logs", isDir = false, patterns))
    assert(GitIgnore.isIgnoredByGitignore("logs", isDir = true, patterns))
  }

  test("isIgnoredByGitignore: ** glob matches nested paths") {
    val patterns = Seq("**/*.class")
    assert(GitIgnore.isIgnoredByGitignore("Foo.class", isDir = false, patterns))
    assert(GitIgnore.isIgnoredByGitignore("bar/Foo.class", isDir = false, patterns))
    assert(GitIgnore.isIgnoredByGitignore("a/b/c/Foo.class", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: **/build/ matches nested build directories") {
    val patterns = Seq("**/build/")
    assert(GitIgnore.isIgnoredByGitignore("build", isDir = true, patterns))
    assert(GitIgnore.isIgnoredByGitignore("foo/build", isDir = true, patterns))
    assert(GitIgnore.isIgnoredByGitignore("a/b/build", isDir = true, patterns))
  }

  test("isIgnoredByGitignore: leading / anchors to root") {
    val patterns = Seq("/build/")
    assert(GitIgnore.isIgnoredByGitignore("build", isDir = true, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("src/build", isDir = true, patterns))
  }

  test("isIgnoredByGitignore: negation (!) un-ignores a path") {
    val patterns = Seq("*.class", "!Important.class")
    assert(!GitIgnore.isIgnoredByGitignore("Important.class", isDir = false, patterns))
    assert(GitIgnore.isIgnoredByGitignore("Other.class", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: last matching pattern wins for negation") {
    val patterns = Seq("!Important.class", "*.class")
    assert(GitIgnore.isIgnoredByGitignore("Important.class", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: path-style pattern matches from root with boundary check") {
    val patterns = Seq("target/scala-3")
    assert(GitIgnore.isIgnoredByGitignore("target/scala-3", isDir = true, patterns))
    assert(GitIgnore.isIgnoredByGitignore("target/scala-3/classes", isDir = true, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("src/target/scala-3", isDir = true, patterns))
  }

  test("isIgnoredByGitignore: prefix match does not match sibling prefixes") {
    val patterns = Seq("build/output")
    assert(GitIgnore.isIgnoredByGitignore("build/output", isDir = false, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("build/output2.class", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: empty patterns list matches nothing") {
    val patterns = Seq.empty[String]
    assert(!GitIgnore.isIgnoredByGitignore("anything.txt", isDir = false, patterns))
  }

  test("isIgnoredByGitignore: * matches any single directory component") {
    val patterns = Seq("foo/*/bar")
    assert(GitIgnore.isIgnoredByGitignore("foo/x/bar", isDir = false, patterns))
    assert(!GitIgnore.isIgnoredByGitignore("foo/x/y/bar", isDir = false, patterns))
  }
}
