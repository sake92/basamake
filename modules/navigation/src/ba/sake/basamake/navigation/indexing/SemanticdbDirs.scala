package ba.sake.basamake.navigation.indexing

/** Source root paired with its SemanticDB output directory for a single build target.
  *
  * @param sourceRootDir URI string of the source root (from scalac `-sourceroot` option, defaults to workspace root)
  * @param semanticdbDir URI string of the SemanticDB output directory (from `-semanticdb-target` or class directory)
  */
final case class SemanticdbDirs(sourceRootDir: String, semanticdbDir: String)
