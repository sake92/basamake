# SemanticDB Reference

Spec: https://github.com/scalameta/scalameta/blob/main/docs/semanticdb/specification.md (~4k lines, v4 schema).

## Data Model

SemanticDB is a protobuf-based data model for semantic information about Scala/Java programs. Two main concerns: **producers** (compiler plugins) emit `.semanticdb` files, **consumers** (basamake) read them to power navigation.

### Top-Level: TextDocuments

```protobuf
message TextDocuments {
  repeated TextDocument documents = 1;
}
```

A `.semanticdb` file contains one `TextDocuments` with one or more `TextDocument` entries.

### TextDocument

```protobuf
message TextDocument {
  Schema schema = 1;                       // version: 4 = SEMANTICDB4
  string uri = 2;                          // relative source path, e.g. "src/main/scala/Main.scala"
  string text = 3;                         // source content (optional)
  string md5 = 11;                         // hex MD5 of source (optional)
  Language language = 10;                  // SCALA or JAVA
  repeated SymbolInformation symbols = 5;  // definition metadata
  repeated SymbolOccurrence occurrences = 6; // name resolution for every identifier
  repeated Diagnostic diagnostics = 7;     // compiler errors/warnings
  repeated Synthetic synthetics = 12;      // synthetic code (for-comprehensions, implicits)
}
```

`schema` must be `4` for v4 (current). Fields `text` and `md5` are optional — basamake uses neither.

### Symbol

String key linking references ↔ definitions. Two categories:

#### Global Symbols

Fully qualified within a SemanticDB universe. Format: `<owner_chain><descriptor>`.

**Owner chain**: `_root_/` → `_empty_/` → packages → enclosing definitions, concatenated.

**Descriptor** encodes the definition kind:

| Kind | Suffix | Example |
|------|--------|---------|
| PACKAGE | `/` | `scala/` |
| ROOT PACKAGE | `_root_/` | — |
| EMPTY PACKAGE | `_empty_/` | — |
| OBJECT | `.` | `scala/Predef.` |
| PACKAGE_OBJECT | `.` | `scala/package.` |
| VAL | `.` | `scala/package.Seq.` |
| METHOD (non-overloaded) | `.` | `scala/Predef.println.` |
| METHOD (overloaded) | `(<tag>).` | `scala/Predef.println(+1).` |
| MACRO | `(<tag>).` | same as method |
| CONSTRUCTOR | `` `<init>`(). `` | `_empty_/Main$package.`<init>`().` |
| CLASS | `#` | `scala/Int#` |
| TRAIT | `#` | `upickle/Api#` |
| TYPE | `#` | `scala/package.Seq#` |
| PARAMETER | `(name)` | `scala/Predef.implicitly().(e)` |
| TYPE_PARAMETER | `[name]` | `scala/Predef.implicitly().[T]` |
| SELF_PARAMETER | unsupported | — |

**Backtick wrapping**: names that aren't Java identifiers (`???`, `==`, `::`) are wrapped: `` scala/Predef.`???`(). ``.

**Owner rules**:
- Root package → no owner
- Top-level package → owner is root package
- Package object → owner is its associated package
- Class/trait/object member → owner is the enclosing definition
- Other global def → innermost enclosing definition

#### Local Symbols

Format: `local<N>(+<N>)?`. Unique within a single document only. Examples: `local0`, `local1`, `local2+1`. Local variables, parameters, and type parameters use these. Counter resets per file.

### SymbolOccurrence

```protobuf
message SymbolOccurrence {
  Range range = 1;
  string symbol = 2;
  Role role = 3;
}
```

Maps every identifier in source to its resolved symbol.

**Role**:
- `1` = `REFERENCE` — usage site (e.g. `y` in `val x = y`)
- `2` = `DEFINITION` — declaration site (e.g. `x` in `val x = y`)

A single source position can have multiple occurrences (e.g. `Seq` at a call site gets both a REFERENCE occurrence and a synthetic `apply` occurrence). The consumer must pick the best match (basamake picks smallest enclosing range).

### SymbolInformation

```protobuf
message SymbolInformation {
  string symbol = 1;                  // the symbol
  Language language = 2;              // SCALA/JAVA
  Kind kind = 3;                      // LOCAL, FIELD, METHOD, CONSTRUCTOR, MACRO, TYPE, PARAMETER, SELF_PARAMETER, TYPE_PARAMETER, OBJECT, PACKAGE, PACKAGE_OBJECT, CLASS, TRAIT, INTERFACE, ...
  int32 properties = 4;               // bitmask: ABSTRACT, FINAL, SEALED, IMPLICIT, LAZY, CASE, COVARIANT, CONTRAVARIANT, ...
  string display_name = 5;            // source-level name
  Signature signature = 10;           // type signature (optional)
  Access access = 17;                 // visibility (optional)
  string tpe = 7;                     // legacy type representation
  repeated Annotation annotations = 14; // annotations
  repeated string overridden_symbols = 15; // overridden symbols
  Scope members = 17;                 // child definitions (hardlinks or symlinks)
}
```

`SymbolInformation` provides metadata about definitions declared in the document. The `symbols` section contains one entry per top-level definition in the file; nested definitions are in the `members` Scope.

### Scope

```protobuf
message Scope {
  repeated string symlinks = 1;       // symbol references to members
  repeated SymbolInformation hardlinks = 2; // inline member metadata
}
```

