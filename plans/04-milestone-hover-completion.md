# Milestone 4 — Hover & completion (presentation compiler)

**Goal:** hover, completion, signature help. **Optional / "if easy enough."** Read
`00-overview.md` first; complete M1–M3.

**Hard truth:** these are reachable *only* via the presentation compiler. There is no
BSP-based or SemanticDB-based substitute for completion/hover. Declining the presentation
compiler means structurally no completions — it's a capability you decline, not a shortcut you
skip.

## Deliverables

- One embedded `scala3-presentation-compiler` (PC) instance per build target.
- `textDocument/hover`, `textDocument/completion`, `textDocument/signatureHelp`.

## Approach

- Embed `scala3-presentation-compiler` **as a library** (this is what Metals does). Do NOT
  reimplement or drive `dotc` by hand.
- Feed it the classpath + scalac options already obtained from BSP (`buildTarget/scalacOptions`).
- Hand it the **current buffer contents** (not disk) plus a cursor offset. It returns
  completions/hover/signatures.
- The PC is **stateful and per-target**: create lazily, keep one per build target, **dispose
  and recreate on classpath change**. Store the instance in the connection's ephemeral scope.

## Why this is deferred / risky

- It's the one component with real async + cancellation baked into its API — the thing we
  otherwise avoid. Wrap it so cancellation flows through ox like everything else, and honor
  rapid-typing cancellation (a newer completion request supersedes an in-flight one).
- Most sensitive to **version skew** — PC version must match the Scala version of the target.
- Edge cases: classpath/version pinning per target, invalidation on file change, distinct
  contexts (source vs. dependency-source; worksheets are out of scope).

## Prove the risk FIRST

Before wiring the full feature: get a single completion to fire against the PC for **one
hardcoded file** with a hardcoded classpath. This is the riskiest unknown in the whole
project — do it first, not last. If it works, the rest is integration; if it doesn't, better
to know before building the plumbing around it.

## Definition of done

- Completion fires at a cursor position using live buffer contents, with the target's real
  classpath.
- Hover shows type/signature info.
- Signature help populates on call sites.
- Rapid typing cancels superseded requests; classpath change disposes and recreates the PC.
