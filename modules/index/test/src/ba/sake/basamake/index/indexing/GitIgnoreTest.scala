package ba.sake.basamake.index.indexing

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
}
