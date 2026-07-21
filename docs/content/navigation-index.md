---
title: Navigation Index
description: SemanticDB-based go-to-definition, references, and document symbols with dependency source fallback
---

# Navigation Index

**Files:** `NavigationIndex.scala`, `SemanticdbIndexing.scala`, `DependencySourceIndexing.scala`, `DependencySourceParsing.scala`, `ScalaSourceParser.scala`, `JavaSourceParser.scala`, `NavigationSymbolLookup.scala`, `NavigationLocationUtils.scala`, `NavigationRangeUtils.scala`, `NavigationUriUtils.scala`

## Optional: Best-Effort Compilation

Go-to-definition and references work out of the box — the index is built from SemanticDB
files produced by normal successful compiles. No extra flags required.

By default, however, the compiler **does not** emit SemanticDB when compilation has
errors. So on broken code the index simply stays at the last successful compile's state:
navigation keeps working, but may be stale for files you just broke.

To get fresh navigation even while the code has errors, enable Best-Effort Compilation
(Scala ≥3.5):

```scala
// sbt
ThisBuild / scalacOptions += "-Ybest-effort"

// Deder (deder.pkl)
scalacOptions = Seq("-Ybest-effort")

// Scala CLI
//.scalacOptions += "-Ybest-effort"
```

With `-Ybest-effort`, the compiler runs through the typer and `ExtractSemanticDB` phases
even when errors exist, producing normal SemanticDB files. No index code changes
required — files land in the same `-semanticdb-target` directory and are picked up
automatically. Basamake detects the flag via `buildTargetScalacOptions` and re-indexes
after **failed** compiles too (without the flag, a failed compile skips re-indexing).

Check `status.json` (`.basamake/status.json` in your workspace root) for per-target
`bestEffortEnabled` and `semanticdbEnabled` flags to verify.

## How Indexing Works

```diagram:mermaid
flowchart TD
    subgraph TRIGGER["Refresh Trigger"]
        ROUTING["onRoutingReady<br/>(after BSP handshake)"]
        COMPILE["after compile<br/>(also on failed compile<br/>when -Ybest-effort)"]
    end

    TRIGGER --> RUNNER["Nav-refresh runner:<br/>serialized per connection,<br/>latest-wins, never blocks<br/>supervisor VT"]
    RUNNER --> REFRESH["NavigationIndex.refresh()"]

    REFRESH --> SRCROOTS["sourceRootsByTarget<br/>(from buildTargetSources)"]
    REFRESH --> DEPSRCS["dependencySourceUrisByTarget<br/>(from buildTargetDependencySources)"]
    REFRESH --> SCALAC["fetchScalacOptions()<br/>→ semanticdb flags?"]
    REFRESH --> OUTPUTS["fetchOutputRoots()<br/>→ buildTargetOutputPaths"]

    SCALAC --> CANDIDATE["candidateSemanticdbRoots<br/>(semanticdb output dirs)"]
    OUTPUTS --> CANDIDATE

    CANDIDATE --> WORKSPACE["SemanticdbIndexing.indexWorkspaceTarget<br/>(walk .semanticdb files, concurrent)"]
    DEPSRCS --> DEPS["DependencySourceIndexing.indexDependencySources<br/>(extract from JARs/ZIPs)"]

    WORKSPACE --> MERGE["TargetState:<br/>workspaceSlicesByTarget +<br/>dependencySlicesByTarget"]
    DEPS --> MERGE
```

`buildTargetOutputPaths` and `buildTargetScalacOptions` are issued concurrently and each
is awaited with a 10s timeout; on failure the refresh falls back to whatever data it has.

## Workspace Indexing

1. Find SemanticDB output directories via:
   - `-semanticdb-target:` (Scala 3) / `-P:semanticdb:targetroot:` (Scala 2) from scalac options — preferred
   - otherwise `buildTargetOutputPaths` output directories + `-d` class directory
   - if flags are absent but roots were discovered, a warning is logged and indexing proceeds anyway
2. Walk each root for `*.semanticdb` files
3. Parse files **concurrently** — one virtual thread per file
   (`Executors.newVirtualThreadPerTaskExecutor`), each awaited with a **10s timeout**;
   a timed-out or unparsable file is skipped with a warning, never blocks the rest
4. Resolve source URIs by traversing the `META-INF/semanticdb` path, trying each
   candidate source root (with segment-overlap matching)
5. Build `SemanticdbFileSlice` per source file containing:
   - Occurrences (symbol + range + definition flag)
   - Symbol definitions map (symbol → List[Location])
   - Symbol references map (symbol → List[Location])
   - Document symbols (for outline view)

## Dependency Source Indexing

For library dependencies (`-sources.jar`s / source directories from
`buildTargetDependencySources`):

