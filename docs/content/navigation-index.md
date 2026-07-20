---
title: Navigation Index
description: SemanticDB-based go-to-definition, references, and document symbols with dependency source fallback
---

# Navigation Index

**Files:** `SemanticdbNavigationIndex.scala`, `DependencySourceParsing.scala`, `NavigationSymbolLookup.scala`, `NavigationLocationUtils.scala`, `NavigationRangeUtils.scala`, `NavigationUriUtils.scala`

## How Indexing Works

```diagram:mermaid
flowchart TD
    subgraph TRIGGER["Refresh Trigger"]
        ROUTING["onRoutingReady<br/>(after BSP handshake)"]
        COMPILE["onCompileSuccess<br/>(after compile)"]
    end

    TRIGGER --> REFRESH["SemanticdbNavigationIndex.refresh()"]

    REFRESH --> SRCROOTS["sourceRootsByTarget<br/>(from buildTargetSources)"]
    REFRESH --> DEPSRCS["dependencySourceUrisByTarget<br/>(from buildTargetDependencySources)"]
    REFRESH --> SCALAC["fetchScalacOptions()<br/>→ -Xsemanticdb flags?"]
    REFRESH --> OUTPUTS["fetchOutputRoots()<br/>→ buildTargetOutputPaths"]

    SCALAC --> CANDIDATE["candidateSemanticdbRoots<br/>(semanticdb output dirs)"]
    OUTPUTS --> CANDIDATE

    CANDIDATE --> WORKSPACE["indexWorkspaceTarget<br/>(walk .semanticdb files)"]
    DEPSRCS --> DEPS["indexDependencySources<br/>(extract from JARs/ZIPs)"]

    WORKSPACE --> MERGE["TargetState:<br/>workspaceSlicesByTarget +<br/>dependencySlicesByTarget"]
    DEPS --> MERGE
```

## Workspace Indexing

1. Find SemanticDB output directories via:
   - `buildTargetOutputPaths` — returns output directories
   - `buildTargetScalacOptions` — returns `-d` class directory
   - Combine both to get candidate roots
2. Walk each root for `*.semanticdb` files
3. Parse each with `scala.meta.internal.semanticdb.TextDocuments.parseFrom` (protobuf)
4. Resolve source URIs by traversing `META-INF/semanticdb` path, trying each candidate source root
5. Build `SemanticdbFileSlice` per source file containing:
   - Occurrences (symbol + range + definition flag)
   - Symbol definitions map (symbol → List[Location])
   - Symbol references map (symbol → List[Location])
   - Document symbols (for outline view)

## Dependency Source Indexing

For library dependencies (JARs/ZIPs from `buildTargetDependencySources`):

```diagram:mermaid
flowchart TD
    URI["jar:file:///.../foo.jar!/some/Source.scala"] --> RESOLVE["resolveSourcePath"]
    RESOLVE --> IS_DIR{Path is dir?}
    IS_DIR -->|Yes| WALK["os.walk, filter .scala/.java"]
    RESOLVE --> IS_JAR{Path is JAR/ZIP?}
    IS_JAR -->|Yes| EXTRACT["readArchiveEntries<br/>→ cache in .basamake/dependency-sources/"]
    RESOLVE --> IS_FILE{Path is .scala/.java?}
    IS_FILE -->|Yes| READ["readText"]
    RESOLVE --> HAS_BANG{URI contains ! ?}
    HAS_BANG -->|Yes| NESTED["strip jar: prefix, resolve archive"]
    
    EXTRACT --> PARSE["indexSourceContent<br/>→ regex extractDefinitions"]
    WALK --> PARSE
    READ --> PARSE
    PARSE --> SLICE["SemanticdbFileSlice<br/>(definitions only, no SemanticDB protobuf)"]
```

Dependency sources have no SemanticDB protobuf data, so they fall back to regex parsing:

```scala
private val DefinitionPattern =
  """\b(object|class|trait|enum|def|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)""".r
```

Extracted definitions provide go-to-definition and outline view for library symbols.

## Symbol Lookup

```diagram:mermaid
flowchart TD
    REQ["definition(uri, position)"] --> NORM["normalize uri"]
    NORM --> OCCUR["slicesForUri → find<br/>owned occurrences at position"]
    OCCUR --> SYMBOL["extract symbol string"]
    SYMBOL --> KEYS["NavigationSymbolLookup<br/>candidateSymbolKeys(symbol)"]
    KEYS --> LOOKUP["firstDefinition:<br/>workspace slices first,<br/>then dependency slices"]
    LOOKUP --> DONE["return first Location found"]
```

`candidateSymbolKeys` generates progressive symbol suffixes:

```
Input: "com/example/Foo.bar()."
Strip: "com/example/Foo.bar"
After last '/': "Foo.bar"
Segments: ["Foo", "bar"]
Suffixes: ["Foo.bar", "bar"]
```

This handles cases where the SemanticDB symbol in the source file differs from the symbol in the dependency by package nesting.

## References

References union all definition and reference locations for the symbol (plus candidate keys), deduplicate, and filter for existence on disk:

```scala
def references(uri: String, position: Position): List[Location] = synchronized {
    val symbols = slicesForUri(normalized).flatMap(_.symbolAt(position)).distinct
    symbols.flatMap { symbol =>
      val defs = allDefinitions.getOrElse(symbol, Nil) ++ candidateKeys.flatMap(k => allDefinitions.get(k))
      val refs = allReferences.getOrElse(symbol, Nil) ++ candidateKeys.flatMap(k => allReferences.get(k))
      NavigationLocationUtils.postProcessLocations((defs ++ refs).distinct)
    }.distinct
}
```
