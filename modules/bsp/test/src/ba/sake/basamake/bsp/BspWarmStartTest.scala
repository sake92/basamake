package ba.sake.basamake.bsp

import munit.FunSuite
import ba.sake.tupson.{given, *}
import ba.sake.basamake.index.indexing.SemanticdbDirs

class BspWarmStartTest extends FunSuite {
  test("warm start ignores metadata from removed or disabled BSP configs") {
    val root = os.temp.dir(prefix = "bsp-warm-")
    try {
      val bspFile = ".bsp/mill-bsp.json"
      val sourceRoot = root / "src"
      val semanticdbDir = root / "out"
      val jar = root / "dep-sources.jar"
      val data = BspTargetData(bspFile, List(BspTargetInfo("target", sourceRoot, semanticdbDir, List(jar.toString))))
      os.write(root / ".basamake" / "bsp" / "mill" / "data.json", toJson(data), createFolders = true)
      assertEquals(BspWarmStart.load(root), (Nil, Nil))
      os.write(root / os.RelPath(bspFile), "{}", createFolders = true)
      assertEquals(BspWarmStart.load(root),
        (List(SemanticdbDirs(sourceRoot, semanticdbDir)), List(sourceRoot -> List(jar))))
      os.write(root / ".basamake" / "config.json",
        """{"bspOverrides":[{"bspFile":".bsp/mill-bsp.json","enabled":false}]}""")
      assertEquals(BspWarmStart.load(root), (Nil, Nil))
    } finally os.remove.all(root)
  }
}