```diagram:mermaid
flowchart TD
    URI["jar:file:///.../foo.jar!/some/Source.scala"] --> RESOLVE["resolveSourcePath"]
    RESOLVE --> IS_DIR{Path is dir?}
    IS_DIR -->|Yes| WALK["os.walk, filter .scala/.java"]
    RESOLVE --> IS_JAR{Path is JAR/ZIP?}
    IS_JAR -->|Yes| EXTRACT["readArchiveEntries<br/>→ extract to .basamake/dependency-sources/<br/>(persistent on-disk cache)"]
    RESOLVE --> IS_FILE{Path is .scala/.java?}
    IS_FILE -->|Yes| READ["readText"]
    RESOLVE --> HAS_BANG{URI contains ! ?}
    HAS_BANG -->|Yes| NESTED["strip jar: prefix, resolve archive"]

    EXTRACT --> PARSE["indexSourceContent"]
    WALK --> PARSE
    READ --> PARSE
    PARSE --> SLICE["SemanticdbFileSlice<br/>(definitions only, no SemanticDB protobuf)"]
```

Dependency sources have no SemanticDB protobuf data, so definitions are extracted by
real parsers instead:

- **Scala** — `ScalaSourceParser` (scalameta): parses with the `Scala3` dialect first,
  falls back to `Scala213`. Extracts classes, traits, objects, enums (+ cases), givens,
  defs, vals, vars, type members, tracking package nesting and owner chains.
- **Java** — `JavaSourceParser` (javaparser).

Symbols are **synthesized strings without SemanticDB markers**, owner-qualified:
`scala/Predef.println` for a method, `scala/Array` for a top-level class. Each
definition is indexed under two keys (`DependencySourceIndexing.indexSourceContent`):

- `symbol` — package-qualified, e.g. `scala/Predef.println`
- `ownerName` — owner + name without package, e.g. `Predef.println`

Parsed slices are cached **in memory per dependency-URI set** (`depSliceCache`), so
re-indexing after compiles reuses dependency slices. A parse summary
(`ScalaSourceParser summary: ok/total parsed (...)`) is logged after each refresh.

Extracted definitions provide go-to-definition and outline view for library symbols.

## Symbol Lookup

```diagram:mermaid
flowchart TD
    REQ["definition(uri, position)"] --> NORM["normalize uri"]
    NORM --> OCCUR["slicesForUri → find<br/>owned occurrences at position"]
    OCCUR --> SYMBOL["symbolAt: smallest enclosing<br/>occurrence range wins"]
    SYMBOL --> KEYS["NavigationSymbolLookup<br/>candidateSymbolKeys(symbol)"]
    KEYS --> LOOKUP["firstDefinition:<br/>workspace slices first,<br/>then dependency slices"]
    LOOKUP --> DONE["return first Location found"]
```

`symbolAt(position)` picks the **smallest enclosing occurrence** (by line span, then
character span) so nested symbols resolve precisely.

`candidateSymbolKeys` bridges the marker mismatch: SemanticDB occurrence symbols carry
markers (`scala/Predef.println().`), dependency keys don't (`scala/Predef.println`).
It strips markers (`()`, trailing `.` / `#`), drops the package prefix, and generates
progressive owner-qualified suffixes:

```
Input: "com/example/Foo.bar()."
Strip markers: "com/example/Foo.bar"
After last '/': "Foo.bar"
Segments: ["Foo", "bar"]
Suffixes: ["Foo.bar"]
```

**Suffixes require at least 2 segments (owner + name)** — bare names like `bar` are
deliberately excluded to prevent cross-library collisions. Local symbols (`localN`)
are scoped to the **current file only** for both definition and references.

## References

References union all definition and reference locations for the symbol (plus candidate
keys), deduplicate, and filter for existence on disk. Local symbols search only the
current file's slices; global symbols search all workspace + dependency slices:

```scala
def references(uri: String, position: Position): List[Location] = synchronized {
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    symbols.flatMap { symbol =>
      if isLocalSymbol(symbol) then
        // current file slices only
      else
        val candidateKeys = candidateSymbolKeys(symbol)
        val defs = allDefinitions(symbol) ++ candidateKeys.flatMap(allDefinitions(_))
        val refs = allReferences(symbol) ++ candidateKeys.flatMap(allReferences(_))
        NavigationLocationUtils.postProcessLocations((defs ++ refs).distinct)
    }.distinct
}
```

## Known Limitations

- **Capture checking (`^`)** — scalameta's `Scala3` dialect cannot parse Scala 3.8
  capture-checking syntax, so affected stdlib files (e.g. `Array.scala`, `Int.scala`
  from `scala-library-3.8.x`) are skipped entirely. Tracked in
  `plans/06-fix-gotodef-default-imports.md` (fix: use the `Scala3Future` dialect).
- **Top-level dependency types** (`Array`, `Unit`, `Option`, …) don't resolve to
  dependency sources: their candidate keys are empty (single-segment names are
  filtered) and the exact key keeps the `#` marker. Also tracked in
  `plans/06-fix-gotodef-default-imports.md`.
- **JDK sources** are only indexed if the BSP server ships them via
  `buildTargetDependencySources`; there is no JDK fallback indexing.
