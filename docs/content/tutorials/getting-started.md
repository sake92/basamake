---
title: Getting started
description: Install Basamake and use it in a Scala CLI project
---

# Getting started

In this tutorial, you will install Basamake, connect it to a Scala CLI project,
and try code navigation in VS Code.

You need VS Code, JDK 21 or newer, and Scala CLI.

## Install the extension

Download the latest VSIX from the
[Basamake VS Code releases page](https://github.com/sake92/basamake-vscode/releases).

In VS Code, open **Extensions**, select **...**, choose **Install from VSIX**, and
select the downloaded file.

## Create the BSP configuration

Open a terminal in your Scala CLI project and run:

```shell
scala setup-ide .
```

This creates a `.bsp` configuration that Basamake can discover.

## Open the project

Open the project directory in VS Code. If VS Code asks which language server to
use for Scala files, select **Basamake**.

Open a `.scala` file, then use **Go to Definition** on a local or library symbol.
Basamake starts the Scala CLI build server when it is first needed and opens the
symbol's definition.

You can now use go to definition, find references, hover, completion, and build
diagnostics. For another build tool, follow
[Install Basamake and configure BSP](/how-tos/install-and-configure-bsp.html).
