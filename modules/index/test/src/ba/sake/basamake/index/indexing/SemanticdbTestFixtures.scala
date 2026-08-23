package ba.sake.basamake.index.indexing

import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, Range => SdbRange, SymbolOccurrence}

/** Hand-written utils/Main semanticdb fixture shared by several suites:
  *   root/src/main/scala/{Main,utils}.scala
  *   root/target/scala-3.8.4/meta/META-INF/semanticdb/src/main/scala/{Main,utils}.scala.semanticdb
  * Main.scala references `ext.getMsg()` — the SOURCE parser cannot resolve `ext`
  * (empty symbol), so `_empty_/utils.getMsg().` can ONLY come from semanticdb.
  * This discriminates semanticdb-based occurrences from source-parsed ones. */
object SemanticdbTestFixtures {

  val UtilsContent: String = "object utils:\n  def getMsg() = \"bla\"\n"
  val MainContent: String = "object Main:\n  def main(args: Array[String]): Unit =\n    println(ext.getMsg())\n"

  /** Write source files + semanticdb files under `root` (directories created).
    * Returns the semanticdb OUTPUT dir (parent of META-INF) for SemanticdbDirs. */
  def writeUtilsMainFixture(root: os.Path): os.Path = {
    val srcDir = root / "src" / "main" / "scala"
    os.makeDir.all(srcDir)
    val semDir = root / "target" / "scala-3.8.4" / "meta"
    os.makeDir.all(semDir / "META-INF" / "semanticdb" / "src" / "main" / "scala")

    os.write(srcDir / "utils.scala", UtilsContent)
    os.write(srcDir / "Main.scala", MainContent)

    val utilsDoc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "src/main/scala/utils.scala",
      text = UtilsContent,
      language = Language.SCALA,
      symbols = Nil,
      occurrences = List(
        SymbolOccurrence(symbol = "_empty_/utils.", range = Some(SdbRange(0, 7, 0, 12)), role = SymbolOccurrence.Role.DEFINITION),
        SymbolOccurrence(symbol = "_empty_/utils.getMsg().", range = Some(SdbRange(1, 6, 1, 12)), role = SymbolOccurrence.Role.DEFINITION)
      )
    )
    val mainDoc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "src/main/scala/Main.scala",
      text = MainContent,
      language = Language.SCALA,
      symbols = Nil,
      occurrences = List(
        SymbolOccurrence(symbol = "_empty_/utils.", range = Some(SdbRange(2, 12, 2, 15)), role = SymbolOccurrence.Role.REFERENCE),
        SymbolOccurrence(symbol = "_empty_/utils.getMsg().", range = Some(SdbRange(2, 16, 2, 22)), role = SymbolOccurrence.Role.REFERENCE)
      )
    )
    os.write(semDir / "META-INF" / "semanticdb" / "src" / "main" / "scala" / "utils.scala.semanticdb", TextDocuments(List(utilsDoc)).toByteArray)
    os.write(semDir / "META-INF" / "semanticdb" / "src" / "main" / "scala" / "Main.scala.semanticdb", TextDocuments(List(mainDoc)).toByteArray)
    semDir
  }
}
