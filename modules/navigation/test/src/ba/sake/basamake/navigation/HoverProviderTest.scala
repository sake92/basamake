package ba.sake.basamake.navigation

import munit.FunSuite
import scala.meta.internal.semanticdb.Range
import ba.sake.basamake.navigation.indexing.WorkspaceIndex

class HoverProviderTest extends FunSuite {

  private def freshProvider(root: os.Path): HoverProvider = {
    val st = new InMemorySymbolTable
    val idx = new WorkspaceIndex(root, st)
    idx.initialize(List.empty)
    HoverProvider(idx)
  }

  /** Returns (0-indexed line, 0-indexed startCharacter) for the first match of
    * `regex` in `content`. If a named group `p` exists, its start is used. */
  private def posAt(content: String, regex: String): (Int, Int) = {
    val m = java.util.regex.Pattern.compile(regex).matcher(content)
    require(m.find(), s"posAt: regex not found: /$regex/")
    val start =
      try m.start("p")
      catch { case _: IllegalArgumentException => m.start() }
    val before = content.substring(0, start)
    val line = before.count(_ == '\n')
    val lastNl = before.lastIndexOf('\n')
    val char = if lastNl < 0 then before.length else before.length - lastNl - 1
    (line, char)
  }

  private def writeFixture(root: os.Path): Unit = {
    os.makeDir.all(root / "src")
    os.write(root / "src" / "Main.scala",
      """@main def hello(): Unit =
        |  utils.getMsg()
        |""".stripMargin)
    os.write(root / "src" / "utils.scala",
      """object utils {
        |  /** Returns a greeting message. */
        |  def getMsg(): String = "bla"
        |}
        |""".stripMargin)
    os.write(root / "src" / "Main.java",
      """class Main {
        |  String s = new Greeter().greet("x");
        |}
        |""".stripMargin)
    os.write(root / "src" / "Greeter.java",
      """/** A greeter. */
        |public class Greeter {
        |  /** Greets by name. */
        |  public String greet(String name) { return "hi " + name; }
        |}
        |""".stripMargin)
  }

  // ── scala hover ──────────────────────────────────────────────

  test("hover on reference shows signature + doc + location") {
    val root = os.pwd / "tmp" / s"hover-scala-ref-${System.currentTimeMillis()}"
    try {
      writeFixture(root)
      val provider = freshProvider(root)
      val mainFile = root / "src" / "Main.scala"
      provider.workspaceIndex.onDidOpen(mainFile)

      val mainText = os.read(mainFile)
      val (l, c) = posAt(mainText, """utils\.(?<p>getMsg)\(\)""")
      val info = provider.hover(mainFile, l, c).get

      assertEquals(info.signature, "def getMsg(): String")
      assertEquals(info.doc, Some("Returns a greeting message."))
      assertEquals(info.defPath.last, "utils.scala")
      assertEquals(info.defLine, 2)
      val md = info.markdown
      assert(md.contains("**def getMsg(): String**"), s"markdown missing signature: $md")
      assert(md.contains("Returns a greeting message."), s"markdown missing doc: $md")
      assert(md.contains("— utils.scala:3"), s"markdown missing location footer: $md")
    } finally os.remove.all(root)
  }

  test("hover on def site works") {
    val root = os.pwd / "tmp" / s"hover-scala-defsite-${System.currentTimeMillis()}"
    try {
      writeFixture(root)
      val provider = freshProvider(root)
      val utilsFile = root / "src" / "utils.scala"

      val utilsText = os.read(utilsFile)
      val (l, c) = posAt(utilsText, """def (?<p>getMsg)""")
      val info = provider.hover(utilsFile, l, c).get
      assertEquals(info.signature, "def getMsg(): String")
    } finally os.remove.all(root)
  }

  test("hover on object name shows object signature") {
    val root = os.pwd / "tmp" / s"hover-scala-object-${System.currentTimeMillis()}"
    try {
      writeFixture(root)
      val provider = freshProvider(root)
      val utilsFile = root / "src" / "utils.scala"

      val utilsText = os.read(utilsFile)
      val (l, c) = posAt(utilsText, """object (?<p>utils)""")
      val info = provider.hover(utilsFile, l, c).get
      assertEquals(info.signature, "object utils")
    } finally os.remove.all(root)
  }

