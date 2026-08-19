package ba.sake.basamake.index.indexing

import munit.FunSuite
import ba.sake.basamake.index.*
import scala.meta.internal.semanticdb.{Language, Schema, TextDocument, TextDocuments, Range as SdbRange, SymbolOccurrence}
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.FileOutputStream

/** Goto-definition FROM inside dep/JDK sources and package-segment cursors.
  * Dep-file parsing must resolve same-jar refs (owning-jar candidates), Java
  * imports must emit refs, package segments must resolve to package objects
  * (Scala), and cross-jar dep→dep must stay a clean miss. */
class SourceNavigationTest extends FunSuite, TestCacheRoot {

  private def eventually(cond: => Boolean, timeoutMs: Long = 30000): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond && System.currentTimeMillis() < deadline) Thread.sleep(50)
    cond
  }

  /** Multi-entry sources jar + sibling classes jar (one dummy .class per source
    * entry, so metadata.json package filtering works). */
  private def writeJar(dir: os.Path, name: String, entries: List[(String, String)]): os.Path = {
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

  /** Index the jar, extract the file defining `sym`, open it in a fresh
    * WorkspaceIndex, return (idx, deps, extractedFilePath). */
  private def openDepFile(
      jar: os.Path,
      sym: String,
      ws: os.Path
  ): (WorkspaceIndex, IndexedSymbolTable, os.Path) = {
    val deps = new IndexedSymbolTable(cacheRoot = testCacheRoot)
    deps.registerTarget(List(jar))
    assert(eventually(deps.get(sym, List(jar)).isDefined), s"warm-up must index $sym")
    val depFile = deps.get(sym, List(jar)).get.path // lookup extracts the file
    val idx = new WorkspaceIndex(ws, new InMemorySymbolTable, Some(deps))
    idx.onDidOpen(depFile)
    (idx, deps, depFile)
  }

  test("Scala dep file: same-package body ref resolves into the same jar") {
    val jarDir = os.temp.dir(prefix = "dep-nav-scala-")
    val ws = os.temp.dir(prefix = "dep-nav-ws-")
    val jar = writeJar(jarDir, "lib-sources.jar", List(
      "com/example/Foo.scala" -> "package com.example\nclass Foo\n",
      "com/example/Bar.scala" -> "package com.example\nclass Bar { val f: Foo = new Foo() }\n"
    ))
    try {
      val (idx, deps, barFile) = openDepFile(jar, "com/example/Bar#", ws)
      val text = os.read(barFile)
      val (l, c) = TestPositions.at(text, "new (?<p>Foo)")
      val cands = deps.candidatesForPath(barFile)
      assert(cands.contains(jar), s"owning-jar candidates expected, got $cands")
      val locs = idx.gotoDefinitions(barFile, l, c, depCandidates = cands)
      assert(locs.nonEmpty, s"same-jar body ref must resolve, got empty")
      assertEquals(locs.map(_.path.last).toSet, Set("Foo.scala"))
    } finally {
      os.remove.all(jarDir); os.remove.all(ws)
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(jar)))
    }
  }

  test("Java dep file: same-package body ref resolves into the same jar") {
    val jarDir = os.temp.dir(prefix = "dep-nav-java-")
    val ws = os.temp.dir(prefix = "dep-nav-ws-")
    val jar = writeJar(jarDir, "lib-sources.jar", List(
      "com/example/Foo.java" -> "package com.example;\npublic class Foo {}\n",
      "com/example/Bar.java" -> "package com.example;\npublic class Bar { public Foo foo = new Foo(); }\n"
    ))
    try {
      val (idx, deps, barFile) = openDepFile(jar, "com/example/Bar#", ws)
      val text = os.read(barFile)
      val (l, c) = TestPositions.at(text, "public (?<p>Foo) foo")
      val locs = idx.gotoDefinitions(barFile, l, c, depCandidates = deps.candidatesForPath(barFile))
      assert(locs.nonEmpty, s"same-jar field type ref must resolve, got empty")
      assertEquals(locs.map(_.path.last).toSet, Set("Foo.java"))
    } finally {
      os.remove.all(jarDir); os.remove.all(ws)
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(jar)))
    }
  }

  test("Java dep file: import-line ref resolves into the same jar") {
    val jarDir = os.temp.dir(prefix = "dep-nav-jimport-")
    val ws = os.temp.dir(prefix = "dep-nav-ws-")
    val jar = writeJar(jarDir, "lib-sources.jar", List(
      "com/example/Foo.java" -> "package com.example;\npublic class Foo {}\n",
      "com/example/Bar.java" -> "package com.example;\nimport com.example.Foo;\npublic class Bar { public Foo foo = new Foo(); }\n"
    ))
    try {
      val (idx, deps, barFile) = openDepFile(jar, "com/example/Bar#", ws)
      val text = os.read(barFile)
      val (l, c) = TestPositions.at(text, "import com.example.(?<p>Foo);")
      val locs = idx.gotoDefinitions(barFile, l, c, depCandidates = deps.candidatesForPath(barFile))
      assert(locs.nonEmpty, s"import-line ref must resolve, got empty")
      assertEquals(locs.map(_.path.last).toSet, Set("Foo.java"))
    } finally {
      os.remove.all(jarDir); os.remove.all(ws)
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(jar)))
    }
  }

  // ── package-segment cursors → package objects ─────────────────

  /** Workspace fixture: package object + a file importing from that package. */
  private def workspaceWithPkgObj(): (os.Path, os.Path, String, WorkspaceIndex) = {
    val ws = os.temp.dir(prefix = "pkgseg-ws-")
    val pkgFile = ws / "com" / "package.scala"
    os.write.over(pkgFile, "package com\npackage object example { val x = 1 }\n", createFolders = true)
    val barFile = ws / "com" / "example" / "Bar.scala"
    os.write.over(barFile, "package com.example\nimport com.example.Foo\nclass Bar\n", createFolders = true)
    val idx = new WorkspaceIndex(ws, new InMemorySymbolTable)
    idx.initialize(Nil) // Pass B extracts the package object def
    idx.onDidOpen(barFile)
    (ws, barFile, os.read(barFile), idx)
  }

  test("package-segment cursor resolves to the package object (workspace)") {
    val (ws, barFile, text, idx) = workspaceWithPkgObj()
    try {
      val (l, c) = TestPositions.at(text, "com.(?<p>example).Foo")
      val locs = idx.gotoDefinitions(barFile, l, c)
      assert(locs.nonEmpty, s"package segment must resolve to the package object, got empty")
      assertEquals(locs.map(_.symbol).toSet, Set("com/example/package."))
      assert(locs.head.path.endsWith(os.RelPath("com/package.scala")))
    } finally os.remove.all(ws)
  }

  test("package-segment cursor without a package object stays a clean miss") {
    val ws = os.temp.dir(prefix = "pkgseg-nopkgobj-ws-")
    val barFile = ws / "com" / "example" / "Bar.scala"
    val text = "package com.example\nimport com.example.Foo\nclass Bar\n"
    os.write.over(barFile, text, createFolders = true)
    val idx = new WorkspaceIndex(ws, new InMemorySymbolTable)
    try {
      idx.initialize(Nil)
      idx.onDidOpen(barFile)
      val (l, c) = TestPositions.at(text, "com.(?<p>example).Foo")
      assertEquals(idx.gotoDefinitions(barFile, l, c), Vector.empty)
    } finally os.remove.all(ws)
  }

  test("semanticdb package occurrence resolves to the package object") {
    val ws = os.temp.dir(prefix = "pkgseg-semdb-ws-")
    val pkgFile = ws / "com" / "package.scala"
    os.write.over(pkgFile, "package com\npackage object example { val x = 1 }\n", createFolders = true)
    val mainFile = ws / "Main.scala"
    os.write.over(mainFile, "import com.example.Foo\n")
    val semDir = ws / ".semanticdb"
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = "Main.scala",
      text = os.read(mainFile),
      language = Language.SCALA,
      symbols = Nil,
      occurrences = List(
        SymbolOccurrence(symbol = "com/example/", range = Some(SdbRange(0, 11, 0, 18)), role = SymbolOccurrence.Role.REFERENCE)
      )
    )
    os.makeDir.all(semDir)
    os.write(semDir / "Main.scala.semanticdb", TextDocuments(List(doc)).toByteArray)
    val idx = new WorkspaceIndex(ws, new InMemorySymbolTable)
    try {
      idx.initialize(List(SemanticdbDirs(ws, semDir)))
      idx.onDidOpen(mainFile)
      val locs = idx.gotoDefinitions(mainFile, 0, 12)
      assert(locs.nonEmpty, s"semanticdb package occurrence must resolve, got empty")
      assertEquals(locs.map(_.symbol).toSet, Set("com/example/package."))
    } finally os.remove.all(ws)
  }

  test("package-segment cursor in a dep file resolves to the jar's package object") {
    val jarDir = os.temp.dir(prefix = "dep-nav-pkgobj-")
    val ws = os.temp.dir(prefix = "dep-nav-ws-")
    val jar = writeJar(jarDir, "lib-sources.jar", List(
      "com/package.scala" -> "package com\npackage object example { val x = 1 }\n",
      "com/example/Foo.scala" -> "package com.example\nclass Foo\n",
      "com/example/Bar.scala" -> "package com.example\nimport com.example.Foo\nclass Bar\n"
    ))
    try {
      val (idx, deps, barFile) = openDepFile(jar, "com/example/Bar#", ws)
      assert(eventually(deps.get("com/example/package.", List(jar)).isDefined), "package object must be indexed")
      val text = os.read(barFile)
      val (l, c) = TestPositions.at(text, "com.(?<p>example).Foo")
      val locs = idx.gotoDefinitions(barFile, l, c, depCandidates = deps.candidatesForPath(barFile))
      assert(locs.nonEmpty, s"dep package segment must resolve to the jar's package object, got empty")
      assertEquals(locs.map(_.symbol).toSet, Set("com/example/package."))
    } finally {
      os.remove.all(jarDir); os.remove.all(ws)
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(jar)))
    }
  }

  // ── cross-jar dep→dep stays out of scope ──────────────────────

  test("cross-jar dep→dep stays out of scope: owning-jar candidates miss cleanly") {
    val dirA = os.temp.dir(prefix = "dep-nav-jarA-")
    val dirB = os.temp.dir(prefix = "dep-nav-jarB-")
    val ws = os.temp.dir(prefix = "dep-nav-ws-")
    val jarA = writeJar(dirA, "a-sources.jar", List(
      "com/a/Foo.java" -> "package com.a;\nimport com.b.Bar;\npublic class Foo { public Bar b = new Bar(); }\n"
    ))
    val jarB = writeJar(dirB, "b-sources.jar", List(
      "com/b/Bar.java" -> "package com.b;\npublic class Bar {}\n"
    ))
    try {
      val deps = new IndexedSymbolTable(cacheRoot = testCacheRoot)
      deps.registerTarget(List(jarA, jarB))
      assert(eventually(deps.get("com/a/Foo#", List(jarA)).isDefined), "warm jarA")
      assert(eventually(deps.get("com/b/Bar#", List(jarB)).isDefined), "warm jarB")
      val fooFile = deps.get("com/a/Foo#", List(jarA)).get.path
      val idx = new WorkspaceIndex(ws, new InMemorySymbolTable, Some(deps))
      idx.onDidOpen(fooFile)
      val text = os.read(fooFile)
      val owningOnly = deps.candidatesForPath(fooFile)
      assertEquals(owningOnly, List(jarA), "dep file candidates must be the owning jar only")

      // sanity: with jarB explicitly in the candidate set, the IMPORT ref resolves
      // (import symbols are deterministic from the statement; the body ref is
      // dropped at parse time — the resolver only sees the owning jar)
      val (lImp, cImp) = TestPositions.at(text, "import com.b.(?<p>Bar);")
      assert(idx.gotoDefinitions(fooFile, lImp, cImp, depCandidates = List(jarA, jarB)).nonEmpty,
        "fixture sanity: import Bar resolves when jarB is a candidate")

      // pin: owning-jar-only candidates must NOT reach jarB — clean miss
      assertEquals(idx.gotoDefinitions(fooFile, lImp, cImp, depCandidates = owningOnly), Vector.empty,
        "cross-jar import ref must miss with owning-jar candidates")
      val (lBody, cBody) = TestPositions.at(text, "new (?<p>Bar)()")
      assertEquals(idx.gotoDefinitions(fooFile, lBody, cBody, depCandidates = owningOnly), Vector.empty,
        "cross-jar body ref must miss with owning-jar candidates")
    } finally {
      os.remove.all(dirA); os.remove.all(dirB); os.remove.all(ws)
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(jarA)))
      os.remove.all(testCacheRoot / os.RelPath(Fingerprint.fromJarPath(jarB)))
    }
  }
}
