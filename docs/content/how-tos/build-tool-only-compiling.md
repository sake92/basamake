---
title: Use the build tool only for compiling
description: Let Basamake navigate while your build tool controls compilation
---

# Use the build tool only for compiling

Use this lightweight mode when another process, such as sbt or an agent, owns
compilation. It keeps navigation and BSP target metadata active while
preventing Basamake from triggering builds, avoiding compile loops.

Choose **Don't compile on change** when Basamake asks how to import the build.
To change the mode after importing, set `autoCompile` to `false` for the connection
in `.basamake/config.json`:

```json
{
  "bspOverrides": [
    {
      "bspFile": ".bsp/sbt.json",
      "enabled": true,
      "autoCompile": false
    }
  ]
}
```

The process that owns compilation must generate SemanticDB for navigation to
follow its updates. Diagnostics published by the BSP server are still
forwarded. See the [configuration reference](/reference/configuration.html) for
the complete `autoCompile` behavior.
