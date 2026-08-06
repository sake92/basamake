package ba.sake.basamake

import ba.sake.basamake.navigation.*
import ba.sake.basamake.navigation.scalasrc.*
import ba.sake.basamake.navigation.indexing.*

  @main def app(): Unit = {
    
  val fileName = "bla.scala"
  val filePath = os.pwd / fileName
  val code = 
    """|@main def blaMain(): Unit =
       |  println("Hello, Basamake!")
       |  val xyz = add(2, 3)
       |  println(xyz)
       |
       |def add(a: Int, b: Int): Int = a + b
       |""".stripMargin

  os.write.over(filePath, code)

  val symbolTable = new InMemorySymbolTable
  val extractor = new ScalaDefinitionsExtractor(symbolTable)
  extractor.extractFromContent(fileName, code, filePath)
  println(s"extracted symbols:\n${symbolTable.all.toSeq.sortBy(_.symbol).mkString("\n")}")



  val workspaceIndex = new WorkspaceIndex(os.pwd, symbolTable)
  workspaceIndex.onDidOpen(filePath)
  {
    val definitions = workspaceIndex.gotoDefinitions(filePath, line = 2, char = 14)
    println(s"gotoDefinition: ${definitions}")
  }
}