#!/usr/bin/env python3
"""A tiny MCP server for the M9 live acceptance — stdlib only, harmless tools.

    python3 tools/mcp-test-server.py            # stdio: the app spawns it
    python3 tools/mcp-test-server.py --http     # Streamable HTTP on 127.0.0.1:8765/mcp

It speaks the tool subset the client implements (`initialize`, `tools/list`, `tools/call`,
`ping`, `notifications/tools/list_changed`), so it exercises the real transport, the handshake,
the provider declarations, the approval gate and the audit — without a real integration.

The tools are deliberately boring:

    echo(text)         returns the text
    add(a, b)          arithmetic, to have a non-string argument
    save_note(text)    appends a line to a file (the mutating call the gate asks about)
    fail()             returns an isError result, to see a failure reported honestly

`save_note` writes to $MCP_TEST_NOTE_FILE, or /tmp/celeste-mcp-note.txt. Nothing here reaches the
network except the loopback HTTP listener this file starts itself, and nothing runs a command.
"""

import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROTOCOL = "2025-06-18"
SERVER_INFO = {"name": "celeste-test-server", "version": "1.0"}
NOTE_FILE = os.environ.get("MCP_TEST_NOTE_FILE", "/tmp/celeste-mcp-note.txt")

TOOLS = [
    {
        "name": "echo",
        "description": "Echo the text back. Use it to prove a tool call reached a server.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "What to echo."}},
            "required": ["text"],
        },
    },
    {
        "name": "add",
        "description": "Add two numbers and return the sum.",
        "inputSchema": {
            "type": "object",
            "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
            "required": ["a", "b"],
        },
    },
    {
        "name": "save_note",
        "description": "Append a line to the note file. This is the mutating call, so the app asks first.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "The line to append."}},
            "required": ["text"],
        },
    },
    {
        "name": "fail",
        "description": "Always reports an error, so a failure can be seen reported honestly.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


def text_result(text, is_error=False):
    return {
        "content": [{"type": "text", "text": text}],
        "isError": is_error,
    }


def call_tool(name, arguments):
    if name == "echo":
        return text_result(str(arguments.get("text", "")))
    if name == "add":
        try:
            return text_result(str(float(arguments.get("a", 0)) + float(arguments.get("b", 0))))
        except (TypeError, ValueError):
            return text_result("a and b must be numbers.", is_error=True)
    if name == "save_note":
        line = str(arguments.get("text", ""))
        with open(NOTE_FILE, "a", encoding="utf-8") as handle:
            handle.write(line + "\n")
        return text_result("saved %d characters to %s" % (len(line), NOTE_FILE))
    if name == "fail":
        return text_result("this failure is deliberate", is_error=True)
    return text_result("unknown tool: %s" % name, is_error=True)


def handle(request):
    """One JSON-RPC message in, one response object out (None for notifications)."""
    method = request.get("method")
    request_id = request.get("id")
    if request_id is None:
        # notifications/initialized and friends: no answer by contract.
        return None
    if method == "initialize":
        result = {
            "protocolVersion": PROTOCOL,
            "capabilities": {"tools": {"listChanged": True}},
            "serverInfo": SERVER_INFO,
        }
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        params = request.get("params") or {}
        result = call_tool(params.get("name"), params.get("arguments") or {})
    elif method == "ping":
        result = {}
    else:
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "error": {"code": -32601, "message": "Method not found: %s" % method},
        }
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def run_stdio():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            print("not JSON: %r" % line[:120], file=sys.stderr, flush=True)
            continue
        response = handle(request)
        if response is not None:
            print(json.dumps(response), flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep the app's debug log readable
        sys.stderr.write("[mcp-test-server] " + (fmt % args) + "\n")
        sys.stderr.flush()

    def _common_headers(self):
        self.send_header("Mcp-Session-Id", "test-session")
        self.send_header("Access-Control-Allow-Origin", "*")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        try:
            request = json.loads(body)
        except json.JSONDecodeError:
            self.send_response(400)
            self._common_headers()
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        response = handle(request)
        if response is None:
            # A notification: 202 with no body, which is what the protocol asks of a server.
            self.send_response(202)
            self._common_headers()
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        payload = json.dumps(response)
        if request.get("method") == "tools/list":
            # SSE for the list, so the client's event-stream path is exercised too.
            self.send_response(200)
            self._common_headers()
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            chunk = ("data: " + payload + "\n\n").encode("utf-8")
            self.wfile.write(("%x\r\n" % len(chunk)).encode("ascii") + chunk + b"\r\n0\r\n\r\n")
            self.wfile.flush()
            return

        raw = payload.encode("utf-8")
        self.send_response(200)
        self._common_headers()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)
        self.wfile.flush()

    def do_GET(self):
        # The server-initiated stream. One list_changed event, then keep-alives so the app's
        # transport sees a live stream; it goes away when the client closes the socket.
        self.send_response(200)
        self._common_headers()
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        try:
            self.wfile.write(
                b'data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed"}\n\n'
            )
            self.wfile.flush()
            while True:
                time.sleep(2)
                self.wfile.write(b": keep-alive\n\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            return


def run_http(port):
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("MCP test server: http://127.0.0.1:%d/mcp" % port, file=sys.stderr, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    if "--http" in sys.argv:
        port = 8765
        if "--port" in sys.argv:
            port = int(sys.argv[sys.argv.index("--port") + 1])
        run_http(port)
    else:
        run_stdio()
