---
title: Basamake
description: Minimalistic Scala language server — fast and stable navigation and build diagnostics for everyday work
pagination:
  enabled: false
---

# Basamake

**Basamake** is a minimalistic Scala language server for everyday work.
It connects your editor to one or more [BSP](https://build-server-protocol.github.io/) build servers
and gives you the essentials:

- **Go to definition** and **find references** for Scala and Java code
- **Build diagnostics** (errors, warnings) reported by your build tool
- **Multiple build tools per workspace** (sbt, Mill, scala-cli, deder, ...) — all discovered and routed automatically
- **Cached indexes** for dependency jars and JDK sources — fast startup and snappy navigation

No completion, hover, rename, or formatting. Just the bare minimum to get work done — fast and stable.

> **New here?** Start with the [Installation](/install.html) guide,
> then read [How it works](/how-it-works.html).
> Already set up? See the [Configuration](/config.html) page.

## Why Basamake?

- **Snappy** — BSP servers start lazily (nothing spawns at editor startup), dependency indexes are cached on disk and reused across sessions
- **Stable** — small surface area and a simple concurrency model, no heavy machinery
- **Robust** — workspaces with several build tools just work; crashed build servers don't leave orphan processes behind
- See [Why Basamake?](/why-basamake.html) for the comparison with Metals and IntelliJ

## Features

- go to definition, including into dependency jars and JDK sources
- find references
- build diagnostics from BSP servers
- multiple BSP servers per workspace, lazily started
- indexing progress reported to the editor while the workspace index is built

## Site map

- [Installation](/install.html) — VS Code extension and BSP setup
- [How it works](/how-it-works.html) — the main mechanisms, in short
- [Navigation & indexing](/navigation.html) — how definitions and references are resolved
- [Configuration](/config.html) — `.basamake/config.json`
- [Why Basamake?](/why-basamake.html) — Basamake vs Metals and IntelliJ
