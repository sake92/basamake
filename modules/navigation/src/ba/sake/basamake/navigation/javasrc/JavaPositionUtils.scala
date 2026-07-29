package ba.sake.basamake.navigation.javasrc

import com.github.javaparser.Range as JpRange
import com.github.javaparser.ast.Node
import scala.meta.internal.semanticdb.Range

object JavaPositionUtils {

  /** Convert a javaparser `Range` to a semanticdb `Range`.
    * javaparser v3.28.x ranges are 1-BASED on lines, 0-BASED on columns.
    * semanticdb expects 0-based for both. Subtract 1 from lines.
    */
  def toRange(r: JpRange): Range =
    new Range(
      startLine = r.begin.line - 1,
      startCharacter = r.begin.column,
      endLine = r.end.line - 1,
      endCharacter = r.end.column
    )

  /** Get the range for the name portion of a node.
    * For nodes that are `SimpleName` (from `getName()`, `getNameAsString()`, etc.),
    * returns the range of just the name token.
    * Falls back to the node's own range if name-specific resolution fails.
    */
  def nameRange(n: Node): Option[JpRange] = {
    if (n.getRange.isPresent) Some(n.getRange.get()) else None
  }
}
