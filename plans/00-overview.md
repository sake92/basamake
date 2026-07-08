# Scala LSP — Overview & Architecture

A from-scratch Scala language server, built to be a Metals alternative. Delivered in
layered milestones so each stage is independently useful and shippable.

## Guiding principles

- **BSP for what BSP is good at.** BSP gives diagnostics, build targets, classpath, and
  scalac options — and nothing else. Navigation comes from SemanticDB; hover/completion
  come from the presentation compiler. Do not expect BSP to provide those.
- **Robustness is the headline feature.** The reconnect / retry / reload story must be
  demonstrably better than Metals. That means an explicit connection state machine, not an
  implicit chain of futures.
- **No futures past the library boundary.** lsp4j and bsp4j hand us `CompletableFuture` at
  the edges. Quarantine it: block on `.get()` from virtual threads (cheap), keep all real
  work in straight-line sequential code, and coordinate subsystems with message queues.
- **Single-thread-owns-state.** Each subsystem (a connection, the index) is owned by one
  virtual thread processing a queue. No shared locks. This is the actor model, minimal and
  by hand.
- **Multi-BSP is first-class**, not bolted on. Everything per-connection is an *instance*,
  coordinated by a manager, never a global singleton.

## Runtime / library choices

- **JDK 24+** — required. JEP 491 (JDK 24) removed virtual-thread pinning on `synchronized`,
  which matters because lsp4j/bsp4j/GSON contain `synchronized` we do not control. On older
  JDKs a VT blocking inside library `synchronized` pins its carrier and can deadlock under
  load. Pin the toolchain to 24+.
- **Scala 3.**
- **Deder** build tool (https://github.com/sake92/deder)
- **lsp4j** (`org.eclipse.lsp4j`) — editor-facing LSP server.
- **bsp4j** (`ch.epfl.scala:bsp4j`) — build-server-facing BSP client. Note: bsp4j is
  generated Java; constructor arg order shifts between versions — verify against the exact
  version pulled.
- **ox** — structured concurrency. Supervised scopes for per-connection lifecycles;
  scope-scoped cooperative cancellation; channel/flow ops for the debounce pipeline.
- **os-lib** (Li Haoyi) — file watching. Chosen over Java `WatchService`, which is flaky /
  high-latency on macOS (where our users are heavy).
- **scalameta** — SemanticDB readers (Milestone 2). Do NOT hand-roll the protobuf.
- **scala3-presentation-compiler** — embedded as a library (Milestone 3). Do NOT reimplement.

## Concurrency model (the substrate everything sits on)

- Every LSP handler called by lsp4j immediately drops a message on a queue and returns a
  pre-completed future (a dumb ack). Real work happens on the owning subsystem's VT.
- One VT per subsystem loop (connection, index). One VT per in-flight request is fine too.
  **Never pool or cap virtual threads** — that's a platform-thread habit that fights their
  purpose.
- In our own code, never hold a lock across a blocking `.get()`. Use `ReentrantLock` over
  `synchronized` if a lock is ever needed. By construction (single-thread-owns-state) we
  mostly avoid locks entirely; the residual pinning risk is in libraries and is neutralized
  by JDK 24+.

## Top-level component shape

```
LSP server (owns editor protocol, one thread)
   |
BuildServerManager (owns routing table + N connections + file watcher)
   |
   +-- per-connection DURABLE record (survives crashes/reloads):
   |      spec, attempt counter, last-known diagnostics, current state
   |
   +-- per-connection EPHEMERAL scope (destroyed/recreated on transition):
   |      process, reader fork, debounce fork, compile forks, PC instance
   |
Workspace SemanticDB index (partitioned per build server)
```

The critical split: the **manager owns the durable half**, the **connection scope owns the
ephemeral half**. State transitions destroy and rebuild the ephemeral half while the durable
half persists. Get this split right and reconnect/reload/backoff all reduce to "manipulate
the durable record, then rebuild the scope."

## Milestones (each is a separate plan file)

1. `01-milestone-diagnostics.md` — BSP connection + diagnostics forwarding. The proxy.
   Includes the connection state machine, debounce, and the reconnect/reload story.
2. `02-milestone-multibsp.md` — BuildServerManager, routing table, file watcher,
   attach/detach/reload. (Design this alongside M1; it dictates M1's per-connection shape.)
3. `03-milestone-navigation.md` — SemanticDB indexing: go-to-def, find-refs, doc symbols.
4. `04-milestone-hover-completion.md` — presentation compiler: hover, completion, sig help.
   Optional / "if easy enough."
5. `05-config-and-persistence.md` — config file (enable/disable, build-tool allowlist,
   per-connection overrides) and the JSON build-digest cache.

## What we are explicitly NOT doing (v1)

- No H2/embedded SQL DB. Persist only build digests + user choices, as a plain JSON file.
- No presentation compiler until M4 — and its absence only costs hover/completion, nothing
  else.
- No debug adapter (DAP), code lenses, test discovery, worksheets, Bloop-install fallback.
  These are the long tail that made Metals a multi-year effort; out of scope for now.

## Sequencing advice

Build M1 and the M2 skeleton together — if M1 assumes a single global connection, M2 forces
a rewrite. Prove the riskiest unknowns early: for M1 that's the reconnect state machine; for
M4 (if attempted) it's getting a single completion to fire against the presentation compiler
for one hardcoded file, done FIRST not last.
