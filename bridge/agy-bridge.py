#!/usr/bin/env python3
"""
agy-bridge: runs inside Termux. The Android app talks to it on localhost.

Run:   python agy-bridge.py
Check: curl http://127.0.0.1:8765/health

Skeleton version: only /health. Next step adds a /session WebSocket
that attaches a PTY running `agy`.
"""
import json
import shutil
import subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer

HOST = "127.0.0.1"
PORT = 8765
VERSION = "0.1.0"


def agy_info():
    path = shutil.which("agy")
    if not path:
        return {"agy_installed": False, "agy_path": None, "agy_version": None}
    version = None
    try:
        out = subprocess.run([path, "--version"], capture_output=True, text=True, timeout=5)
        version = (out.stdout or out.stderr).strip().splitlines()[0] if (out.stdout or out.stderr) else None
    except Exception:
        pass
    return {"agy_installed": True, "agy_path": path, "agy_version": version}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"ok": True, "bridge_version": VERSION, **agy_info()})
        else:
            self._send(404, {"ok": False, "error": "not found"})

    def log_message(self, fmt, *args):
        print("[bridge]", fmt % args)


if __name__ == "__main__":
    print(f"agy-bridge {VERSION} listening on http://{HOST}:{PORT}")
    HTTPServer((HOST, PORT), Handler).serve_forever()
