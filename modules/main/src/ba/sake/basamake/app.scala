package ba.sake.basamake

import ba.sake.basamake.navigation.*
import ba.sake.basamake.navigation.scalasrc.*
import ba.sake.basamake.lsp.index.*

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


  val symbolTable = new SymbolTable
  val extractor = new ScalaDefinitionsExtractor(symbolTable)
  extractor.extractFromContent(fileName, code, filePath)
  println(s"extracted symbols:\n${symbolTable.all.toSeq.sortBy(_.symbol).mkString("\n")}")



  val workspaceIndex = new WorkspaceIndex(symbolTable)
  workspaceIndex.onDidOpen(filePath, code)
  {
    val definitions = workspaceIndex.gotoDefinitions(filePath, line = 2, char = 14)
    println(s"gotoDefinition: ${definitions}")
  }
}