Symlinks are preferred (smaller). Hardlinks used when a definition can't have a global symbol (e.g. structural types, existential types).

### Range & Location

```protobuf
message Range {
  int32 start_line = 1;         // 0-based
  int32 start_character = 2;    // 0-based
  int32 end_line = 3;           // 0-based
  int32 end_character = 4;      // 0-based
}

message Location {
  string uri = 1;
  Range range = 2;
}
```

Directly correspond to LSP `Range`/`Location`. Start inclusive, end exclusive. Lines and characters are 0-based (same as LSP).

## SUID Encoding (Implementation Detail)

The spec defines `SymbolOccurrence.symbol` as `string`. However, real Scala 3 compilers sometimes encode symbols as **SUIDs** (Symbol Unique Identifiers) — 4-byte binary hashes stored as protobuf bytes within a sub-message at field 2. The `SymbolInformation` table in the document provides the SUID→string mapping.

The scalameta `TextDocuments.parseFrom()` library resolves SUIDs transparently when the symbol is defined in the same document's `symbols` section. For cross-document references (e.g. references to types in other compilation units), SUID resolution depends on the consumer having access to the full set of documents.

**Consumer implications**: after parsing with scalameta, check `occ.symbol` — if empty/null for an occurrence that should be resolvable, the SUID couldn't be resolved from the available documents.

## TextDocument URI Resolution

`TextDocument.uri` is **relative** to the project source root. To get an absolute `file://` URI:

```
workspace-relative: "src/main/scala/Main.scala"
source-root:        /home/user/project
resolved-uri:       file:///home/user/project/src/main/scala/Main.scala
```

Basamake resolves via `SemanticdbIndexing.resolveSourceUri`, which combines `workspaceRoot`, the semanticdb file location, the document URI, and the known `sourceRoots` from BSP.

## SemanticDB File Locations

The Scala compiler writes `.semanticdb` files to the output directory. Paths determined by scalac options:

| Scala version | Flag |
|---------------|------|
| Scala 3 | `-semanticdb-target:<path>` |
| Scala 2 | `-P:semanticdb:targetroot:<path>` |

Without these flags, Scala 3 defaults to `META-INF/semanticdb/` under the first output directory (typically `target/scala-<version>/classes/META-INF/semanticdb/` or the BSP class directory).

Basamake discovers files by walking the target directory with `semanticdbFilesUnder` (recursive walk, `.semanticdb` extension).

## Scala 3 vs Scala 2 Differences

| Aspect | Scala 2 | Scala 3 |
|--------|---------|---------|
| Symbol length | Always fully qualified | May emit short symbols for simple dep refs |
| SUID usage | Rare | Common for stdlib/type refs |
| Top-level defs | Not supported | `X$package.` wrapper in symbol |
| Package objects | Standard | Also use `package` as object name |
| `-semanticdb-target` | `-P:semanticdb:targetroot:` | `-semanticdb-target:` |

## How basamake Consumes SemanticDB

### Workspace indexing (`SemanticdbIndexing.parseSemanticdbFile`)

1. `TextDocuments.parseFrom(bytes)` → scala.meta `TextDocuments`
2. `doc.occurrences` → `SemanticdbOccurrence(symbol, range, isDefinition)`
3. Group: `symbolDefinitions` = occurrences where `isDefinition=true`, grouped by `symbol`
4. Group: `symbolReferences` = all occurrences (defs + refs), grouped by `symbol`
5. Return `SemanticdbFileSlice(sourceUri, occurrences, symbolDefinitions, symbolReferences)`

### Dependency indexing (`DependencySourceIndexing.indexSourceContent`)

Parses dependency source files (Scala/Java) with `ScalaSourceParser`/`JavaSourceParser`, synthesizes `symbolDefinitions` from parsed definitions:
- `groupMap(_.symbol)` — fully qualified key
- `groupMap(_.ownerName)` — owner-qualified key (e.g. `"Predef.println"`)

### Lookup (`NavigationSymbolLookup`)

`firstDefinition(symbols, currentFileUri, workspaceSlices, dependencySlices)`:
- For each symbol, try workspace slices first, then dependency slices
- Match keys: raw symbol first, then `candidateSymbolKeys(symbol)` (strips markers, generates inits)

`candidateSymbolKeys` strips `.`/`#`/`(+N)` markers, splits by `/` for package prefix, by `.` for owner chain, generates `owner.name` inits.

## Key Descriptor Suffix Reference

| Symbol ends with | Kind |
|-----------------|------|
| `#` | CLASS, TRAIT, TYPE (type definition) |
| `.` | OBJECT, PACKAGE_OBJECT, VAL, METHOD, MACRO |
| `().` | METHOD with no overloads |
| `(+1).`, `(+2).` | METHOD overloaded, Nth occurrence |
| `/` | PACKAGE |
| `(name)` | PARAMETER |
| `[name]` | TYPE_PARAMETER |
| `` `<init>`(). `` | CONSTRUCTOR |

## Known Limitations (Spec)

- Global symbols not guaranteed unique across different build targets / Scala versions
- No spec for how Scala constructs map to SymbolInformation (e.g. `given` → ?)
- No spec for Java complete support (WIP)
- SUID resolution requires full document set
- Scala 3 occurrences have non-standard symbol formats (short symbols, SUID)
