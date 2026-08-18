package ba.sake.basamake.index.javasrc

import com.github.javaparser.Range as JpRange
import scala.meta.internal.semanticdb.Range

object JavaPositionUtils {

  /** Convert a javaparser `Range` to a semanticdb `Range`.
    * javaparser v3.28.x ranges are 1-BASED on lines AND columns, with END INCLUSIVE.
    * semanticdb expects 0-based for both, with end EXCLUSIVE.
    * Subtract 1 from lines and start column; end column stays as-is (converts inclusive→exclusive).
    */
  def toRange(r: JpRange): Range =
    new Range(
      startLine = r.begin.line - 1,
      startCharacter = r.begin.column - 1,
      endLine = r.end.line - 1,
      endCharacter = r.end.column
    )
}
