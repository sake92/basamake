---
title: Why Basamake?
description: How Basamake compares to Metals and IntelliJ — and what you give up
---

# Why Basamake?

Basamake is designed for **everyday work**: fast, stable, and predictable,
without the complexity of a full-featured IDE backend.

## What you get

| | Basamake | Metals | IntelliJ |
|---|---|---|---|
| Editor startup | instant — nothing is spawned or indexed up front | starts a build server and indexes on open | heavy project import and indexing |
| BSP setup | manual one-time command per tool (`sbt bspConfig`, ...) | similar, plus automatic "import build" | build-tool specific, slow import |
| Multiple build tools per workspace | first-class — discovered and routed automatically | partial | single build system per project |
| Dependency/JDK index | cached in LMDB, reused across sessions | re-indexed per project | cached, but tied to its own model |
| Feature surface | small — goto definition, references, diagnostics | very large | very large |

## Stability

- **Lazy everything** — build servers spawn only when needed; a crashed server is just restarted on the next request, no manual "restart the language server"
- **No orphan processes** — Basamake shuts down its BSP connections when the editor closes
- **Simple concurrency** — a small, auditable codebase instead of a sprawling one

## Trade-offs

You give up the fancy stuff: code completion, hover, rename refactoring, formatting,
and workspace-wide symbol search. For projects where you mainly navigate, read, and
iterate on compile errors, Basamake is faster to start, lighter on resources,
and more predictable than the alternatives.
