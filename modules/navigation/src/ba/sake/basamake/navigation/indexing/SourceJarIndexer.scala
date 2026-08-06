package ba.sake.basamake.navigation.indexing

import ba.sake.basamake.navigation.{SymbolTable, InMemorySymbolTable, SymbolDefinition}
import ba.sake.basamake.navigation.scalasrc.ScalaDefinitionsExtractor
import ba.sake.basamake.navigation.javasrc.JavaDefinitionsExtractor
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

object SourceJarIndexer {

  def index(jar: os.Path, fingerprint: String): SymbolTable = {
    val indexPath = os.home / ".basamake" / "deps" / fingerprint / "index.lmdb"

    if (os.exists(indexPath)) {
      return LmdbSerializer.load(indexPath)
    }

    val table = new InMemorySymbolTable()
    val scalaExtractor = new ScalaDefinitionsExtractor(table)
    val javaExtractor = new JavaDefinitionsExtractor(table)
    val zip = new ZipFile(jar.toIO)

    try {
      zip.entries().asScala.foreach { entry =>
        if (!entry.isDirectory) {
          val entryPath = entry.getName
          val source = new String(zip.getInputStream(entry).readAllBytes(), "UTF-8")
          val sourceRoot = os.home / ".basamake" / "deps" / fingerprint / "source"
          val extractedPath = sourceRoot / os.RelPath(entryPath)

          if (entryPath.endsWith(".java")) {
            javaExtractor.extractFromContent(entryPath, source, extractedPath)
          } else if (entryPath.endsWith(".scala") || entryPath.endsWith(".sbt")) {
            scalaExtractor.extractFromContent(entryPath, source, extractedPath)
          }
        }
      }
    } finally {
      zip.close()
    }

    LmdbSerializer.save(table, indexPath)
    LmdbSerializer.load(indexPath)
  }
}
