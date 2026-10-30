#!/usr/bin/env python3
"""
agy-bridge v0.2 - runs inside Termux. The Android app talks to it on localhost.

  GET  /health  -> {"ok": true, "agy_installed": ..., ...}
  POST /chat    -> body {"prompt": "...", "conversation_id": "...?", "cwd": "...?"}
                   streams agy's stream-json output back, one JSON object per line.

Each chat turn runs: agy -p PROMPT --output-format stream-json [--conversation ID]
Closing the connection (Stop button) kills the agy process.

Run:  python agy-bridge.py
"""
import json
import os
import shutil
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 8765
VERSION = "0.2.0"


def agy_info():
    path = shutil.which("agy")
    if not path:
        return {"agy_installed": False, "agy_path": None, "agy_version": None}
    version = None
    try:
        out = subprocess.run([path, "--version"], capture_output=True, text=True, timeout=5)
        text = (out.stdout or out.stderr or "").strip()
        version = text.splitlines()[-1] if text else None
    except Exception:
        pass
    return {"agy_installed": True, "agy_path": path, "agy_version": version}


def error_line(message):
    return json.dumps({"event": "result", "result": {"status": "ERROR", "response": message}}) + "\n"


class Handler(BaseHTTPRequestHandler):
    def _json(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"ok": True, "bridge_version": VERSION, **agy_info()})
        else:
            self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path != "/chat":
            return self._json(404, {"ok": False, "error": "not found"})

        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            return self._json(400, {"ok": False, "error": "bad json"})

        prompt = (body.get("prompt") or "").strip()
        if not prompt:
            return self._json(400, {"ok": False, "error": "empty prompt"})

        # Streaming response: no Content-Length, connection closes when done (HTTP/1.0).
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        agy = shutil.which("agy")
        if not agy:
            self.wfile.write(error_line("agy was not found in Termux's PATH.").encode())
            return

        cmd = [agy, "-p", prompt, "--output-format", "stream-json"]
        conv = body.get("conversation_id")
        if conv:
            cmd += ["--conversation", str(conv)]

        cwd = body.get("cwd") or os.path.expanduser("~")
        if not os.path.isdir(cwd):
            cwd = os.path.expanduser("~")

        proc = subprocess.Popen(
            cmd, cwd=cwd, text=True, bufsize=1,
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
        saw_result = False
        try:
            for line in proc.stdout:
                if '"event":"result"' in line.replace(" ", ""):
                    saw_result = True
                self.wfile.write(line.encode())
                self.wfile.flush()
            code = proc.wait()
            if not saw_result:
                self.wfile.write(error_line(f"agy exited with code {code} without a result.").encode())
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # app disconnected (Stop pressed or app closed)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait()

    def log_message(self, fmt, *args):
        print("[bridge]", fmt % args)


if __name__ == "__main__":
    print(f"agy-bridge {VERSION} listening on http://{HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
