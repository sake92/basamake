package ba.sake.basamake.navigation

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import munit.FunSuite
import scala.meta.internal.semanticdb.{
  Range as SRange,
  Schema,
  SymbolInformation,
  SymbolOccurrence,
  TextDocument,
  TextDocuments
}

class SemanticdbIncrementalIndexTest extends FunSuite {

  private def writeSemanticdb(
      root: os.Path,
      name: String,
      sourceUri: String,
      symbol: String
  ): os.Path = {
    val doc = TextDocument(
      schema = Schema.SEMANTICDB4,
      uri = sourceUri,
      occurrences = Seq(
        SymbolOccurrence(
          Some(new SRange(0, 0, 0, 5)),
          symbol,
          SymbolOccurrence.Role.DEFINITION
        )
      ),
      symbols = Seq(
        SymbolInformation(
          symbol,
          kind = SymbolInformation.Kind.OBJECT,
          displayName = name.stripSuffix(".scala.semanticdb")
        )
      )
    )
    val target = root / "META-INF" / "semanticdb" / name
    os.makeDir.all(target / os.up)
    os.write.over(target, TextDocuments(Seq(doc)).toByteArray)
    target
  }

  private def bumpMtime(path: os.Path): Unit =
    Files.setLastModifiedTime(
      path.toNIO,
      FileTime.fromMillis(Files.getLastModifiedTime(path.toNIO).toMillis + 60_000)
    )

  test("unchanged files reuse the same slice instance") {
    val tmp = os.temp.dir(prefix = "sdb-incr-reuse")
    try {
      val root = tmp / "out"
      val uriA = (tmp / "A.scala").toNIO.toUri.toString
      writeSemanticdb(root, "A.scala.semanticdb", uriA, "a/A.")

      val empty = SemanticdbIndexing.WorkspaceIndexState(Map.empty, Nil)
      val (res1, st1) =
        SemanticdbIndexing.indexWorkspaceTargetIncremental(tmp, Set(root), Nil, empty)
      val (res2, _) =
        SemanticdbIndexing.indexWorkspaceTargetIncremental(tmp, Set(root), Nil, st1)

      assert(res1.contains(uriA))
      assert(res2(uriA) `eq` res1(uriA), "expected identical slice instance for unchanged file")
    } finally os.remove.all(tmp)
  }

  test("mtime bump triggers re-parse of only that file") {
    val tmp = os.temp.dir(prefix = "sdb-incr-bump")
    try {
      val root = tmp / "out"
      val uriA = (tmp / "A.scala").toNIO.toUri.toString
      val uriB = (tmp / "B.scala").toNIO.toUri.toString
      val fileA = writeSemanticdb(root, "A.scala.semanticdb", uriA, "a/A.")
      writeSemanticdb(root, "B.scala.semanticdb", uriB, "b/B.")

      val empty = SemanticdbIndexing.WorkspaceIndexState(Map.empty, Nil)
      val (res1, st1) =
        SemanticdbIndexing.indexWorkspaceTargetIncremental(tmp, Set(root), Nil, empty)

      bumpMtime(fileA)
      val (res2, _) =
        SemanticdbIndexing.indexWorkspaceTargetIncremental(tmp, Set(root), Nil, st1)

      assert(!(res2(uriA) `eq` res1(uriA)), "expected re-parse for touched file")
      assert(res2(uriB) `eq` res1(uriB), "expected reuse for untouched file")
    } finally os.remove.all(tmp)
  }

  test("deleted files are dropped from result and state") {
    val tmp = os.temp.dir(prefix = "sdb-incr-del")
    try {
      val root = tmp / "out"
      val uriA = (tmp / "A.scala").toNIO.toUri.toString
      val fileA = writeSemanticdb(root, "A.scala.semanticdb", uriA, "a/A.")

      val empty = SemanticdbIndexing.WorkspaceIndexState(Map.empty, Nil)
      val (res1, st1) =
        SemanticdbIndexing.indexWorkspaceTargetIncremental(tmp, Set(root), Nil, empty)
      assert(res1.contains(uriA))

      os.remove(fileA)
      val (res2, st2) =
        SemanticdbIndexing.indexWorkspaceTargetIncremental(tmp, Set(root), Nil, st1)

      assert(!res2.contains(uriA))
      assert(!st2.files.contains(fileA))
    } finally os.remove.all(tmp)
  }

  test("sourceRoots change forces full re-parse") {
    val tmp = os.temp.dir(prefix = "sdb-incr-roots")
    try {
      val root = tmp / "out"
      val uriA = (tmp / "A.scala").toNIO.toUri.toString
      writeSemanticdb(root, "A.scala.semanticdb", uriA, "a/A.")

      val empty = SemanticdbIndexing.WorkspaceIndexState(Map.empty, Nil)
      val (res1, st1) =
        SemanticdbIndexing.indexWorkspaceTargetIncremental(tmp, Set(root), Nil, empty)

      val (res2, _) =
        SemanticdbIndexing.indexWorkspaceTargetIncremental(tmp, Set(root), List(tmp), st1)

      assert(!(res2(uriA) `eq` res1(uriA)), "expected re-parse when sourceRoots change")
    } finally os.remove.all(tmp)
  }

  test("unparseable file is skipped, not fingerprinted, and self-heals") {
    val tmp = os.temp.dir(prefix = "sdb-incr-heal")
    try {
      val root = tmp / "out"
      val uriA = (tmp / "A.scala").toNIO.toUri.toString
      val bad = root / "META-INF" / "semanticdb" / "A.scala.semanticdb"
      os.makeDir.all(bad / os.up)
      os.write(bad, Array[Byte](1, 2, 3, 4))

      val empty = SemanticdbIndexing.WorkspaceIndexState(Map.empty, Nil)
      val (res1, st1) =
        SemanticdbIndexing.indexWorkspaceTargetIncremental(tmp, Set(root), Nil, empty)

      assert(!res1.contains(uriA), "garbage bytes must not produce a slice")
      assert(!st1.files.contains(bad), "failed parse must not store a fingerprint")

      writeSemanticdb(root, "A.scala.semanticdb", uriA, "a/A.")
      val (res2, _) =
        SemanticdbIndexing.indexWorkspaceTargetIncremental(tmp, Set(root), Nil, st1)

      assert(res2.contains(uriA), "file must be indexed once it becomes parseable")
    } finally os.remove.all(tmp)
  }
}
