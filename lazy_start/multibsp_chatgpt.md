# BSP Lifecycle & Routing Plan

## Goals

- Support multiple BSP configurations in one workspace.
- Support multiple workspace folders.
- Make BSP servers lazy, restartable, and resilient.
- Avoid fragile startup behavior.
- Keep the architecture simple and predictable.

---

# Discovery

On workspace load (and whenever the workspace changes):

1. Discover all `.bsp/*.json` files.
2. Record each parent directory as a **BSP root**.
3. Do **not** start any BSP processes during LSP startup.

The LSP owns discovery. BSPs are just resources that can be started on demand.

---

# Routing

For every request associated with a file:

1. Walk up the parent directories.
2. Find the nearest ancestor containing a `.bsp` directory.
3. Route the request to that BSP root.

Example:

```text
workspace/
├── build.sbt
├── .bsp/
│   └── sbt.json
└── scalacli/
    ├── myscript.scala
    └── .bsp/
        └── scala.json
```

Routing:

- `workspace/src/Main.scala`
  → workspace BSP

- `workspace/scalacli/myscript.scala`
  → Scala CLI BSP

This naturally supports nested projects and matches user expectations.

---

# BSP Lifecycle

Each BSP instance has a simple state machine.

```text
Stopped
   │
   ▼
Starting
   │
   ▼
Running
   │
   ▼
Failed
```

Behavior:

## Running

Send requests immediately.

## Starting

Queue requests until startup finishes.

## Stopped

Start the BSP.

Queue incoming requests.

## Failed

Restart the BSP.

Queue incoming requests.

---

# Request Queue

Interactive requests should wait until the BSP is ready.

Examples:

- hover
- completion
- definition
- references
- semantic tokens
- code actions

These should be queued.

---

High-frequency notifications should be coalesced instead.

Examples:

- didChange
- diagnostics scheduling
- background indexing

If the user types 100 characters while the BSP starts, only keep the latest document version.

---

# Startup Sequence

When a BSP finishes starting:

1. Perform BSP initialization.
2. Replay open documents.
3. Flush queued requests in FIFO order.

If startup fails:

- Mark the BSP as Failed.
- Fail queued requests with a "build server unavailable" error.

---

# Restart Strategy

Do **not** continuously ping BSPs.

Instead:

- Assume Running is healthy.
- Detect failures naturally when a request is sent.

Failure examples:

- broken pipe
- EOF
- timeout
- process exited

Recovery:

1. Mark BSP as Failed.
2. Restart it.
3. Replay open documents.
4. Retry the original request exactly once.

This makes laptop sleep and unexpected BSP exits mostly transparent.

---

# Multiple BSPs in One Root

Example:

```text
project/
└── .bsp/
    ├── sbt.json
    └── bloop.json
```

Initially:

- Start all BSPs for that root lazily when first needed.

After startup:

- Query `workspace/buildTargets`
- Query `buildTarget/sources`

Build an ownership cache:

```
File/Directory -> BSP Instance
```

Subsequent requests can route directly to the correct BSP.

---

# Ownership Cache

Maintain:

```
Nearest .bsp root
        ↓
Active BSP(s)
        ↓
Target ownership
        ↓
File -> BSP mapping
```

Refresh this mapping whenever:

- build targets change
- BSP reconnects
- workspace is reloaded

---

# Future Improvements

- Idle BSP eviction after configurable timeout.
- Background ownership refresh.
- Metrics for:
  - startup time
  - restart count
  - queued requests
  - retry count
- Smarter routing using build target ownership once known.

---

# Guiding Principles

- The LSP owns routing.
- BSPs are disposable.
- Never eagerly start everything.
- Route by the nearest `.bsp` ancestor.
- Start BSPs lazily.
- Queue requests while starting.
- Restart transparently after failures.
- Learn ownership dynamically for multiple BSPs in the same root.

This architecture should remain simple while being significantly more resilient than always-running BSP processes.