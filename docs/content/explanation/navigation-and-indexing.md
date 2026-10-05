---
title: Navigation & indexing
description: How Basamake resolves go to definition and find references — SemanticDB, source parsing, and the dependency/JDK index cache
---

# Navigation & indexing

Basamake supports **go to definition** and **find references** for Scala and Java.
sbt build definitions (`.sbt` files) are indexed too — vals and defs in
`build.sbt`/`project/*.sbt` support go to definition, references, and hover.
This page explains how they are resolved.

## Workspace index

When a workspace is opened, Basamake builds an index of all definitions and references:

1. **SemanticDB first** — if your build tool produces `.semanticdb` files
   (the compiler's structured metadata format), they are used: most accurate, no re-parsing.
2. **Source parsing fallback** — otherwise sources are parsed directly in two passes:
   first definitions are extracted, then references are resolved.
   Scala is parsed with [scalameta](https://scalameta.org/), Java with [JavaParser](https://github.com/javaparser/javaparser).

Files that don't match your `.gitignore` are skipped, so huge build outputs stay out of the index.
Directories containing their own `.git` (nested git repositories) are treated as separate
workspaces and never indexed — open them directly to work on them.
While the index is being built, progress is reported to the editor
(so it doesn't look frozen on big projects).

## Dependency and JDK sources

Definitions inside dependency jars and the JDK resolve too. Sources are downloaded once,
then **cached on disk** in an LMDB database (`~/.cache/basamake/deps`, XDG-compliant).
Indexing happens lazily in the background with priorities: the JDK first, then
`scala-library`/`scala3-library`, then everything else — so the sources you need most
are ready first.

The cache survives restarts: the second time you open the same project, navigation into
dependencies is instant, with no re-indexing.

## Hover and completion

For a Scala file owned by a BSP build target, Basamake starts that target's Scala
presentation compiler on demand. It uses the target's Scala version, classpath, compiler
options, and your current unsaved editor buffer.

- **Hover** shows the inferred type or symbol signature, plus Scaladoc or Javadoc when the
  compiler can obtain it.
- **Completion** suggests names and members valid at the cursor.

The presentation compiler is separate from the workspace index. If it is unavailable,
navigation and references still use the index as usual.

### Availability

Hover and completion need a working BSP target and a presentation-compiler artifact for its
exact Scala version. Scala 2 uses the version-matched `mtags` compiler; Scala 3 uses the
presentation compiler published with that Scala version. They can be unavailable for an old,
unpublished, or otherwise unsupported compiler version, or while its artifact cannot be
downloaded. In those cases Basamake returns no compiler result rather than substituting a
newer compiler, which could typecheck the target incorrectly.

## Not supported (yet)

- rename, formatting, workspace symbols
- `documentSymbol` (outline) is registered but returns nothing in v1
