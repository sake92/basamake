---
title: How it works
description: The main mechanisms of Basamake — LSP to BSP bridging, lazy connections, routing, diagnostics, and shutdown
---

# How it works

Basamake sits between your editor and your build tool(s):

```diagram:mermaid
flowchart LR
    E["Editor (VS Code)"] <-->|LSP, JSON-RPC over stdio| B["Basamake"]
    B <-->|BSP, JSON-RPC| S1["sbt server"]
    B <-->|BSP, JSON-RPC| S2["Mill server"]
    B <-->|BSP, JSON-RPC| S3["scala-cli server"]
```

## BSP discovery

At startup Basamake scans the workspace (up to 10 levels deep) for `.bsp/` directories and
reads every `*.json` config inside. Each config describes one build server: its name,
launch command, and working directory.

No build server is started yet — discovery is read-only.

## Lazy connections

BSP processes are **not** started when the editor opens. A connection spawns only when the
first editor message targets a file in its territory (opening a file, saving, go to definition, ...).
If the server is busy spawning, requests are queued and drained once it is up.

A failed spawn is not fatal: every user action is a fresh attempt, and a crashed server is
simply restarted on the next request. No orphan processes are left behind on shutdown.

## Routing

When you open a file, Basamake finds which build server owns it. The build tools themselves
report which source roots they own (`buildTargetSources`) — that's the ground truth.
Until those arrive (right after a server connects), a bootstrap heuristic based on
directory boundaries routes requests.

## Diagnostics

Build tools publish compile errors and warnings (`PublishDiagnostics`), which Basamake
forwards to the editor. Compiles are debounced, so typing doesn't spam your build tool.

## File watching

Changes to `.bsp/*.json` files (e.g. after `sbt bspConfig`) and to `.basamake/config.json`
are picked up automatically: connections are added, removed, or reconfigured live.

## Shutdown

When the editor closes, Basamake shuts down all BSP connections and waits for them to exit,
so no build servers outlive the editor session.
