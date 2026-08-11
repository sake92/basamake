---
title: Navigation & indexing
description: How Basamake resolves go to definition and find references — SemanticDB, source parsing, and the dependency/JDK index cache
---

# Navigation & indexing

Basamake supports **go to definition** and **find references** for Scala and Java.
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

## Not supported (yet)

- completion, hover, rename, formatting, workspace symbols
- `documentSymbol` (outline) is registered but returns nothing in v1
