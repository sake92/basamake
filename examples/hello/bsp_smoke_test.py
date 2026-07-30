#!/usr/bin/env python3
"""BSP v2 smoke test. Drives LSP through JSON-RPC and verifies:
  - initialize returns capabilities
  - didOpen/didSave doesn't crash (lazy BSP spawn on background threads)
  - didClose + shutdown + exit cleanly
"""
import subprocess, json, sys, time, os, select

JAR = os.path.expanduser("~") + "/projects/sake92/basamake/.worktrees/bsp-v2/.deder/out/modules-main/assembly/out.jar"
WORKSPACE = os.path.expanduser("~") + "/projects/sake92/basamake/.worktrees/bsp-v2/examples/hello"

def rpc_msg(msg):
    body = json.dumps(msg)
    header = f"Content-Length: {len(body.encode('utf-8'))}\r\n\r\n"
    return header + body

def recv_msg(proc, timeout_s=10):
    buf = b""
    deadline = time.time() + timeout_s
    content_length = None
    while time.time() < deadline:
        ready, _, _ = select.select([proc.stdout], [], [], 0.5)
        if not ready: continue
        chunk = proc.stdout.read1(4096)
        if not chunk: return None
        buf += chunk
        while b"\r\n\r\n" in buf:
            header, _, rest = buf.partition(b"\r\n\r\n")
            for line in header.split(b"\r\n"):
                if line.lower().startswith(b"content-length:"):
                    content_length = int(line.split(b":")[1].strip())
            if content_length and len(rest) >= content_length:
                body = rest[:content_length]
                buf = rest[content_length:]
                return json.loads(body.decode("utf-8"))
            else:
                break
    return None

def main():
    proc = subprocess.Popen(
        ["java", "-jar", JAR, "--workspace", WORKSPACE],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    try:
        # initialize
        proc.stdin.write(rpc_msg({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"processId": os.getpid(), "rootUri": "file://" + WORKSPACE, "capabilities": {}}
        }).encode()); proc.stdin.flush()
        init = recv_msg(proc, 10)
        assert init and "result" in init, f"initialize must respond, got {init}"
        caps = init["result"]["capabilities"]
        assert caps["definitionProvider"] is True, f"definitionProvider missing: {caps}"
        print("initialize: OK")

        # didOpen a source file — triggers lazy BSP spawn on background thread
        sbt_src = "file://" + WORKSPACE + "/sbt/src/main/scala/Main.scala"
        proc.stdin.write(rpc_msg({
            "jsonrpc": "2.0", "method": "textDocument/didOpen",
            "params": {"textDocument": {"uri": sbt_src, "languageId": "scala", "version": 1,
                "text": "object Main {}"}}
        }).encode()); proc.stdin.flush()
        time.sleep(2)
        print("didOpen: OK")

        # didSave — triggers compile (fire-and-forget)
        proc.stdin.write(rpc_msg({
            "jsonrpc": "2.0", "method": "textDocument/didSave",
            "params": {"textDocument": {"uri": sbt_src}, "text": "object Main {}"}
        }).encode()); proc.stdin.flush()
        time.sleep(2)
        print("didSave: OK")

        # goto-definition (should return something even without BSP compile)
        proc.stdin.write(rpc_msg({
            "jsonrpc": "2.0", "id": 2, "method": "textDocument/definition",
            "params": {"textDocument": {"uri": sbt_src}, "position": {"line": 0, "character": 7}}
        }).encode()); proc.stdin.flush()
        resp = recv_msg(proc, 10)
        assert resp and "result" in resp, f"definition request failed: {resp}"
        print("definition: OK")

        # didClose
        proc.stdin.write(rpc_msg({
            "jsonrpc": "2.0", "method": "textDocument/didClose",
            "params": {"textDocument": {"uri": sbt_src}}
        }).encode()); proc.stdin.flush()
        time.sleep(0.5)
        print("didClose: OK")

        # shutdown + exit
        proc.stdin.write(rpc_msg({"jsonrpc": "2.0", "id": 3, "method": "shutdown"}).encode()); proc.stdin.flush()
        time.sleep(1)
        proc.stdin.write(rpc_msg({"jsonrpc": "2.0", "method": "exit"}).encode()); proc.stdin.flush()
        proc.wait(timeout=10)
        print("shutdown+exit: OK (process exited cleanly)")
        print("\nBSP v2 smoke test: PASS")

    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait()

if __name__ == "__main__":
    main()
