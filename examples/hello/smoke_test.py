#!/usr/bin/env python3
"""Smoke test for Basamake LSP server. Sends JSON-RPC messages via stdio."""

import subprocess, json, sys, time, threading, os

JAR = os.path.expanduser("~") + "/projects/sake92/basamake/.deder/out/core/assembly/out.jar"
WORKSPACE = os.path.expanduser("~") + "/projects/sake92/basamake/examples/hello"

def rpc_msg(msg):
    """Encode a JSON-RPC message with Content-Length header."""
    body = json.dumps(msg)
    header = f"Content-Length: {len(body.encode('utf-8'))}\r\n\r\n"
    return header + body

def recv_msg(proc, timeout_s=10):
    """Read one LSP message from process stdout. Returns dict or None."""
    import select
    # Read all available data up to double CRLF
    buf = b""
    deadline = time.time() + timeout_s
    content_length = None
    
    while time.time() < deadline:
        ready, _, _ = select.select([proc.stdout], [], [], 0.5)
        if not ready:
            continue
        chunk = proc.stdout.read1(4096)
        if not chunk:
            return None
        buf += chunk
        # Check if we have headers and body
        if b"\r\n\r\n" in buf and content_length is None:
            header_end = buf.index(b"\r\n\r\n")
            header_text = buf[:header_end].decode()
            for line in header_text.split("\r\n"):
                if line.lower().startswith("content-length:"):
                    content_length = int(line.split(":")[1].strip())
            if content_length is not None:
                body_start = header_end + 4
                body = buf[body_start:]
                if len(body) >= content_length:
                    return json.loads(body[:content_length].decode())
    
    print(f"TIMEOUT reading LSP message. Buffer: {buf[:300]}")
    return None

# Start server
proc = subprocess.Popen(
    ["java", "-jar", JAR, "--workspace", WORKSPACE],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
)

# Stderr reader thread
def read_stderr():
    for line in proc.stderr:
        sys.stderr.write(line.decode())

threading.Thread(target=read_stderr, daemon=True).start()

try:
    # Step 1: Initialize
    init = rpc_msg({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "processId": None,
            "rootUri": f"file://{WORKSPACE}",
            "capabilities": {}
        }
    })
    proc.stdin.write(init.encode())
    proc.stdin.flush()

    resp = recv_msg(proc)
    print("INITIALIZE RESPONSE:", json.dumps(resp, indent=2))
    assert resp.get("id") == 1, "Initialize failed"
    caps = resp.get("result", {}).get("capabilities", {})
    assert caps.get("textDocumentSync") == 1, "Expected Full sync"
    print("  => Capabilities OK (Full sync)")

    # Step 2: Send initialized notification
    initd = rpc_msg({"jsonrpc": "2.0", "method": "initialized", "params": {}})
    proc.stdin.write(initd.encode())
    proc.stdin.flush()

    # Step 3: Touch the file to force Deder to recompile (otherwise it uses cached result)
    src_file = f"{WORKSPACE}/hello/src/hello/Main.scala"
    os.utime(src_file, None)

    with open(src_file) as f:
        text = f.read()

    did_open = rpc_msg({
        "jsonrpc": "2.0",
        "method": "textDocument/didOpen",
        "params": {
            "textDocument": {
                "uri": f"file://{src_file}",
                "languageId": "scala",
                "version": 1,
                "text": text
            }
        }
    })
    proc.stdin.write(did_open.encode())
    proc.stdin.flush()

    # Step 4: Wait for diagnostics (they come as notifications, not responses)
    print("Waiting for diagnostics (5s timeout)...")
    deadline = time.time() + 5
    diagnostics_received = False
    while time.time() < deadline:
        msg = recv_msg(proc)
        if msg is None:
            break
        if isinstance(msg, dict) and msg.get("method") == "textDocument/publishDiagnostics":
            diags = msg.get("params", {}).get("diagnostics", [])
            print(f"  => Received {len(diags)} diagnostics for {msg['params'].get('uri', 'unknown')}")
            diagnostics_received = True
            for d in diags:
                print(f"     - [{d['severity']}] Line {d['range']['start']['line']}: {d['message']}")
            break

    if diagnostics_received:
        print("\nSMOKE TEST PASSED")
    else:
        print("\nNo diagnostics received within timeout. Check stderr for errors.")
        # Let's see what else came
        print("  Additional messages:", recv_msg(proc))

finally:
    # Send proper LSP shutdown/exit so the server cleans up child processes
    proc.stdin.write(rpc_msg({"jsonrpc": "2.0", "id": 2, "method": "shutdown", "params": None}).encode())
    proc.stdin.write(rpc_msg({"jsonrpc": "2.0", "method": "exit", "params": None}).encode())
    proc.stdin.flush()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
