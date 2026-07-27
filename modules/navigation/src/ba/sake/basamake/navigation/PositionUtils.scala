package ba.sake.basamake.navigation

import scala.meta.inputs.Position
import scala.meta.internal.semanticdb.Range

object PositionUtils {
  def toRange(pos: Position): Range =
    new Range(
      startLine = pos.startLine,
      startCharacter = pos.startColumn,
      endLine = pos.endLine,
      endCharacter = pos.endColumn
    )
}
