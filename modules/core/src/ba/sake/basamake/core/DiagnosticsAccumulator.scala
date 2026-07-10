package ba.sake.basamake.core

import org.eclipse.lsp4j.*

/**
 * Pure-function diagnostics accumulator. Maintains per-URI, per-target diagnostic lists
 * with BSP reset semantics, and produces full LSP publish lists.
 */
object DiagnosticsAccumulator {

  /** targetId → List[Diagnostic] */
  type PerTarget = Map[String, List[Diagnostic]]

  /** Full accumulated state: URI → PerTarget */
  type State = Map[String, PerTarget]


  val empty: State = Map.empty

  /**
   * Apply a BSP publish-diagnostics params to the accumulator.
   * Returns the new state and the full diagnostic list to publish for this URI.
   */
  def apply(
      state: State,
      uri: String,
      targetId: String,
      reset: Boolean,
      newDiags: List[Diagnostic]
  ): (State, List[Diagnostic]) =
    val perTarget = state.getOrElse(uri, Map.empty)
    val updated =
      if reset then perTarget + (targetId -> newDiags)
      else perTarget + (targetId -> (perTarget.getOrElse(targetId, Nil) ++ newDiags))
    val newState = state + (uri -> updated)
    val allDiags = updated.values.flatten.toList
    (newState, allDiags)

  def clearUri(state: State, uri: String): State =
    state - uri

}