  test("hover on unresolved position returns None") {
    val root = os.pwd / "tmp" / s"hover-scala-none-${System.currentTimeMillis()}"
    try {
      writeFixture(root)
      val provider = freshProvider(root)
      val mainFile = root / "src" / "Main.scala"
      provider.workspaceIndex.onDidOpen(mainFile)
      // blank line — no symbol under cursor
      val info = provider.hover(mainFile, 0, 0)
      assertEquals(info, None)
    } finally os.remove.all(root)
  }

  test("cache is invalidated when the def file changes") {
    val root = os.pwd / "tmp" / s"hover-cache-${System.currentTimeMillis()}"
    try {
      writeFixture(root)
      val provider = freshProvider(root)
      val mainFile = root / "src" / "Main.scala"
      val utilsFile = root / "src" / "utils.scala"
      provider.workspaceIndex.onDidOpen(mainFile)

      val mainText = os.read(mainFile)
      val (l, c) = posAt(mainText, """utils\.(?<p>getMsg)\(\)""")
      assertEquals(provider.hover(mainFile, l, c).get.signature, "def getMsg(): String")

      // change the def signature on disk, hover again — must see the new one
      os.write.over(utilsFile,
        """object utils {
          |  /** Returns a greeting message. */
          |  def getMsg(): Int = 42
          |}
          |""".stripMargin)
      assertEquals(provider.hover(mainFile, l, c).get.signature, "def getMsg(): Int")
    } finally os.remove.all(root)
  }

  // ── java hover ───────────────────────────────────────────────

  test("hover on java reference shows signature + doc") {
    val root = os.pwd / "tmp" / s"hover-java-ref-${System.currentTimeMillis()}"
    try {
      writeFixture(root)
      val provider = freshProvider(root)
      val mainJava = root / "src" / "Main.java"
      provider.workspaceIndex.onDidOpen(mainJava)

      val javaText = os.read(mainJava)
      // hover on the Greeter type ref inside `new Greeter()` (resolves to the class def)
      val (l, c) = posAt(javaText, """new (?<p>Greeter)\(\)""")
      val info = provider.hover(mainJava, l, c).get
      assertEquals(info.signature, "public class Greeter")
      assertEquals(info.doc, Some("A greeter."))
      assertEquals(info.defPath.last, "Greeter.java")
    } finally os.remove.all(root)
  }

  test("hover on java def site works") {
    val root = os.pwd / "tmp" / s"hover-java-defsite-${System.currentTimeMillis()}"
    try {
      writeFixture(root)
      val provider = freshProvider(root)
      val greeter = root / "src" / "Greeter.java"

      val greeterText = os.read(greeter)
      val (l, c) = posAt(greeterText, """String (?<p>greet)""")
      val info = provider.hover(greeter, l, c).get
      assertEquals(info.signature, "public String greet(String name)")
    } finally os.remove.all(root)
  }

  // ── fallback ─────────────────────────────────────────────────

  test("fallback extracts declaration lines for synthetic symbols") {
    val root = os.pwd / "tmp" / s"hover-fallback-${System.currentTimeMillis()}"
    try {
      writeFixture(root)
      val provider = freshProvider(root)
      val file = root / "src" / "Synthetic.scala"
      os.write(file, "case class Foo(\n  x: Int,\n  y: Int\n)\n")

      // synthetic apply points at the class name position (Range(0,0,0,0) stand-in)
      val defn = SymbolDefinition("Foo#apply().", "apply", isType = false,
        new Range(0, 0, 0, 0), file)
      val res = provider.sourceLineFallback(defn).get
      assert(res._1.contains("case class Foo("), s"expected class decl, got: ${res._1}")
      assert(res._1.contains("x: Int"), s"expected continuation lines, got: ${res._1}")
      assertEquals(res._2, None)
    } finally os.remove.all(root)
  }
}
