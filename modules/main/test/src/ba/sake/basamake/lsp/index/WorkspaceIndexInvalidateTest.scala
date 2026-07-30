package ba.sake.basamake.lsp.index

import ba.sake.basamake.navigation.SymbolTable
import munit.FunSuite

class WorkspaceIndexInvalidateTest extends FunSuite {

  test("invalidate does not throw on an empty dir list (no-op)") {
    val st = new SymbolTable
    val idx = new WorkspaceIndex(st)
    idx.invalidate(Nil) // must not throw
  }

  test("invalidate accepts a directory string and does not throw even if dir does not exist") {
    val st = new SymbolTable
    val idx = new WorkspaceIndex(st)
    idx.invalidate(List("/no/such/dir/anywhere"))
  }
}
