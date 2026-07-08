# Milestone 3 — SemanticDB navigation

**Goal:** go-to-definition, find-references, document symbols (and later rename). This is
where the server stops being a proxy and becomes a language server. Read `00-overview.md`
and complete M1/M2 first.

**Key realization:** SemanticDB is produced *at compile time*, so after an M1 compile the
`.semanticdb` files are already on disk. We are not invoking anything new — we read artifacts.

## Deliverables

- Per-target SemanticDB indexing after each successful compile.
- `textDocument/definition`, `textDocument/references`, `textDocument/documentSymbol`.
- (Later) `textDocument/rename`, dependency-source navigation.

## Flow

1. From BSP `buildTarget/scalacOptions`, confirm/inject `-Xsemanticdb` (Scala 3) and read each
   target's `classDirectory`. `.semanticdb` files land under
   `<classDir>/META-INF/semanticdb/**.semanticdb`.
2. Parse them with **scalameta's** `semanticdb` readers — do NOT hand-roll the protobuf. Each
   `TextDocument` yields `occurrences` (symbol ⇄ source range) and `symbols` (symbol ⇄
   metadata).
3. Build two in-memory indexes per build target:
   - `Map[SymbolString, DefinitionLocation]`
   - `Map[SymbolString, List[ReferenceLocation]]`
4. Answer queries:
   - **go-to-def**: find the occurrence under the cursor → look up its symbol's definition.
   - **find-refs**: the reverse map.
   - **document symbols**: filter one file's `symbols`.

## Invalidation

- Index keyed **by file** so slices can be replaced without a full rebuild.
- On each successful compile of a target, re-read that target's changed `.semanticdb` files
  and update only those file slices.
- Partition the index per build server (M2): separate classpaths and class dirs. Union only
  at query time.

## Known limitation — do not try to fix

SemanticDB navigation is always **one compile behind** — it reflects the last successful
compile, not the current buffer. Right after edits, go-to-def can point at a slightly stale
location. This is inherent to the approach (Metals has it too) and users tolerate it. Don't
fight it.

## Dependency-source navigation (defer)

Jumping into library code uses BSP `buildTarget/dependencySources` to get source jars; index
them the same way. Can be added after in-workspace navigation works.

## Definition of done

- Go-to-def resolves within-file and cross-file symbols after a compile.
- Find-references returns the reverse set.
- Document symbols populates the outline.
- Editing a file and recompiling updates only that file's index slice, not the whole index.
