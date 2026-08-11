---
title: Installation
description: Installing the Basamake VS Code extension and setting up BSP connections for your build tools
---

# Installation

## VS Code extension

Install the [Basamake VS Code extension](https://github.com/sake92/basamake-vscode):
download the latest VSIX from the [releases page](https://github.com/sake92/basamake-vscode/releases)
and install it in VS Code (Extensions → `...` → Install from VSIX).
There is also a [snapshot release](https://github.com/sake92/basamake-vscode/releases#release-main)
if you want the latest changes.

If you also have **Metals** installed, VS Code will prompt you which language server to use
for `.scala`/`.sbt` files — select **Basamake**.

## BSP setup

> **You must install BSP manually — Basamake does not do it for you.**
> There is no "import build" step in Basamake: it only *reads* `.bsp/` configs,
> it never generates them. Run the setup command of your build tool once per machine, per project.

Basamake speaks the [Build Server Protocol](https://build-server-protocol.github.io/):
it talks to your build tool through a small config file in a `.bsp/` directory.
Most build tools can generate it for you:

- **deder**: `deder bsp install`
- **sbt**: `sbt bspConfig`
- **scala-cli**: `scala setup-ide .`
- **Mill**: `mill mill.bsp.BSP/install`

The generated `.bsp/*.json` files are usually gitignored (they are machine-specific),
so re-run the setup command when you switch machines or clone a project.

## Multiple build tools in one workspace

A workspace may contain several projects, each with its own build tool and `.bsp/` directory
(e.g. an sbt project in `app/` and a scala-cli script in `scripts/`).

Basamake discovers **all** `.bsp/` directories (up to 10 levels deep) and routes each editor
request to the right build server automatically — you don't have to configure anything.

## Logs

All logs go to `.basamake/logs/basamake.log` in your project root.
If something misbehaves, check that file first.
