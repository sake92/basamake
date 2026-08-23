package ba.sake.basamake.index.indexing

import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, SymbolOccurrence}

/** Shared helpers for dep-index tests (sources-jar fixtures, polling, semanticdb pairing). */
object DepTestUtils {

  /** Poll `cond` until true or the timeout expires. */
  def eventually(cond: => Boolean, timeoutMs: Long = 30000): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(50)
    cond
  }

  /** Multi-entry sources jar + sibling classes jar (one dummy .class per entry,
    * so metadata.json package filtering works). */
  def writeJar(dir: os.Path, name: String, entries: List[(String, String)]): os.Path = {
    val sourcesJar = dir / name
    val sources = new ZipOutputStream(new FileOutputStream(sourcesJar.toIO))
    try {
      entries.foreach { case (entry, content) =>
        sources.putNextEntry(new ZipEntry(entry)); sources.write(content.getBytes("UTF-8")); sources.closeEntry()
      }
    } finally sources.close()
    val classesJar = dir / (name.stripSuffix("-sources.jar") + ".jar")
    val classes = new ZipOutputStream(new FileOutputStream(classesJar.toIO))
    try {
      entries.foreach { case (entry, _) =>
        val base = entry.split('/').last.stripSuffix(".scala").stripSuffix(".java")
        val pkgPath = entry.split('/').toList.dropRight(1).mkString("/")
        classes.putNextEntry(new ZipEntry(s"$pkgPath/$base.class")); classes.write(Array[Byte](1, 2)); classes.closeEntry()
      }
    } finally classes.close()
    sourcesJar
  }

  /** Single-entry Java sources jar + classes sibling (coursier-style). */
  def writeJarPair(dir: os.Path, name: String, pkg: String, methodName: String = "bar", className: String = "Foo"): os.Path = {
    val pkgPath = pkg.replace('.', '/')
    writeJar(dir, name, List(
      s"$pkgPath/$className.java" -> s"package $pkg;\npublic class $className { public void $methodName() {} }\n"
    ))
  }

  /** Pair `Main.scala` at the workspace root with a hand-crafted semanticdb
    * whose occurrences carry FULL dep symbols (as a real compile would). */
  def pairMainWithSemanticdb(workspace: os.Path, occurrences: List[SymbolOccurrence]): Unit = {
    val semDir = workspace / ".semanticdb"
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "Main.scala",
      text = os.read(workspace / "Main.scala"),
      language = Language.SCALA,
      symbols = Nil,
      occurrences = occurrences
    )
    os.makeDir.all(semDir)
    os.write(semDir / "Main.scala.semanticdb", TextDocuments(List(doc)).toByteArray)
  }
}
