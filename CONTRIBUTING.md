# Contributing

## Dev Setup

Requires **JDK 21+**, Scala 3.7.4.

```bash
# Clone the extension repo alongside basamake
git clone https://github.com/sake92/basamake-vscode.git ../basamake-vscode

# Build fat JAR
deder exec -t assembly -m modules-main

# Launch VS Code with the dev extension
code --disable-extension scalameta.metals --extensionDevelopmentPath="$(pwd)/../basamake-vscode" .
```

Set the extension's `basamake.jarPath` setting to the absolute path of `.deder/out/modules-main/assembly/out.jar`.

Then `Ctrl+Shift+P` → **Developer: Reload Window** in VS Code after each rebuild.

When making changes: rebuild the JAR -> reload window.

## Build & Test

```bash
# Compile
deder exec

# Run tests
deder exec -t test

# Clean build state
deder clean && deder exec
```

## Stale Process Cleanup

If necessary, between relaunches, kill leftover processes:

```bash
pkill -9 -f "deder bsp"; pkill -9 -f "basamake.*jar"; sleep 1
```

## stdout Is Sacred

LSP transport rides on stdout. No other output may touch it.

| Guard | Where |
|-------|-------|
| File-only logging (no console appender) | `LoggingUtils.configureFileLogging()` |
| Auto-flush stdout wrapper | `Main.run()` — `PrintStream(System.out, true, "UTF-8")` |

Verify with: `strace -e write -f java -jar ...` — fd 1 must have only JSON-RPC.
