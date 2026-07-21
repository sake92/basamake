import subprocess, json, sys, time, threading, os

JAR = os.path.expanduser("~") + "/projects/sake92/basamake/.deder/out/core/assembly/out.jar"
WORKSPACE = os.path.expanduser("~") + "/projects/sake92/basamake/examples/hello/sbt"

def rpc_msg(msg):
    body = json.dumps(msg)
    header = f"Content-Length: {len(body.encode('utf-8'))}\r\n\r\n"
    return header + body

def recv_msg(proc, timeout_s=10):
    import select
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
                    msg = json.loads(body[:content_length].decode())
                    # Skip notifications (no id field), return only responses
                    if "id" in msg:
                        return msg
                    # Reset and continue waiting for next message
                    buf = body[content_length:]
                    content_length = None
    return None

proc = subprocess.Popen(
    ["java", "-jar", JAR, "--workspace", WORKSPACE],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
)

def read_stderr():
    for line in proc.stderr:
        sys.stderr.write(line.decode())

threading.Thread(target=read_stderr, daemon=True).start()

try:
    # 1. Init
    proc.stdin.write(rpc_msg({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "processId": None,
            "rootUri": f"file://{WORKSPACE}",
            "capabilities": {}
        }
    }).encode())
    proc.stdin.flush()
    resp = recv_msg(proc)
    print("INITIALIZE RESP:", resp)

    proc.stdin.write(rpc_msg({"jsonrpc": "2.0", "method": "initialized", "params": {}}).encode())
    proc.stdin.flush()

    main_uri = f"file://{WORKSPACE}/src/main/scala/Main.scala"
    with open(f"{WORKSPACE}/src/main/scala/Main.scala") as f:
        text = f.read()

    proc.stdin.write(rpc_msg({
        "jsonrpc": "2.0",
        "method": "textDocument/didOpen",
        "params": {
            "textDocument": {
                "uri": main_uri,
                "languageId": "scala",
                "version": 1,
                "text": text
            }
        }
    }).encode())
    proc.stdin.flush()

    print("Waiting 45s for compile and navigation index refresh...")
    time.sleep(45)

    # Query definition on write (SemanticDB line 7, col 3)
    print("--- Querying definition for 'write' (Line 7, col 3) ---")
    proc.stdin.write(rpc_msg({
        "jsonrpc": "2.0",
        "id": 2,
        "method": "textDocument/definition",
        "params": {
            "textDocument": {"uri": main_uri},
            "position": {"line": 7, "character": 3}
        }
    }).encode())
    proc.stdin.flush()
    resp = recv_msg(proc, 15)
    print("DEF write:", json.dumps(resp, indent=2))

    # Query definition on utils (SemanticDB line 10, col 3)
    print("--- Querying definition for 'utils' (Line 10, col 3) ---")
    proc.stdin.write(rpc_msg({
        "jsonrpc": "2.0",
        "id": 3,
        "method": "textDocument/definition",
        "params": {
            "textDocument": {"uri": main_uri},
            "position": {"line": 10, "character": 3}
        }
    }).encode())
    proc.stdin.flush()
    resp = recv_msg(proc, 15)
    print("DEF utils:", json.dumps(resp, indent=2))

    # Query definition on getMsg (SemanticDB line 10, col 10)
    print("--- Querying definition for 'getMsg' (Line 10, col 10) ---")
    proc.stdin.write(rpc_msg({
        "jsonrpc": "2.0",
        "id": 4,
        "method": "textDocument/definition",
        "params": {
            "textDocument": {"uri": main_uri},
            "position": {"line": 10, "character": 10}
        }
    }).encode())
    proc.stdin.flush()
    resp = recv_msg(proc, 15)
    print("DEF getMsg:", json.dumps(resp, indent=2))

    # msg has no SemanticDB reference occurrence (argument in method call)
    print("--- msg: KNOWN LIMITATION — Scala 3 SemanticDB omits reference occurrences for method arguments ---")

finally:
    proc.stdin.write(rpc_msg({"jsonrpc": "2.0", "id": 99, "method": "shutdown", "params": None}).encode())
    proc.stdin.write(rpc_msg({"jsonrpc": "2.0", "method": "exit", "params": None}).encode())
    proc.stdin.flush()
    try:
        proc.wait(timeout=3)
    except:
        proc.kill()
