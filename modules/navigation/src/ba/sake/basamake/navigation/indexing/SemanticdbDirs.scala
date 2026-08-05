package ba.sake.basamake.navigation.indexing

/** Source root paired with its SemanticDB output directory for a single build target.
  *
  * @param sourceRootDir source root for SemanticDB URI resolution (from scalac `-sourceroot`
  *                      when present, else the BSP working dir / workspace root; ancestor
  *                      climbing in the indexer covers remaining layout mismatches)
  * @param semanticdbDir SemanticDB output directory (from `-semanticdb-target` or class directory)
  */
final case class SemanticdbDirs(sourceRootDir: os.Path, semanticdbDir: os.Path)
