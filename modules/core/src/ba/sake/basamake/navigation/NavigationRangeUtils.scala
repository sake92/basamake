package ba.sake.basamake.navigation

import org.eclipse.lsp4j.{Position, Range}

object NavigationRangeUtils {
  def contains(range: Range, pos: Position): Boolean = {
    val startsBefore =
      pos.getLine > range.getStart.getLine ||
        (pos.getLine == range.getStart.getLine && pos.getCharacter >= range.getStart.getCharacter)
    val endsAfter =
      pos.getLine < range.getEnd.getLine ||
        (pos.getLine == range.getEnd.getLine && pos.getCharacter <= range.getEnd.getCharacter)
    startsBefore && endsAfter
  }
}
