package ba.sake.basamake.navigation.indexing

import ba.sake.basamake.navigation.SymbolTable
import munit.FunSuite

class WorkspaceIndexInvalidateTest extends FunSuite {

  test("invalidate does not throw on an empty dir list (no-op)") {
    val st = new SymbolTable
    val idx = new WorkspaceIndex(os.pwd, st)
    idx.invalidate(Nil) // must not throw
  }

  test("invalidate accepts SemanticdbDirs and does not throw even if dir does not exist") {
    val st = new SymbolTable
    val idx = new WorkspaceIndex(os.pwd, st)
    idx.invalidate(List(SemanticdbDirs("/no/such/dir", "/no/such/dir")))
  }
}
