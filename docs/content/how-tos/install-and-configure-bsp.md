---
title: Install Basamake and configure BSP
description: Install the VS Code extension and connect Basamake to deder, sbt, Scala CLI, or Mill
---

# Install Basamake and configure BSP

This guide shows how to install Basamake and connect an existing project to its
build tool.

## Install the VS Code extension

Download the latest VSIX from the
[Basamake VS Code releases page](https://github.com/sake92/basamake-vscode/releases),
then choose **Extensions → ... → Install from VSIX** in VS Code.

The [snapshot release](https://github.com/sake92/basamake-vscode/releases#release-main)
contains the latest changes.

If Metals is installed, select **Basamake** when VS Code asks which language
server to use for `.scala` and `.sbt` files.

## Auto install BSP config

When you open a source file in a Deder, sbt, or Mill project that has no BSP
configuration, Basamake asks whether it should run that build tool's BSP install
command (`deder bsp install`, `sbt bspConfig`, or
`mill mill.bsp.BSP/install`). Choose **Install** to create the configuration and
connect it automatically.

Choose **Do not offer again** to disable the prompt for that build marker. The
choice is stored in `offerInstallBlacklist`; remove the marker from that list to
make Basamake ask again.

## Manually install BSP config

Run the command for the project's build tool from the project directory:

| Build tool | Command |
|---|---|
| sbt | `sbt bspConfig` |
| Scala CLI | `scala setup-ide .` |
| Mill | `mill mill.bsp.BSP/install` |
| deder | `deder bsp install` |

## Check logs

Runtime logs are written to `.basamake/logs/basamake.log` in the project root.
