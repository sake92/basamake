# Architecture & Implementation Plan: Scala Multi-BSP LSP Multiplexer

## 1. System Objective
Build a robust, multiplexing Scala Language Server (LSP) that acts as a transparent router for multiple underlying Build Server Protocol (BSP) instances. The system must lazy-load BSPs, route file requests accurately based on workspace boundaries, handle overlapping target claims gracefully, and maintain stable background processes.

**Core Philosophy:** - Avoid monolithic state.
- Treat each BSP as an isolated, supervised state machine.
- Prefer deterministic behavior over fragile heuristics (e.g., no auto-shutdown timers).

---

## 2. Component Architecture

### 2.1 The Request Router (Middleware)
The central nervous system of the LSP. It intercepts all incoming LSP requests and routes them to the correct BSP instance.

- **Routing Table:** Maintains an in-memory mapping of `URI -> BSP Connection`.
- **Request Queuing:** If a request targets a BSP that is currently booting (`Starting` state), the router queues the request and flushes it only when the BSP reaches the `Running` state.
- **RPC Timeouts:** Implements strict timeouts on BSP RPC calls. If a BSP becomes unresponsive (e.g., laptop sleep, dead pipe), the router catches the timeout, prevents blocking the main LSP thread, and marks the BSP as `Dead`.

### 2.2 The BSP Supervisor (State Machine)
Manages the lifecycle of individual BSP subprocesses.
- **States:** `Idle` -> `Starting` -> `Running` -> `Dead`.
- **Lifecycle Rules:**
  - **Start:** Triggered *only* when a file mapped to the BSP is opened or edited.
  - **Stop:** Explicitly disabled. Once started, a BSP remains alive for the duration of the LSP session to avoid severe cold-start JVM penalties. 
  - **Crash Recovery:** If a BSP transitions to `Dead` (due to crash or timeout), the supervisor cleans up zombie processes and automatically transitions to `Starting` upon the next routed request.

---

## 3. Heuristics & Resolution Logic

### 3.1 Workspace Discovery (The `.bsp/` Heuristic)
When a file is opened (`textDocument/didOpen`) and its URI is not in the Routing Table:
1. Walk up the directory tree from the file's location.
2. Find the first parent directory containing a `.bsp/` folder.
3. Parse all `*.json` connection files within that `.bsp/` directory.

### 3.2 The "Same Folder" Conflict Resolution
When multiple BSP configs exist in the same `.bsp/` folder (e.g., `sbt.json` and `scala-cli.json`), the LSP must determine which BSP owns the file.

1. **Initial Boot:** Boot all discovered BSPs in that `.bsp/` folder simultaneously.
2. **Target Resolution:** Query `workspace/buildTargets` on each newly booted BSP.
3. **Source Mapping:** Map the base directories/roots that each BSP claims to update the Routing Table.
4. **Collision Handling ("First Wins"):** If two BSPs legitimately claim the exact same source file, the first one to resolve and respond claims ownership in the Routing Table.

---

## 4. User Configuration & Escape Hatches

Since workspace migrations (e.g., SBT to Mill) often result in messy, overlapping builds, algorithmic resolution isn't enough.

- **Manual Overrides:** Implement a workspace-level configuration (e.g., via `workspace/didChangeConfiguration` or a local `.lsp-config` file) allowing users to explicitly disable specific BSPs.
- **Behavior:** If `sbt.json` is in the blocklist, the LSP completely ignores it during the Workspace Discovery phase, never attempting to boot it.

---

## 5. Suggested Implementation Steps for AI Agent

1. **Scaffold the Router:** Implement the base LSP server shell and the asynchronous `URI -> BSP` router middleware.
2. **Implement the State Machine:** Build the BSP Supervisor to handle subprocess spawning, `stdin/stdout` pipe management, and state transitions (`Idle`, `Starting`, `Running`, `Dead`).
3. **File Discovery Logic:** Write the directory-walking utility to locate `.bsp/` folders and parse JSON configs.
4. **Target Resolution & Caching:** Implement the `workspace/buildTargets` handshake to populate the Routing Table dynamically on boot.
5. **Implement Queuing:** Add the request buffer logic to hold `textDocument/*` requests while a targeted BSP is in the `Starting` state.
